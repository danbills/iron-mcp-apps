package ironmcp.extapps.lazyauth

import cats.effect.IO
import io.circe.Json
import io.circe.parser.parse
import io.github.iltotore.iron.*
import ironmcp.auth.*
import ironmcp.http.McpHeaders
import ironmcp.protocol.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.ci.CIString

class LazyAuthSuite extends CatsEffectSuite:

  private val hmacSecret = "01234567890123456789012345678901".getBytes("UTF-8")
  private val testValidator = TokenValidator.hs256[IO](
    hmacSecret,
    TokenValidator.Config(expectedIssuer = Some("https://auth.example.com"))
  )

  private def makeToken(sub: String, scopes: String, sid: String = "session-1"): String =
    val header = Json.obj("alg" -> Json.fromString("HS256"), "typ" -> Json.fromString("JWT"))
    val now = System.currentTimeMillis() / 1000L
    val payload = Json.obj(
      "sub"   -> Json.fromString(sub),
      "iss"   -> Json.fromString("https://auth.example.com"),
      "scope" -> Json.fromString(scopes),
      "exp"   -> Json.fromLong(now + 300L),
      "sid"   -> Json.fromString(sid)
    )
    Jwt.signHs256(header, payload, hmacSecret)

  private val metaJson = """{"io.modelcontextprotocol/protocolVersion":"2026-07-28","io.modelcontextprotocol/clientCapabilities":{}}"""

  private val meta = RequestMeta(
    protocolVersion = LatestProtocolVersion,
    clientCapabilities = ClientCapabilities.none
  )

  private def callToolBody(name: String, id: Int): String =
    s"""{"jsonrpc":"2.0","id":$id,"method":"tools/call","params":{"name":"$name","arguments":{},"_meta":$metaJson}}"""

  test("server loads and registers public show_auth_button, protected get_secret and revoke_auth_token"):
    for
      server <- LazyAuthServer.load
      toolsResult <- server.tools.get.list(ListToolsParams(meta))
      names = toolsResult.tools.map(_.name: String).toSet
      _ = assert(names.contains("show_auth_button"))
      _ = assert(names.contains("get_secret"))
      _ = assert(names.contains("revoke_auth_token"))

      // Both UI resources are registered and readable
      resources = server.resources.get
      btnRead <- resources.read(ReadResourceParams(uri = (LazyAuthServer.ButtonResourceUri: String).assume, _meta = meta))
      _ = assert(btnRead match
        case r: ReadResourceResult => r.contents.headOption.exists {
          case t: ResourceContents.Text => t.text.contains("Public App")
          case _                        => false
        }
        case _ => false
      )

      secRead <- resources.read(ReadResourceParams(uri = (LazyAuthServer.SecretResourceUri: String).assume, _meta = meta))
      _ = assert(secRead match
        case r: ReadResourceResult => r.contents.headOption.exists {
          case t: ResourceContents.Text => t.text.contains("Protected App")
          case _                        => false
        }
        case _ => false
      )
    yield ()

  test("metadataRoutes serve RFC 9728 Protected Resource Metadata at well-known endpoints"):
    val routes = LazyAuthServer.metadataRoutes("http://localhost:3214/mcp", LazyAuthServer.DefaultKeycloakRealm)
    val req = Request[IO](org.http4s.Method.GET, Uri.unsafeFromString("/.well-known/oauth-protected-resource/mcp"))
    routes.orNotFound.run(req).flatMap { res =>
      assertEquals(res.status, Status.Ok)
      res.as[String].map { body =>
        val json = parse(body).toOption.get
        assertEquals(json.hcursor.get[String]("resource"), Right("http://localhost:3214/mcp"))
        assertEquals(json.hcursor.get[List[String]]("authorization_servers"), Right(List(LazyAuthServer.DefaultKeycloakRealm)))
        assert(json.hcursor.get[List[String]]("scopes_supported").toOption.exists(_.contains("mcp:read")))
      }
    }

  test("lazy auth HTTP: public tools and metadata succeed with no auth"):
    for
      pages <- LazyAuthServer.loadPages
      (btn, sec) = pages
      routes = LazyAuthServer.routes(btn, sec, testValidator)

      callReq = Request[IO](org.http4s.Method.POST, Uri.unsafeFromString("/mcp"))
        .putHeaders(
          Header.Raw(McpHeaders.ProtocolVersion, LatestProtocolVersion),
          Header.Raw(McpHeaders.Method, "tools/call"),
          Header.Raw(McpHeaders.Name, McpHeaders.encode("show_auth_button")),
          Header.Raw(CIString("Host"), "localhost:3214")
        )
        .withEntity(callToolBody("show_auth_button", 1))

      res <- routes.orNotFound.run(callReq)
      _ = assertEquals(res.status, Status.Ok)
    yield ()

  test("lazy auth HTTP: calling protected get_secret with no auth returns 401 with WWW-Authenticate"):
    for
      pages <- LazyAuthServer.loadPages
      (btn, sec) = pages
      routes = LazyAuthServer.routes(btn, sec, testValidator)

      callReq = Request[IO](org.http4s.Method.POST, Uri.unsafeFromString("/mcp"))
        .putHeaders(
          Header.Raw(McpHeaders.ProtocolVersion, LatestProtocolVersion),
          Header.Raw(McpHeaders.Method, "tools/call"),
          Header.Raw(McpHeaders.Name, McpHeaders.encode("get_secret")),
          Header.Raw(CIString("Host"), "localhost:3214")
        )
        .withEntity(callToolBody("get_secret", 2))

      res <- routes.orNotFound.run(callReq)
      _ = assertEquals(res.status, Status.Unauthorized)
      wwwAuth = res.headers.get(CIString("WWW-Authenticate")).map(_.head.value)
      _ = assert(wwwAuth.exists(_.startsWith("Bearer resource_metadata=")), wwwAuth)
    yield ()

  test("lazy auth HTTP: calling get_secret with insufficient scope returns 403 Forbidden with scope challenge"):
    for
      pages <- LazyAuthServer.loadPages
      (btn, sec) = pages
      routes = LazyAuthServer.routes(btn, sec, testValidator)

      token = makeToken("user-bob", "mcp:unrelated")
      callReq = Request[IO](org.http4s.Method.POST, Uri.unsafeFromString("/mcp"))
        .putHeaders(
          Header.Raw(McpHeaders.ProtocolVersion, LatestProtocolVersion),
          Header.Raw(McpHeaders.Method, "tools/call"),
          Header.Raw(McpHeaders.Name, McpHeaders.encode("get_secret")),
          Header.Raw(CIString("Authorization"), s"Bearer $token"),
          Header.Raw(CIString("Host"), "localhost:3214")
        )
        .withEntity(callToolBody("get_secret", 3))

      res <- routes.orNotFound.run(callReq)
      _ = assertEquals(res.status, Status.Forbidden)
      wwwAuth = res.headers.get(CIString("WWW-Authenticate")).map(_.head.value)
      _ = assert(wwwAuth.exists(_.contains("insufficient_scope")), wwwAuth)
      _ = assert(wwwAuth.exists(_.contains("scope=\"mcp:read\"")), wwwAuth)
    yield ()

  test("lazy auth HTTP: calling get_secret with valid token returns secret, revoke_auth_token revokes it"):
    for
      pages <- LazyAuthServer.loadPages
      (btn, sec) = pages
      routes = LazyAuthServer.routes(btn, sec, testValidator)

      token = makeToken("user-alice", "mcp:read", sid = "session-alice-123")
      callReq = Request[IO](org.http4s.Method.POST, Uri.unsafeFromString("/mcp"))
        .putHeaders(
          Header.Raw(McpHeaders.ProtocolVersion, LatestProtocolVersion),
          Header.Raw(McpHeaders.Method, "tools/call"),
          Header.Raw(McpHeaders.Name, McpHeaders.encode("get_secret")),
          Header.Raw(CIString("Authorization"), s"Bearer $token"),
          Header.Raw(CIString("Host"), "localhost:3214")
        )
        .withEntity(callToolBody("get_secret", 4))

      res1 <- routes.orNotFound.run(callReq)
      _ = assertEquals(res1.status, Status.Ok)
      bodyStr1 <- res1.as[String]
      _ = assert(bodyStr1.contains("the-answer-is-42"), bodyStr1)
      _ = assert(bodyStr1.contains("user-alice"), bodyStr1)

      // Now call revoke_auth_token with this session
      revokeReq = Request[IO](org.http4s.Method.POST, Uri.unsafeFromString("/mcp"))
        .putHeaders(
          Header.Raw(McpHeaders.ProtocolVersion, LatestProtocolVersion),
          Header.Raw(McpHeaders.Method, "tools/call"),
          Header.Raw(McpHeaders.Name, McpHeaders.encode("revoke_auth_token")),
          Header.Raw(CIString("Authorization"), s"Bearer $token"),
          Header.Raw(CIString("Host"), "localhost:3214")
        )
        .withEntity(callToolBody("revoke_auth_token", 5))

      resRevoke <- routes.orNotFound.run(revokeReq)
      _ = assertEquals(resRevoke.status, Status.Ok)
      bodyRevoke <- resRevoke.as[String]
      _ = assert(bodyRevoke.contains("Session revoked"), bodyRevoke)

      // Subsequent call with the revoked session is rejected with 401
      res2 <- routes.orNotFound.run(callReq)
      _ = assertEquals(res2.status, Status.Unauthorized)
      bodyStr2 <- res2.as[String]
      _ = assert(bodyStr2.contains("Session has been revoked"), bodyStr2)
    yield ()

  test("live danarch Keycloak integration: RS256 token verification against live Keycloak"):
    import scala.concurrent.duration.*
    EmberClientBuilder.default[IO].build.use { client =>
      val kc = new KeycloakClient[IO](client, Uri.unsafeFromString(LazyAuthServer.DefaultKeycloakRealm))
      kc.discover.timeout(2.seconds).attempt.flatMap {
        case Left(_) =>
          IO(println("Keycloak on danarch not reachable; skipping live integration test."))
        case Right(oidc) =>
          for
            // Acquire live token with mcp:read scope from danarch Keycloak
            tokenEndpoint = Uri.unsafeFromString(oidc.token_endpoint)
            tok <- kc.clientCredentials(tokenEndpoint, "iron-mcp-client", "iron-mcp-secret-12345", scopes = Set("mcp:read"))
            _ = assert(tok.grantedScopes.contains("mcp:read"))

            // Build LazyAuthServer routes wired to Keycloak's published JWKS
            pages <- LazyAuthServer.loadPages
            (btn, sec) = pages
            validator <- LazyAuthServer.keycloakValidator(client, LazyAuthServer.DefaultKeycloakRealm)
            routes = LazyAuthServer.routes(btn, sec, validator)

            // Call protected get_secret with the live Keycloak RS256 Bearer token
            req = Request[IO](org.http4s.Method.POST, Uri.unsafeFromString("/mcp"))
              .putHeaders(
                Header.Raw(McpHeaders.ProtocolVersion, LatestProtocolVersion),
                Header.Raw(McpHeaders.Method, "tools/call"),
                Header.Raw(McpHeaders.Name, McpHeaders.encode("get_secret")),
                Header.Raw(CIString("Authorization"), s"Bearer ${tok.access_token}"),
                Header.Raw(CIString("Host"), "localhost:3214")
              )
              .withEntity(callToolBody("get_secret", 42))

            res <- routes.orNotFound.run(req)
            _ = assertEquals(res.status, Status.Ok)
            bodyStr <- res.as[String]
            _ = assert(bodyStr.contains("the-answer-is-42"), bodyStr)
          yield ()
      }
    }
