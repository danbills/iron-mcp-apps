package ironmcp.extapps.lazyauth

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import io.circe.syntax.*
import io.github.iltotore.iron.*
import ironmcp.apps.*
import ironmcp.auth.*
import ironmcp.extapps.{ExampleServer, NoArgs, ViewPage}
import ironmcp.http.{McpHeaders, McpHttp}
import ironmcp.protocol.*
import ironmcp.server.*
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.headers.`Content-Type`
import org.typelevel.ci.CIString

import java.util.concurrent.ConcurrentHashMap

/** Lazy Auth Server:
  *
  *  - Connects without authentication: `initialize`, `tools/list`, and public
  *    tools (`show_auth_button`) succeed with no token.
  *  - Protected tools (`get_secret`, `revoke_auth_token`) respond 401 with a
  *    `WWW-Authenticate` header pointing at RFC 9728 Protected Resource Metadata,
  *    directing the host to the Keycloak Authorization Server on danarch.
  *  - The `show_auth_button` MCP App is public; clicking "Auth me" calls the
  *    protected `get_secret` tool via `callServerTool`. The host receives the 401,
  *    runs OAuth with Keycloak, retries, and the result renders inline.
  */
object LazyAuthServer:

  val ButtonResourceUri: UiUri = "ui://lazy-auth/mcp-app.html"
  val SecretResourceUri: UiUri = "ui://lazy-auth/secret-app.html"
  val DefaultKeycloakRealm: String = "https://danarch.tail09c16c.ts.net:10000/realms/hacker-bros"

  val ProtectedTools: Set[String] = Set("get_secret", "revoke_auth_token")

  val defaultScopePolicy: ToolScopePolicy = ToolScopePolicy.exact(
    Map(
      "get_secret"        -> Set("mcp:read"),
      "revoke_auth_token" -> Set("mcp:read")
    )
  )

  /** Revoked session IDs (`sid`). */
  val revokedSids: java.util.Set[String] = ConcurrentHashMap.newKeySet[String]()

  def createServer(
      buttonHtml: String,
      secretHtml: String,
      authClaims: Option[TokenClaims]
  ): McpServer[IO] =
    val showAuthButton = McpTool[NoArgs](
      "show_auth_button",
      "Public tool: shows a button that triggers the protected get_secret tool.",
      title = Some("Show Auth Button")
    )(_ => IO.pure(CallToolResult(List(ContentBlock.Text("ok"))))).withUi(ButtonResourceUri)

    val getSecret = McpTool[NoArgs](
      "get_secret",
      "Protected tool: returns secret data. Requires authentication.",
      title = Some("Get Secret")
    ) { _ =>
      authClaims match
        case None =>
          IO.pure(CallToolResult(List(ContentBlock.Text("Authentication required.")), isError = Some(true)))
        case Some(claims) =>
          for
            now <- IO.realTimeInstant
            sub = claims.sub.getOrElse("anonymous")
            json = io.circe.Json.obj(
              "subject"  -> io.circe.Json.fromString(sub),
              "secret"   -> io.circe.Json.fromString("the-answer-is-42"),
              "issuedAt" -> io.circe.Json.fromString(now.toString)
            )
          yield CallToolResult(List(ContentBlock.Text(json.spaces2)))
    }.withUi(SecretResourceUri)

    val revokeAuthToken = McpTool[NoArgs](
      "revoke_auth_token",
      "Protected tool: revokes the caller's entire auth session (access + refresh token).",
      title = Some("Revoke Auth Token")
    ) { _ =>
      authClaims match
        case None =>
          IO.pure(CallToolResult(List(ContentBlock.Text("Authentication required.")), isError = Some(true)))
        case Some(claims) =>
          val sidOpt = claims.rawJson.hcursor.get[String]("sid").toOption
          sidOpt.foreach(revokedSids.add)
          IO.pure(
            CallToolResult(
              List(
                ContentBlock.Text(
                  s"Session revoked (sid: ${sidOpt.getOrElse("unknown")}). Access + refresh token invalid."
                )
              )
            )
          )
    }

    McpServer(
      info = Implementation(name = "Lazy Auth Demo", version = "1.0.0"),
      tools = Some(ToolSet.of(showAuthButton, getSecret, revokeAuthToken)),
      resources = Some(
        UiResources.of[IO](
          UiResource(ButtonResourceUri, "ui://lazy-auth/mcp-app.html", buttonHtml, Some("Lazy Auth Demo")),
          UiResource(SecretResourceUri, "ui://lazy-auth/secret-app.html", secretHtml, Some("Lazy Auth Demo (Secret)"))
        )
      ),
      extensions = Some(Map(McpApps.serverExtension))
    )

  /** Serves RFC 9728 Protected Resource Metadata pointing to Keycloak. */
  def metadataRoutes(serverUrl: String, keycloakRealm: String): HttpRoutes[IO] =
    val prm = ProtectedResourceMetadata(
      resource = serverUrl,
      authorization_servers = List(keycloakRealm),
      scopes_supported = Some(List("mcp:read", "mcp:export", "mcp:admin")),
      bearer_methods_supported = Some(List("header"))
    )
    val rootPath = "/.well-known/oauth-protected-resource"
    val mcpPath  = s"$rootPath/mcp"
    val prmPath  = "/auth/prm"

    HttpRoutes.of[IO] {
      case req if req.method == org.http4s.Method.GET && Set(rootPath, mcpPath, prmPath).contains(req.uri.path.renderString) =>
        IO.pure(
          Response[IO](Status.Ok)
            .putHeaders(Header.Raw(CIString("Access-Control-Allow-Origin"), "*"))
            .withEntity(prm.asJson)
            .withContentType(`Content-Type`(MediaType.application.json))
        )
    }

  def routes(
      buttonHtml: String,
      secretHtml: String,
      validator: TokenValidator[IO],
      serverUrl: String = "http://localhost:3214/mcp",
      keycloakRealm: String = DefaultKeycloakRealm,
      scopePolicy: ToolScopePolicy = defaultScopePolicy
  ): HttpRoutes[IO] =
    val prmUrl = s"${serverUrl.stripSuffix("/")}/.well-known/oauth-protected-resource/mcp"
    val wellKnown = metadataRoutes(serverUrl, keycloakRealm)

    val mcpConfig = McpHttp.Config(path = "/mcp", cors = true)
    val mcpRoutes = McpHttp.routesFor[IO](mcpConfig) { req =>
      val isToolsCall = req.headers.get(McpHeaders.Method).exists(_.head.value == "tools/call")
      val toolNameOpt = req.headers.get(McpHeaders.Name).map(_.head.value).flatMap(McpHeaders.decode)
      val isProtected = isToolsCall && toolNameOpt.exists(ProtectedTools.contains)

      val authHeader = req.headers.get(CIString("Authorization")).map(_.head.value)
      val tokenOpt = authHeader.flatMap { h =>
        if h.startsWith("Bearer ") then Some(h.substring("Bearer ".length).trim)
        else None
      }

      if isProtected then
        tokenOpt match
          case None =>
            IO.pure(Left(unauthorizedResponse(AuthError.MissingToken, prmUrl)))
          case Some(token) =>
            validator.validate(token).flatMap {
              case Left(err) =>
                IO.pure(Left(unauthorizedResponse(err, prmUrl)))
              case Right(claims) =>
                val sidOpt = claims.rawJson.hcursor.get[String]("sid").toOption
                if sidOpt.exists(revokedSids.contains) then
                  IO.pure(
                    Left(
                      unauthorizedResponse(
                        AuthError.InvalidSignature("Session has been revoked"),
                        prmUrl
                      )
                    )
                  )
                else
                  val toolName = toolNameOpt.getOrElse("")
                  scopePolicy.check(toolName, claims.scopes) match
                    case Left(required) =>
                      IO.pure(Left(forbiddenResponse(required, claims, prmUrl)))
                    case Right(()) =>
                      IO.pure(Right(createServer(buttonHtml, secretHtml, Some(claims))))
            }
      else
        tokenOpt match
          case Some(token) =>
            validator.validate(token).map {
              case Right(claims) => Right(createServer(buttonHtml, secretHtml, Some(claims)))
              case Left(_)       => Right(createServer(buttonHtml, secretHtml, None))
            }
          case None =>
            IO.pure(Right(createServer(buttonHtml, secretHtml, None)))
    }

    wellKnown <+> mcpRoutes

  private def unauthorizedResponse(err: AuthError, resourceMetadataUrl: String): Response[IO] =
    val errorKind = err match
      case AuthError.MissingToken => "invalid_request"
      case _                      => "invalid_token"

    val challenge =
      if err == AuthError.MissingToken then s"""Bearer resource_metadata="$resourceMetadataUrl""""
      else s"""Bearer error="$errorKind", error_description="${err.description}", resource_metadata="$resourceMetadataUrl""""

    val body = io.circe.Json.obj(
      "error"             -> errorKind.asJson,
      "error_description" -> err.description.asJson
    )
    Response[IO](Status.Unauthorized)
      .putHeaders(
        Header.Raw(CIString("WWW-Authenticate"), challenge),
        Header.Raw(CIString("Access-Control-Expose-Headers"), "WWW-Authenticate, MCP-Protocol-Version")
      )
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

  private def forbiddenResponse(required: Set[String], claims: TokenClaims, resourceMetadataUrl: String): Response[IO] =
    val desc = s"Requires [${required.mkString(" ")}]; the token grants [${claims.scopes.mkString(" ")}]."
    val challenge =
      s"""Bearer error="insufficient_scope", error_description="$desc", scope="${required.mkString(" ")}", resource_metadata="$resourceMetadataUrl""""
    val body = io.circe.Json.obj(
      "error"             -> "insufficient_scope".asJson,
      "error_description" -> desc.asJson
    )
    Response[IO](Status.Forbidden)
      .putHeaders(
        Header.Raw(CIString("WWW-Authenticate"), challenge),
        Header.Raw(CIString("Access-Control-Expose-Headers"), "WWW-Authenticate, MCP-Protocol-Version")
      )
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))

  def loadPages: IO[(String, String)] =
    (ViewPage.load("lazy-auth-server/button-view"), ViewPage.load("lazy-auth-server/secret-view")).tupled

  def keycloakValidator(client: Client[IO], keycloakRealm: String): IO[TokenValidator[IO]] =
    val jwksUri = org.http4s.Uri.unsafeFromString(s"${keycloakRealm.stripSuffix("/")}/protocol/openid-connect/certs")
    JwksProvider.keycloak[IO](client, jwksUri).map { jwks =>
      TokenValidator.rs256[IO](jwks, TokenValidator.Config(expectedIssuer = Some(keycloakRealm)))
    }

  def loadRoutes(
      serverUrl: String = "http://localhost:3214/mcp",
      keycloakRealm: String = DefaultKeycloakRealm,
      client: Option[Client[IO]] = None
  ): Resource[IO, HttpRoutes[IO]] =
    for
      pages <- Resource.eval(loadPages)
      (buttonHtml, secretHtml) = pages
      c <- client match
        case Some(existing) => Resource.pure[IO, Client[IO]](existing)
        case None           => EmberClientBuilder.default[IO].build
      validator <- Resource.eval(keycloakValidator(c, keycloakRealm))
    yield routes(buttonHtml, secretHtml, validator, serverUrl, keycloakRealm)

  def load: IO[McpServer[IO]] =
    loadPages.map((btn, sec) => createServer(btn, sec, None))

object Main extends cats.effect.IOApp:
  def run(args: List[String]): IO[cats.effect.ExitCode] =
    val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(3097)
    val realm = sys.env.getOrElse("KEYCLOAK_REALM", LazyAuthServer.DefaultKeycloakRealm)
    val serverUrl = sys.env.getOrElse("PUBLIC_URL", s"http://localhost:$port/mcp")
    LazyAuthServer.loadRoutes(serverUrl, realm).use { routes =>
      ExampleServer.httpRoutes(routes, port).use { _ =>
        IO.println(s"lazy-auth-server: listening on $serverUrl (Keycloak: $realm)") >> IO.never
      }
    }.as(cats.effect.ExitCode.Success)
