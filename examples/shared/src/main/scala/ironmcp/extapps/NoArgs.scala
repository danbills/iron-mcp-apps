package ironmcp.extapps

import io.circe.Decoder
import ironmcp.schema.JsonSchemaOf

/** The input of a tool that takes none: upstream's `z.object({})`. */
final case class NoArgs() derives Decoder, JsonSchemaOf
