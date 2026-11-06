package ironmcp.extapps.lazyauth

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.Encoder
import io.github.iltotore.iron.*
import ironmcp.apps.*
import ironmcp.auth.*
import ironmcp.extapps.{ExampleServer, NoArgs, ViewPage}
import ironmcp.http.McpHttp
import ironmcp.protocol.*
import ironmcp.schema.JsonSchemaOf
import ironmcp.server.*
import org.http4s.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder

/** Lazy Auth Server, on iron-mcp-auth:
  *
  *  - Anyone may connect, list tools and call `show_auth_button` and
  *    `whoami` without a token (`McpAuthServer.routesForOptional`).
  *  - `get_secret` and `revoke_auth_token` need `mcp:read`. The first call
  *    without a token gets a `401` pointing at the RFC 9728 metadata, so the
  *    host signs in with Keycloak and retries; tokens are checked for issuer,
  *    audience (this server's URL), type, expiry and revocation.
  *  - `unlock_secret` needs `mcp:read` and does nothing else: the View calls
  *    it to sign in up front, without fetching the secret.
  *
  * Every rule is declared on its tool (`.public`, `.requires`), and a tool
  * without one cannot be called at all.
  */
object LazyAuthServer:

  val ButtonResourceUri: UiUri = "ui://lazy-auth/mcp-app.html"
  val SecretResourceUri: UiUri = "ui://lazy-auth/secret-app.html"
  val DefaultKeycloakRealm: String = "https://danarch.tail09c16c.ts.net:10000/realms/iron-mcp"
  val DefaultResource: ResourceIdentity = ResourceIdentity.unsafe("http://localhost:3207/mcp")

  final case class Secret(subject: String, secret: String, issuedAt: String) derives Encoder.AsObject, JsonSchemaOf
  final case class Revoked(revoked: Boolean, session: Option[String]) derives Encoder.AsObject, JsonSchemaOf

  /** The tools for one caller. Their access rules do not depend on the
    * caller, so [[policy]] is built from them once.
    */
  def tools(caller: Option[TokenClaims], revocations: RevocationList[IO]): List[McpTool[IO]] =
    val showAuthButton = McpTool[NoArgs]("show_auth_button", "Shows a button that calls the protected get_secret tool.", title = Some("Show Auth Button"))(_ =>
      IO.pure("ok")
    ).withUi(ButtonResourceUri).public

    val getSecret = McpTool[NoArgs]("get_secret", "Returns secret data. Requires sign-in.", title = Some("Get Secret"))(_ =>
      caller match
        case None => IO.pure(Left("sign-in required"))
        case Some(claims) =>
          IO.realTimeInstant.map(now => Right(Secret(claims.sub.getOrElse("unknown"), "the-answer-is-42", now.toString)))
    ).withUi(SecretResourceUri).requires("mcp:read")

    val revokeAuthToken = McpTool[NoArgs]("revoke_auth_token", "Revokes the caller's session on this server.", title = Some("Revoke Auth Token"))(_ =>
      caller match
        case None         => IO.pure(Left("sign-in required"))
        case Some(claims) => revocations.revoke(claims).as(Right(Revoked(revoked = true, claims.sid)))
    ).requires("mcp:read")

    val base   = AuthPolicy.fromTools(showAuthButton, getSecret, revokeAuthToken)
    val unlock = SessionTools.unlock[IO]("unlock_secret", caller, base)("mcp:read").withVisibility(Visibility.App)
    val whoami = SessionTools.whoami[IO](caller, base.withTools(unlock))
    List(showAuthButton, getSecret, revokeAuthToken, unlock, whoami)

  /** Who may do what: read off the tools, with listing and the Views'
    * pages open to anonymous callers (the host fetches them before sign-in).
    */
  def policy(revocations: RevocationList[IO]): AuthPolicy =
    AuthPolicy.fromTools(tools(None, revocations)*).views().publicListing

  def createServer(buttonHtml: String, secretHtml: String, caller: Option[TokenClaims], revocations: RevocationList[IO]): McpServer[IO] =
    McpServer(
      info = Implementation(name = "Lazy Auth Demo", version = "2.0.0"),
      tools = Some(ScopedTools(ToolSet(tools(caller, revocations)), policy(revocations), caller)),
      resources = Some(
        UiResources.of[IO](
          UiResource(ButtonResourceUri, "ui://lazy-auth/mcp-app.html", buttonHtml, Some("Lazy Auth Demo")),
          UiResource(SecretResourceUri, "ui://lazy-auth/secret-app.html", secretHtml, Some("Lazy Auth Demo (Secret)"))
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  def config(resource: ResourceIdentity, keycloakRealm: String, revocations: RevocationList[IO]): McpAuthServer.Config =
    McpAuthServer.Config(resource, List(keycloakRealm), policy(revocations), http = McpHttp.Config(cors = true), resourceName = Some("Lazy Auth Demo"))

  /** The MCP endpoint and its RFC 9728 metadata. Tokens revoked through
    * `revoke_auth_token` are refused from then on.
    */
  def routes(
      buttonHtml: String,
      secretHtml: String,
      validator: TokenValidator[IO],
      revocations: RevocationList[IO],
      resource: ResourceIdentity = DefaultResource,
      keycloakRealm: String = DefaultKeycloakRealm,
      audit: AuthAudit[IO] = AuthAudit.jsonLines[IO](line => IO.println(s"auth $line"))
  ): HttpRoutes[IO] =
    McpAuthServer.routesForOptional(validator.withRevocation(revocations), config(resource, keycloakRealm, revocations), audit) { (_, caller) =>
      IO.pure(Right(createServer(buttonHtml, secretHtml, caller, revocations)))
    }

  def loadPages: IO[(String, String)] =
    (ViewPage.load("lazy-auth-server/button-view"), ViewPage.load("lazy-auth-server/secret-view")).tupled

  /** Keycloak's tokens for this server: its JWKS, its issuer, this resource as audience. */
  def keycloakValidator(client: Client[IO], keycloakRealm: String, resource: ResourceIdentity): IO[TokenValidator[IO]] =
    JwksProvider
      .remote[IO](client, Uri.unsafeFromString(s"${keycloakRealm.stripSuffix("/")}/protocol/openid-connect/certs"))
      .map(keys => TokenValidator.jwks[IO](keys, TokenValidator.Config(keycloakRealm, resource)))

  def loadRoutes(
      resource: ResourceIdentity = DefaultResource,
      keycloakRealm: String = DefaultKeycloakRealm,
      client: Option[Client[IO]] = None
  ): Resource[IO, HttpRoutes[IO]] =
    for
      (buttonHtml, secretHtml) <- Resource.eval(loadPages)
      c <- client.fold(EmberClientBuilder.default[IO].build)(Resource.pure)
      validator   <- Resource.eval(keycloakValidator(c, keycloakRealm, resource))
      revocations <- Resource.eval(RevocationList.inMemory[IO])
    yield routes(buttonHtml, secretHtml, validator, revocations, resource, keycloakRealm)

  def load: IO[McpServer[IO]] =
    (loadPages, RevocationList.inMemory[IO]).flatMapN((pages, revocations) => IO.pure(createServer(pages._1, pages._2, None, revocations)))

object Main extends cats.effect.IOApp:
  def run(args: List[String]): IO[cats.effect.ExitCode] =
    val port     = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(3097)
    val realm    = sys.env.getOrElse("KEYCLOAK_REALM", LazyAuthServer.DefaultKeycloakRealm)
    val resource = ResourceIdentity.unsafe(sys.env.getOrElse("PUBLIC_URL", s"http://localhost:$port/mcp"))
    LazyAuthServer.loadRoutes(resource, realm).use { routes =>
      ExampleServer.httpRoutes(routes, port).use { _ =>
        IO.println(s"lazy-auth-server: listening on $resource (Keycloak: $realm)") >> IO.never
      }
    }.as(cats.effect.ExitCode.Success)
