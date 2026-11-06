package ironmcp.extapps.wiki

import io.circe.{Codec, Decoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.schema.JsonSchemaOf

final case class PageInfo(
    url: String,
    title: String
) derives Codec.AsObject, JsonSchemaOf

final case class GetFirstDegreeLinksArgs(
    url: Option[String :| (Not[Empty] DescribedAs "Wikipedia page URL")] = None
) derives Decoder, JsonSchemaOf

final case class GetFirstDegreeLinksResult(
    page: PageInfo,
    links: List[PageInfo],
    error: Option[String]
) derives Codec.AsObject, JsonSchemaOf
