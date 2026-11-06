package ironmcp.extapps.monitor

import io.circe.Codec
import ironmcp.schema.JsonSchemaOf

final case class CpuInfo(model: String, count: Int) derives Codec.AsObject, JsonSchemaOf
final case class MemoryInfo(totalBytes: Long) derives Codec.AsObject, JsonSchemaOf

/** What `get-system-info` returns once: the machine. */
final case class SystemInfo(hostname: String, platform: String, arch: String, cpu: CpuInfo, memory: MemoryInfo) derives Codec.AsObject, JsonSchemaOf

/** One core's cumulative CPU time, in milliseconds: the View diffs two samples. */
final case class CpuCore(idle: Long, total: Long) derives Codec.AsObject, JsonSchemaOf
final case class CpuCores(cores: List[CpuCore]) derives Codec.AsObject, JsonSchemaOf
final case class MemoryUse(usedBytes: Long, usedPercent: Long, freeBytes: Long) derives Codec.AsObject, JsonSchemaOf
final case class Uptime(seconds: Double) derives Codec.AsObject, JsonSchemaOf

/** What `poll-system-stats` returns every two seconds. */
final case class PollStats(cpu: CpuCores, memory: MemoryUse, uptime: Uptime, timestamp: String) derives Codec.AsObject, JsonSchemaOf
