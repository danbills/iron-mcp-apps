package ironmcp.extapps.monitor

class MonitorSuite extends munit.FunSuite:

  test("the machine, in Node's words"):
    val info = Machine.info
    assert(info.cpu.count > 0)
    assert(info.memory.totalBytes > 0)
    assertEquals(info.platform, s"${Machine.platform} ${Machine.arch}")
    assert(Set("linux", "darwin", "win32").contains(Machine.platform), Machine.platform)
    assert(Set("x64", "arm64").contains(Machine.arch), Machine.arch)

  test("a core per logical processor; idle within total; times only grow"):
    val first  = Machine.cores
    Thread.sleep(200)
    val second = Machine.cores
    assertEquals(first.size, Machine.info.cpu.count)
    assert(first.forall(c => c.idle <= c.total))
    assert(first.zip(second).forall((a, b) => b.total >= a.total))

  test("memory: used plus free is the total; a percentage"):
    val m = Machine.memory
    assertEquals(m.usedBytes + m.freeBytes, Machine.info.memory.totalBytes)
    assert(m.usedPercent >= 0 && m.usedPercent <= 100)
