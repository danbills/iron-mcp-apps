package ironmcp.extapps

import cats.effect.{ExitCode, IO, Resource}
import io.github.iltotore.iron.*
import ironmcp.http.McpHttp
import io.github.iltotore.iron.constraint.all.*
import ironmcp.server.ServerHandler
import ironmcp.transport.Stdio
import org.http4s.server.Server

/** How every example runs, as upstream's `main.ts`: `--stdio`, or stateless
  * Streamable HTTP at `/mcp` on `PORT` (default 3001), with CORS so a host page
  * in a browser (basic-host) can call it.
  */
object ExampleServer:

  def main(args: List[String], server: ServerHandler[IO], name: String): IO[ExitCode] =
    if args.contains("--stdio") then Stdio.serve(server).as(ExitCode.Success)
    else
      val port = sys.env.get("PORT").flatMap(_.toIntOption).getOrElse(3001)
      http(server, port).use(_ => IO.println(s"$name: MCP server listening on http://localhost:$port/mcp") >> IO.never)

  /** The server over Streamable HTTP on loopback. */
  def http(server: ServerHandler[IO], port: Int): Resource[IO, Server] =
    val routes = McpHttp.routes(server, McpHttp.Config(cors = true))
    McpHttp.serve(routes, port.refineEither[Interval.Closed[1, 65535]].fold(error => sys.error(s"not a port: $port ($error)"), identity))
