package ironmcp.extapps

import cats.MonadThrow
import cats.syntax.all.*
import io.github.iltotore.iron.autoRefine
import ironmcp.protocol.*
import ironmcp.server.{McpException, ResourceProvider}

/** One resource whose contents are made fresh on each read, as upstream's
  * `server.registerResource(uri, ...)`. Not cached: the contents may change.
  */
final class SingleResource[F[_]](resource: Resource, contents: F[ResourceContents])(using F: MonadThrow[F])
    extends ResourceProvider[F]:

  def list(params: ListResourcesParams): F[ListResourcesResult] =
    F.pure(ListResourcesResult(List(resource), 0L, CacheScope.`private`))

  def read(params: ReadResourceParams): F[ReadResourceResult | InputRequiredResult] =
    if params.uri == (resource.uri: String) then contents.map(c => ReadResourceResult(List(c), 0L, CacheScope.`private`))
    else F.raiseError(McpException.resourceNotFound(params.uri))
