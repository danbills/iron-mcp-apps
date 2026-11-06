package ironmcp.extapps.budget

import io.circe.Codec
import ironmcp.schema.JsonSchemaOf

// What get-budget-data sends and the View renders: one definition for the
// server's output schema and encoder, and the View's decoder (JVM and JS).

final case class BudgetCategory(id: String, name: String, color: String, defaultPercent: Double) derives Codec.AsObject, JsonSchemaOf

final case class HistoricalMonth(month: String, allocations: Map[String, Double]) derives Codec.AsObject, JsonSchemaOf

final case class BenchmarkPercentiles(p25: Double, p50: Double, p75: Double) derives Codec.AsObject, JsonSchemaOf

final case class StageBenchmark(stage: String, categoryBenchmarks: Map[String, BenchmarkPercentiles]) derives Codec.AsObject, JsonSchemaOf

final case class BudgetConfig(
    categories: List[BudgetCategory],
    presetBudgets: List[Double],
    defaultBudget: Double,
    currency: String,
    currencySymbol: String
) derives Codec.AsObject, JsonSchemaOf

final case class BudgetAnalytics(
    history: List[HistoricalMonth],
    benchmarks: List[StageBenchmark],
    stages: List[String],
    defaultStage: String
) derives Codec.AsObject, JsonSchemaOf

final case class BudgetData(config: BudgetConfig, analytics: BudgetAnalytics) derives Codec.AsObject, JsonSchemaOf
