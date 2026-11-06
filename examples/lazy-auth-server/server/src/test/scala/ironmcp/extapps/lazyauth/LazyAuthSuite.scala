package ironmcp.extapps.lazyauth

import cats.effect.IO
import io.circe.{Json, JsonObject}
import io.circe.parser.parse
import io.circe.syntax.*
import io.github.iltotore.iron.*
import ironmcp.auth.*
import ironmcp.http.McpHeaders
import ironmcp.protocol.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString

class LazyAuthSuite extends CatsEffectSuite:

  private val issuer     = "https://auth.example.com"
  private val resource   = LazyAuthServer.DefaultResource
  private val hmacSecret = "01234567890123456789012345678901".getBytes("UTF-8")
  private val validator  = TokenValidator.sharedSecret[IO](hmacSecret, TokenValidator.Config(issuer, resource.resource, algorithms = Set(JwsAlgorithm.HS256)))

  private def token(sub: String, scopes: String, sid: String = "session-1", aud: String = resource.resource): String =
    val now = System.currentTimeMillis() / 1000L
    Jwt.sign(
      JwsAlgorithm.HS256,
      Jwt.hmacKey(hmacSecret),
      JsonObject("typ" -> "at+jwt".asJson),
      Json.obj("sub" -> sub.asJson, "iss" -> issuer.asJson, "aud" -> aud.asJson, "scope" -> scopes.asJson, "exp" -> (now + 300).asJson, "sid" -> sid.asJson)
    )

  private val metaJson = """{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""
  private val meta     = RequestMeta(protocolVersion = LatestProtocolVersion, clientCapabilities = ClientCapabilities.none)

  private def call(name: String, bearer: Option[String] = None): Request[IO] =
    Request[IO](org.http4s.Method.POST, Uri.unsafeFromString("/mcp"))
      .putHeaders(
        Header.Raw(McpHeaders.ProtocolVersion, LatestProtocolVersion),
        Header.Raw(McpHeaders.Method, "tools/call"),
        Header.Raw(McpHeaders.Name, McpHeaders.encode(name)),
        Header.Raw(CIString("Host"), "localhost:3207")
      )
      .putHeaders(bearer.map(t => Header.Raw(CIString("Authorization"), s"Bearer $t")).toList)
      .withEntity(s"""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":{},"_meta":$metaJson}}""")

  private def app =
    for
      (btn, sec)  <- LazyAuthServer.loadPages
      revocations <- RevocationList.inMemory[IO]
    yield LazyAuthServer.routes(btn, sec, validator, revocations, audit = AuthAudit.noop[IO]).orNotFound

  private def structured(res: Response[IO]): IO[Json] =
    res.as[String].map(b => parse(b).toOption.flatMap(_.hcursor.downField("result").downField("structuredContent").focus).getOrElse(Json.Null))

  test("tools and Views are registered; every tool declares who may call it"):
    for
      server <- LazyAuthServer.load
      listed <- server.tools.get.list(ListToolsParams(meta))
      btn    <- server.resources.get.read(ReadResourceParams(uri = (LazyAuthServer.ButtonResourceUri: String).assume, _meta = meta))
      revocations <- RevocationList.inMemory[IO]
    yield
      val all = LazyAuthServer.tools(None, revocations)
      assertEquals(LazyAuthServer.policy(revocations).undeclared(all*), Nil)
      assertEquals(listed.tools.map(_.name: String).toSet, Set("show_auth_button", "whoami")) // anonymous: public tools only
      btn match
        case r: ReadResourceResult => assert(r.contents.exists { case t: ResourceContents.Text => t.text.contains("Public App"); case _ => false })
        case other                 => fail(other.toString)

  test("metadata is served where the challenge points, for this resource"):
    for
      routes <- app
      res    <- routes.run(Request[IO](org.http4s.Method.GET, Uri.unsafeFromString("/.well-known/oauth-protected-resource/mcp")))
      body   <- res.as[String]
    yield
      val c = parse(body).toOption.get.hcursor
      assertEquals(c.get[String]("resource"), Right(resource.resource))
      assertEquals(c.get[List[String]]("authorization_servers"), Right(List(LazyAuthServer.DefaultKeycloakRealm)))
      assertEquals(c.get[List[String]]("scopes_supported"), Right(List("mcp:read")))

  test("public tools need no token; whoami says what signing in unlocks"):
    for
      routes <- app
      ok     <- routes.run(call("show_auth_button"))
      who    <- routes.run(call("whoami")).flatMap(structured)
    yield
      assertEquals(ok.status, Status.Ok)
      assertEquals(who.hcursor.get[Boolean]("signedIn"), Right(false))
      assertEquals(who.hcursor.downField("locked").as[List[Json]].map(_.flatMap(_.hcursor.get[String]("unlockWith").toOption)), Right(List("unlock_secret", "unlock_secret")))

  test("a host can read the Views' pages before sign-in (over HTTP, as basic-host does)"):
    val read = Request[IO](org.http4s.Method.POST, Uri.unsafeFromString("/mcp"))
      .putHeaders(
        Header.Raw(McpHeaders.ProtocolVersion, LatestProtocolVersion),
        Header.Raw(McpHeaders.Method, "resources/read"),
        Header.Raw(McpHeaders.Name, McpHeaders.encode(LazyAuthServer.ButtonResourceUri)),
        Header.Raw(CIString("Host"), "localhost:3207")
      )
      .withEntity(s"""{"jsonrpc":"2.0","id":1,"method":"resources/read","params":{"uri":"${LazyAuthServer.ButtonResourceUri}","_meta":$metaJson}}""")
    for
      routes <- app
      res    <- routes.run(read)
      body   <- res.as[String]
    yield
      assertEquals(res.status, Status.Ok)
      assert(body.contains("Public App"))

  test("a protected tool without a token gets 401 with resource_metadata where the metadata really is"):
    for
      routes <- app
      res    <- routes.run(call("get_secret"))
    yield
      assertEquals(res.status, Status.Unauthorized)
      val c = AuthChallenge.parse(res.headers.get(CIString("WWW-Authenticate")).toList.flatMap(_.toList.map(_.value))).head
      assertEquals(c.param("resource_metadata"), Some("http://localhost:3207/.well-known/oauth-protected-resource/mcp"))
      assertEquals(c.scopes, Set("mcp:read"))

  test("insufficient scope is a 403 naming mcp:read"):
    for
      routes <- app
      res    <- routes.run(call("get_secret", Some(token("bob", "mcp:unrelated"))))
    yield
      assertEquals(res.status, Status.Forbidden)
      assertEquals(AuthChallenge.parse(List(res.headers.get(CIString("WWW-Authenticate")).get.head.value)).head.scopes, Set("mcp:read"))

  test("a token minted for another server is refused (the audience check the old example lacked)"):
    for
      routes <- app
      res    <- routes.run(call("get_secret", Some(token("alice", "mcp:read", aud = "https://other.example/mcp"))))
    yield assertEquals(res.status, Status.Unauthorized)

  test("an invalid token on a public tool is refused, not treated as anonymous"):
    for
      routes <- app
      res    <- routes.run(call("show_auth_button", Some(token("alice", "mcp:read").dropRight(3) + "AAA")))
    yield assertEquals(res.status, Status.Unauthorized)

  test("get_secret with mcp:read returns the secret; after revoke_auth_token the session's tokens are refused as revoked"):
    val alice = token("alice", "mcp:read", sid = "session-alice-123")
    for
      routes  <- app
      secret  <- routes.run(call("get_secret", Some(alice))).flatMap(structured)
      revoke  <- routes.run(call("revoke_auth_token", Some(alice))).flatMap(structured)
      after   <- routes.run(call("get_secret", Some(alice)))
      body    <- after.as[String]
    yield
      assertEquals(secret.hcursor.get[String]("secret"), Right("the-answer-is-42"))
      assertEquals(secret.hcursor.get[String]("subject"), Right("alice"))
      assertEquals(revoke.hcursor.get[Boolean]("revoked"), Right(true))
      assertEquals(after.status, Status.Unauthorized)
      assert(body.contains("revoked"), body)

  test("live Keycloak (iron-mcp realm): a real token reaches get_secret"):
    val env = List("IRON_MCP_KC_REALM", "IRON_MCP_KC_CLIENT", "IRON_MCP_KC_SECRET", "IRON_MCP_KC_RESOURCE").map(k => k -> sys.env.get(k)).toMap
    assume(env.values.forall(_.isDefined), "set IRON_MCP_KC_* (scripts/keycloak-realm.sh in iron-mcp prints them) to run against Keycloak")
    EmberClientBuilder.default[IO].build.use { client =>
      val realm = env("IRON_MCP_KC_REALM").get
      val live  = ResourceIdentity.unsafe(env("IRON_MCP_KC_RESOURCE").get)
      val kc    = new KeycloakClient[IO](client, Uri.unsafeFromString(realm))
      for
        oidc        <- kc.discover
        tok         <- kc.clientCredentials(oidc, ClientAuth.SecretPost(env("IRON_MCP_KC_CLIENT").get, env("IRON_MCP_KC_SECRET").get), Set("mcp:read"))
        (btn, sec)  <- LazyAuthServer.loadPages
        keycloak    <- LazyAuthServer.keycloakValidator(client, oidc.issuer, live)
        revocations <- RevocationList.inMemory[IO]
        routes       = LazyAuthServer.routes(btn, sec, keycloak, revocations, live, oidc.issuer, AuthAudit.noop[IO]).orNotFound
        res         <- routes.run(call("get_secret", Some(tok.access_token)))
      yield assertEquals(res.status, Status.Ok)
    }
