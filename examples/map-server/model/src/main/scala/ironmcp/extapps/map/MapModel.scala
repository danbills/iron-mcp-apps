package ironmcp.extapps.map

import io.circe.{Codec, Decoder}
import io.github.iltotore.iron.*
import io.github.iltotore.iron.circe.given
import io.github.iltotore.iron.constraint.all.*
import ironmcp.schema.{Default, JsonSchemaOf}

final case class BoundingBox(
    west: Double,
    south: Double,
    east: Double,
    north: Double
) derives Codec.AsObject, JsonSchemaOf

final case class ShowMapArgs(
    west: Option[Double :| (Default[-0.5] & GreaterEqual[-180.0] & LessEqual[180.0] DescribedAs "Western longitude (-180 to 180)")] = None,
    south: Option[Double :| (Default[51.3] & GreaterEqual[-90.0] & LessEqual[90.0] DescribedAs "Southern latitude (-90 to 90)")] = None,
    east: Option[Double :| (Default[0.3] & GreaterEqual[-180.0] & LessEqual[180.0] DescribedAs "Eastern longitude (-180 to 180)")] = None,
    north: Option[Double :| (Default[51.7] & GreaterEqual[-90.0] & LessEqual[90.0] DescribedAs "Northern latitude (-90 to 90)")] = None,
    label: Option[String :| (Not[Empty] DescribedAs "Optional label to display on the map")] = None
) derives Decoder, JsonSchemaOf

final case class GeocodeArgs(
    query: String :| (Not[Empty] DescribedAs "Place name or address to search for (e.g., 'Paris', 'Golden Gate Bridge', '1600 Pennsylvania Ave')")
) derives Decoder, JsonSchemaOf

final case class NavigateToArgs(
    west: Double,
    south: Double,
    east: Double,
    north: Double,
    duration: Option[Double] = None,
    label: Option[String] = None
) derives Codec.AsObject, JsonSchemaOf

final case class CurrentViewCamera(
    latitude: Double,
    longitude: Double,
    height: Double
) derives Codec.AsObject

final case class CurrentViewResult(
    camera: CurrentViewCamera,
    bbox: Option[BoundingBox]
) derives Codec.AsObject

final case class GetScreenshotArgs(
    maxDimension: Option[Int] = None
) derives Codec.AsObject, JsonSchemaOf
