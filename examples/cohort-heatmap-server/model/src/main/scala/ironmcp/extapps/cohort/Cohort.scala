package ironmcp.extapps.cohort

import io.circe.Codec
import ironmcp.schema.JsonSchemaOf

final case class CohortCell(cohortIndex: Int, periodIndex: Int, retention: Double, usersRetained: Int, usersOriginal: Int)
    derives Codec.AsObject, JsonSchemaOf

final case class CohortRow(cohortId: String, cohortLabel: String, originalUsers: Int, cells: List[CohortCell]) derives Codec.AsObject, JsonSchemaOf

final case class CohortData(
    cohorts: List[CohortRow],
    periods: List[String],
    periodLabels: List[String],
    metric: String,
    periodType: String,
    generatedAt: String
) derives Codec.AsObject, JsonSchemaOf
