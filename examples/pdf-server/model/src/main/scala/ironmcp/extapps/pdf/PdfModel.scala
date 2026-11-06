package ironmcp.extapps.pdf

import io.circe.{Codec, Decoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.schema.JsonSchemaOf

final case class DisplayPdfArgs(
    source: Option[String :| (Not[Empty] DescribedAs "Path to a local PDF file or HTTPS URL")] = None,
    page: Option[Int :| (Positive DescribedAs "Page number to display (1-based)")] = None,
    search: Option[String :| (Not[Empty] DescribedAs "Search term to highlight")] = None,
    rotation: Option[Int :| (GreaterEqual[0] & LessEqual[360] DescribedAs "Page rotation in degrees (0, 90, 180, 270)")] = None
) derives Decoder, JsonSchemaOf

final case class ReadPdfBytesArgs(
    source: String,
    offset: Long,
    length: Int
) derives Codec.AsObject, JsonSchemaOf

final case class ReadPdfBytesResult(
    data: String,
    totalSize: Long,
    bytesRead: Int
) derives Codec.AsObject, JsonSchemaOf

final case class PdfCommand(
    action: String,
    page: Option[Int] = None,
    zoom: Option[Double] = None,
    rotation: Option[Int] = None,
    search: Option[String] = None,
    maxDimension: Option[Int] = None
) derives Codec.AsObject, JsonSchemaOf

final case class InteractArgs(
    viewUUID: String :| (Not[Empty] DescribedAs "The viewUUID returned by display_pdf"),
    commands: Option[List[PdfCommand]] = None,
    action: Option[String] = None,
    page: Option[Int] = None,
    zoom: Option[Double] = None,
    rotation: Option[Int] = None,
    search: Option[String] = None
) derives Decoder, JsonSchemaOf

final case class PdfViewerState(
    currentPage: Int,
    totalPages: Int,
    zoom: Double,
    rotation: Int,
    search: Option[String] = None
) derives Codec.AsObject, JsonSchemaOf

final case class SubmitViewerStateArgs(
    viewUUID: String,
    state: PdfViewerState
) derives Codec.AsObject, JsonSchemaOf

final case class PollCommandsArgs(
    viewUUID: String
) derives Codec.AsObject, JsonSchemaOf

final case class PollCommandsResult(
    commands: List[PdfCommand]
) derives Codec.AsObject, JsonSchemaOf
