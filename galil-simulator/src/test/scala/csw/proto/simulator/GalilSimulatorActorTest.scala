package csw.proto.galil.simulator

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.util.ByteString
import csw.proto.galil.io.DataRecord
import csw.proto.galil.simulator.GalilSimulatorActor.*
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite

import scala.concurrent.duration.*

/**
 * Unit tests for the enhanced GalilSimulatorActor.
 *
 * Tests the simulator's command parsing, thread management, motion emulation,
 * QR DataRecord generation, and embedded variable storage — all of which
 * support the HcdIntegrationTest suite running without hardware.
 *
 * Run: sbt "galil-simulator/testOnly *GalilSimulatorActorTest"
 */
class GalilSimulatorActorTest extends AnyFunSuite with BeforeAndAfterAll {

  private val testKit = ActorTestKit()

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // Scala 3: Short and Byte don't have toHexString — widen to Int first
  private def hex(v: Short): String = (v.toInt & 0xFFFF).toHexString
  private def hex(v: Byte): String = (v.toInt & 0xFF).toHexString
  private def hex(v: Int): String = v.toHexString

  /** Spawn a fresh simulator actor for each test */
  private def spawnSimulator() =
    testKit.spawn(Behaviors.withTimers[GalilSimulatorCommand](GalilSimulatorActor.simulate(_)))

  /** Send a command to the simulator and return the raw response ByteString */
  private def send(sim: org.apache.pekko.actor.typed.ActorRef[GalilSimulatorCommand], cmd: String): ByteString = {
    val probe = testKit.createTestProbe[ByteString]()
    sim ! Command(cmd, probe.ref)
    probe.receiveMessage(3.seconds)
  }

  /** Send a command and return the response as a trimmed text string */
  private def sendText(sim: org.apache.pekko.actor.typed.ActorRef[GalilSimulatorCommand], cmd: String): String =
    send(sim, cmd).utf8String.stripSuffix(":").stripSuffix("\r\n").trim

  /** Send a QR command and parse the response into a DataRecord */
  private def sendQR(sim: org.apache.pekko.actor.typed.ActorRef[GalilSimulatorCommand]): DataRecord = {
    val bytes = send(sim, "QR")
    // QR response is binary DataRecord followed by ":"
    DataRecord(bytes.dropRight(1))
  }

  // ==========================================================================
  // 1. Identity and basic command parsing
  // ==========================================================================

  test("ID should return DMC50040 firmware identification") {
    val sim = spawnSimulator()
    val response = sendText(sim, "ID")
    assert(response.contains("DMC50040"), s"ID response should contain DMC50040: $response")
    assert(response.contains("Rev 1.2sim"), s"ID response should identify as simulator: $response")
  }

  test("empty command should return prompt only") {
    val sim = spawnSimulator()
    val response = send(sim, "").utf8String
    assert(response == ":", s"Empty command should return prompt ':' only, got: '$response'")
  }

  test("TC0 should return error code 0") {
    val sim = spawnSimulator()
    val response = sendText(sim, "TC0")
    assert(response == "0", s"TC0 should return 0, got: '$response'")
  }

  test("unhandled commands should return acknowledgment") {
    val sim = spawnSimulator()
    val response = send(sim, "BV").utf8String
    assert(response == ":", s"Unhandled command should return ':' prompt, got: '$response'")
  }

  // ==========================================================================
  // 2. Embedded variable storage
  // ==========================================================================

  test("variable assignment should store and retrieve values") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=500")
    val response = sendText(sim, "MG dmd[0]")
    assert(response == "500.0000", s"MG dmd[0] should return 500.0000, got: '$response'")
  }

  test("multiple variable assignments should be independent") {
    val sim = spawnSimulator()
    send(sim, "speed[0]=10000")
    send(sim, "speed[1]=5000")
    send(sim, "dmd[0]=300")
    send(sim, "dmd[1]=600")

    assert(sendText(sim, "MG speed[0]") == "10000.0000")
    assert(sendText(sim, "MG speed[1]") == "5000.0000")
    assert(sendText(sim, "MG dmd[0]") == "300.0000")
    assert(sendText(sim, "MG dmd[1]") == "600.0000")
  }

  test("unset variable should return 0") {
    val sim = spawnSimulator()
    val response = sendText(sim, "MG somevariable[7]")
    assert(response == "0.0000", s"Unset variable should return 0.0000, got: '$response'")
  }

  // ==========================================================================
  // 3. MG queries for system variables
  // ==========================================================================

  test("MG _NO should return thread bitmask as float") {
    val sim = spawnSimulator()
    val response = sendText(sim, "MG _NO")
    assert(response == "0.0000", s"Initial _NO should be 0 (no threads active), got: '$response'")
  }

  test("MG _TDA should return axis A position") {
    val sim = spawnSimulator()
    send(sim, "DPA=1000")
    val response = sendText(sim, "MG _TDA")
    assert(response == "1000.0000", s"_TDA should return axis A position, got: '$response'")
  }

  test("MG TIME should return a numeric value") {
    val sim = spawnSimulator()
    val response = sendText(sim, "MG TIME")
    assert(response.toDouble >= 0, s"TIME should be a non-negative number, got: '$response'")
  }

  test("MG @AN[0] should return simulated analog value") {
    val sim = spawnSimulator()
    val response = sendText(sim, "MG @AN[0]")
    assert(response == "2.5000", s"@AN[0] should return 2.5000, got: '$response'")
  }

  // ==========================================================================
  // 4. Motor on/off and axis commands
  // ==========================================================================

  test("SH should enable motor, MO should disable") {
    val sim = spawnSimulator()

    // Motor starts off — QR should show motorOff bit
    val dr1 = sendQR(sim)
    val statusOff = dr1.axisStatuses(0).status
    assert((statusOff & 0x0001) != 0, s"Motor should start off (bit 0 set), status=0x${hex(statusOff)}")

    // SH enables motor
    send(sim, "SHA")
    val dr2 = sendQR(sim)
    val statusOn = dr2.axisStatuses(0).status
    assert((statusOn & 0x0001) == 0, s"After SH, motor should be on (bit 0 clear), status=0x${hex(statusOn)}")

    // MO disables motor
    send(sim, "MOA")
    val dr3 = sendQR(sim)
    val statusOffAgain = dr3.axisStatuses(0).status
    assert((statusOffAgain & 0x0001) != 0, s"After MO, motor should be off (bit 0 set), status=0x${hex(statusOffAgain)}")
  }

  test("SPA should set and query axis speed") {
    val sim = spawnSimulator()
    send(sim, "SPA=20000")
    val response = sendText(sim, "SPA?")
    assert(response == "20000.0000", s"SPA? should return 20000, got: '$response'")
  }

  test("ACA should set and query axis acceleration") {
    val sim = spawnSimulator()
    send(sim, "ACA=128000")
    val response = sendText(sim, "ACA?")
    assert(response == "128000.0000", s"ACA? should return 128000, got: '$response'")
  }

  test("DPA should set axis position") {
    val sim = spawnSimulator()
    send(sim, "DPA=5000")
    val response = sendText(sim, "RPA")
    assert(response == "5000", s"RP A should return 5000, got: '$response'")
  }

  // ==========================================================================
  // 5. Thread management — XQ sets bits, completion clears them
  // ==========================================================================

  test("XQ should set thread bit in _NO") {
    val sim = spawnSimulator()
    send(sim, "XQ #Init,0")
    // Thread 0 should be active immediately after XQ
    val noStr = sendText(sim, "MG _NO")
    val noVal = noStr.toDouble.toInt
    assert((noVal & 1) != 0, s"Thread 0 should be active after XQ #Init, _NO=$noVal")
  }

  test("thread should clear after program completion") {
    val sim = spawnSimulator()
    send(sim, "XQ #Init,0")

    // #Init completes in 50ms — wait for it
    Thread.sleep(200)

    val noStr = sendText(sim, "MG _NO")
    val noVal = noStr.toDouble.toInt
    assert(noVal == 0, s"Thread 0 should be cleared after #Init completes, _NO=$noVal")
  }

  test("multiple threads can be active simultaneously") {
    val sim = spawnSimulator()
    // Set up demands so moves are long enough to not complete instantly
    send(sim, "dmd[0]=50000")
    send(sim, "dmd[1]=50000")
    send(sim, "speed[0]=10000")
    send(sim, "speed[1]=10000")

    send(sim, "XQ #MoveA,1")
    send(sim, "XQ #MoveB,2")

    val noStr = sendText(sim, "MG _NO")
    val noVal = noStr.toDouble.toInt
    assert((noVal & 0x02) != 0, s"Thread 1 should be active, _NO=$noVal (0x${hex(noVal)})")
    assert((noVal & 0x04) != 0, s"Thread 2 should be active, _NO=$noVal (0x${hex(noVal)})")
  }

  test("HX should clear thread bit immediately") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=50000")
    send(sim, "speed[0]=1000")
    send(sim, "XQ #MoveA,1")

    // Verify thread is active
    val before = sendText(sim, "MG _NO").toDouble.toInt
    assert((before & 0x02) != 0, s"Thread 1 should be active before HX, _NO=$before")

    // Halt thread 1
    send(sim, "HX1")
    val after = sendText(sim, "MG _NO").toDouble.toInt
    assert((after & 0x02) == 0, s"Thread 1 should be cleared after HX, _NO=$after")
  }

  // ==========================================================================
  // 6. #Init — embedded variable initialization
  // ==========================================================================

  test("XQ #Init should initialize default embedded variables") {
    val sim = spawnSimulator()
    send(sim, "XQ #Init,0")
    Thread.sleep(200)

    // Verify defaults for both axes
    assert(sendText(sim, "MG speed[0]") == "10000.0000")
    assert(sendText(sim, "MG accel[0]") == "256000.0000")
    assert(sendText(sim, "MG decel[0]") == "256000.0000")
    assert(sendText(sim, "MG hspd[0]") == "5000.0000")
    assert(sendText(sim, "MG speed[1]") == "10000.0000")
    assert(sendText(sim, "MG dmd[0]") == "0.0000")
    assert(sendText(sim, "MG dmd[1]") == "0.0000")
  }

  // ==========================================================================
  // 7. #Setup — axis configuration
  // ==========================================================================

  test("XQ #SetupA should configure axis as stepper with motor off") {
    val sim = spawnSimulator()
    send(sim, "XQ #SetupA,1")
    Thread.sleep(200)

    val dr = sendQR(sim)
    val switches = dr.axisStatuses(0).switches
    assert((switches & 0x01) != 0, s"After #SetupA, stepper bit should be set, switches=0x${hex(switches)}")
    val status = dr.axisStatuses(0).status
    assert((status & 0x0001) != 0, s"After #SetupA, motor should be off, status=0x${hex(status)}")
  }

  // ==========================================================================
  // 8. #Home — position reset
  // ==========================================================================

  test("XQ #HomeA should set position to 0 and enable motor") {
    val sim = spawnSimulator()
    send(sim, "DPA=5000")
    send(sim, "XQ #HomeA,1")
    Thread.sleep(200)

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 0, s"After #HomeA, position should be 0, got: ${dr.axisStatuses(0).auxiliaryPosition}")
    val status = dr.axisStatuses(0).status
    assert((status & 0x0001) == 0, s"After #HomeA, motor should be on, status=0x${hex(status)}")
  }

  // ==========================================================================
  // 9. #Move — position motion emulation
  // ==========================================================================

  test("XQ #MoveA should set axis to moving state") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=5000")
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")

    // Check immediately — axis should be moving
    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) != 0, s"After XQ #MoveA, moving bit should be set, status=0x${hex(status)}")
  }

  test("move should reach target and clear thread") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=500")
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")

    // 500 counts at 10000 counts/sec = 50ms, plus thread completion delay
    Thread.sleep(500)

    // Verify position reached
    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 500, s"Position should be 500, got: ${dr.axisStatuses(0).auxiliaryPosition}")

    // Verify not moving
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) == 0, s"After completion, moving bit should be clear, status=0x${hex(status)}")

    // Verify thread cleared
    val noVal = sendText(sim, "MG _NO").toDouble.toInt
    assert(noVal == 0, s"Thread should be cleared after move completes, _NO=$noVal")

    // Verify stop code = 1 (normal)
    assert(dr.axisStatuses(0).stopCode == 1, s"Stop code should be 1 (normal), got: ${dr.axisStatuses(0).stopCode}")
  }

  test("move should support negative direction") {
    val sim = spawnSimulator()
    send(sim, "DPA=1000")
    send(sim, "dmd[0]=0")
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")

    // Wait for completion
    Thread.sleep(500)

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 0, s"Position should be 0, got: ${dr.axisStatuses(0).auxiliaryPosition}")
  }

  test("concurrent moves on different axes should complete independently") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=400")
    send(sim, "dmd[1]=300")
    send(sim, "speed[0]=10000")
    send(sim, "speed[1]=10000")
    send(sim, "XQ #MoveA,1")
    send(sim, "XQ #MoveB,2")

    Thread.sleep(500)

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 400, s"Axis A should be at 400, got: ${dr.axisStatuses(0).auxiliaryPosition}")
    assert(dr.axisStatuses(1).auxiliaryPosition == 300, s"Axis B should be at 300, got: ${dr.axisStatuses(1).auxiliaryPosition}")

    val noVal = sendText(sim, "MG _NO").toDouble.toInt
    assert(noVal == 0, s"All threads should be cleared, _NO=$noVal")
  }

  // ==========================================================================
  // 10. #Stop — halt motion and clear leaked threads
  // ==========================================================================

  test("XQ #StopA should halt motion and clear move thread") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=50000")
    send(sim, "speed[0]=1000")  // slow speed so move takes a while
    send(sim, "XQ #MoveA,1")
    Thread.sleep(50)

    // Verify moving
    val dr1 = sendQR(sim)
    val status1 = dr1.axisStatuses(0).status
    assert((status1 & (1 << 15)) != 0, s"Axis A should be moving before stop, status=0x${hex(status1)}")

    // Stop on a different thread (as the HCD does)
    send(sim, "XQ #StopA,3")
    Thread.sleep(200)

    // Verify stopped
    val dr2 = sendQR(sim)
    val status2 = dr2.axisStatuses(0).status
    assert((status2 & (1 << 15)) == 0, s"Axis A should not be moving after stop, status=0x${hex(status2)}")

    // Verify stop code = 4 (ST command)
    assert(dr2.axisStatuses(0).stopCode == 4, s"Stop code should be 4 (ST), got: ${dr2.axisStatuses(0).stopCode}")

    // Critical: both the move thread (1) AND the stop thread (3) should be cleared
    val noVal = sendText(sim, "MG _NO").toDouble.toInt
    assert((noVal & 0x02) == 0, s"Move thread 1 should be cleared by #StopA, _NO=0x${hex(noVal)}")
    assert((noVal & 0x08) == 0, s"Stop thread 3 should complete, _NO=0x${hex(noVal)}")
  }

  test("ST command should stop all moving axes") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=50000")
    send(sim, "dmd[1]=50000")
    send(sim, "speed[0]=1000")
    send(sim, "speed[1]=1000")
    send(sim, "XQ #MoveA,1")
    send(sim, "XQ #MoveB,2")
    Thread.sleep(50)

    // ST with no axis spec stops all
    send(sim, "ST")
    val dr = sendQR(sim)
    val statusA = dr.axisStatuses(0).status
    val statusB = dr.axisStatuses(1).status
    assert((statusA & (1 << 15)) == 0, s"Axis A should be stopped after ST")
    assert((statusB & (1 << 15)) == 0, s"Axis B should be stopped after ST")

    // Move threads should be cleared
    val noVal = sendText(sim, "MG _NO").toDouble.toInt
    assert((noVal & 0x02) == 0, s"Thread 1 should be cleared after ST, _NO=0x${hex(noVal)}")
    assert((noVal & 0x04) == 0, s"Thread 2 should be cleared after ST, _NO=0x${hex(noVal)}")
  }

  test("MO should stop motion and clear move thread") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=50000")
    send(sim, "speed[0]=1000")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(50)

    send(sim, "MOA")
    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) == 0, s"After MO, axis should not be moving")
    assert((status & 0x0001) != 0, s"After MO, motor should be off")

    val noVal = sendText(sim, "MG _NO").toDouble.toInt
    assert((noVal & 0x02) == 0, s"Move thread should be cleared by MO, _NO=0x${hex(noVal)}")
  }

  // ==========================================================================
  // 11. #Track — jog mode motion
  // ==========================================================================

  test("XQ #TrackA should start jogging and release thread") {
    val sim = spawnSimulator()
    send(sim, "Atarget[0]=100")
    send(sim, "Atarget[1]=20")  // velocity = 20 counts/sec
    send(sim, "XQ #TrackA,1")
    Thread.sleep(50)

    // Axis should be moving and jogging
    val dr1 = sendQR(sim)
    val status = dr1.axisStatuses(0).status
    assert((status & (1 << 15)) != 0, s"Axis should be moving during tracking")

    // Thread releases quickly (~100ms) while motor keeps jogging
    Thread.sleep(200)
    val noVal = sendText(sim, "MG _NO").toDouble.toInt
    assert((noVal & 0x02) == 0, s"Track thread should be released after program ENDs, _NO=0x${hex(noVal)}")

    // But axis should still be moving (jogging)
    val dr2 = sendQR(sim)
    val status2 = dr2.axisStatuses(0).status
    assert((status2 & (1 << 15)) != 0, s"Axis should still be moving (jogging) after thread release")
  }

  test("tracking should change position over time") {
    val sim = spawnSimulator()
    send(sim, "DPA=0")
    send(sim, "Atarget[0]=0")
    send(sim, "Atarget[1]=100")  // 100 counts/sec
    send(sim, "XQ #TrackA,1")

    Thread.sleep(500)

    val dr = sendQR(sim)
    val pos = dr.axisStatuses(0).auxiliaryPosition
    // At 100 counts/sec for ~500ms, expect ~50 counts (with timer overhead, be generous)
    assert(pos > 10 && pos < 200, s"Position should have changed from jog, got: $pos")
  }

  // ==========================================================================
  // 12. QR DataRecord generation
  // ==========================================================================

  test("QR should produce valid DataRecord with correct header") {
    val sim = spawnSimulator()
    val dr = sendQR(sim)

    // Should have header blocks for S, T, I, A, B, C, D
    assert(dr.header != null, "DataRecord should have a valid header")
    assert(dr.axisStatuses.length == 4, s"Should have 4 axis statuses, got: ${dr.axisStatuses.length}")
  }

  test("QR should reflect motor position correctly") {
    val sim = spawnSimulator()
    send(sim, "DPA=1234")
    send(sim, "DPB=5678")

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 1234, s"Axis A auxiliaryPosition should be 1234, got: ${dr.axisStatuses(0).auxiliaryPosition}")
    assert(dr.axisStatuses(1).auxiliaryPosition == 5678, s"Axis B auxiliaryPosition should be 5678, got: ${dr.axisStatuses(1).auxiliaryPosition}")
  }

  test("QR should encode velocity as 64x value") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=50000")
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(50)

    val dr = sendQR(sim)
    val rawVelocity = dr.axisStatuses(0).velocity
    // velocity should be non-zero and be 64x the actual velocity
    assert(rawVelocity != 0, s"Velocity should be non-zero during motion, got: $rawVelocity")
    val actualVelocity = rawVelocity / 64.0
    // Should be near the configured speed (within 50% due to tick timing)
    assert(actualVelocity > 1000, s"Decoded velocity should be substantial, got: $actualVelocity")
  }

  test("QR threadStatus should match _NO bitmask") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=50000")
    send(sim, "speed[0]=1000")
    send(sim, "XQ #MoveA,1")

    val dr = sendQR(sim)
    val noVal = sendText(sim, "MG _NO").toDouble.toInt
    val qrThread = dr.generalState.threadStatus & 0xFF

    assert(qrThread == noVal, s"QR threadStatus ($qrThread) should match MG _NO ($noVal)")
  }

  test("QR stepper mode bit should be set after Setup") {
    val sim = spawnSimulator()
    send(sim, "XQ #SetupA,1")
    Thread.sleep(200)

    val dr = sendQR(sim)
    val switches = dr.axisStatuses(0).switches
    assert((switches & 0x01) != 0, s"Stepper bit should be set in switches byte, switches=0x${hex(switches)}")
  }

  test("QR status word bit 14 should indicate PA mode during move") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=50000")
    send(sim, "speed[0]=1000")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(20)

    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 14)) != 0, s"PA mode bit 14 should be set during position move, status=0x${hex(status)}")
  }

  test("QR status word bit 14 should be clear during jog mode") {
    val sim = spawnSimulator()
    send(sim, "Atarget[0]=0")
    send(sim, "Atarget[1]=100")
    send(sim, "XQ #TrackA,1")
    Thread.sleep(50)

    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) != 0, s"Moving bit should be set during tracking")
    assert((status & (1 << 14)) == 0, s"PA mode bit should NOT be set during jog/tracking, status=0x${hex(status)}")
  }

  test("QR negative direction bit should be set for negative velocity") {
    val sim = spawnSimulator()
    send(sim, "DPA=5000")
    send(sim, "dmd[0]=0")     // demand < position = negative direction
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(50)

    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 7)) != 0, s"Negative direction bit should be set for reverse move, status=0x${hex(status)}")
  }

  // ==========================================================================
  // 13. Program upload/download
  // ==========================================================================

  test("UL should return stored program text") {
    val sim = spawnSimulator()
    val response = send(sim, "UL").utf8String
    // UL response format: programText + "\\\r\n:"
    assert(response.endsWith("\\") || response.contains("\\"), s"UL response should end with backslash continuation")
  }

  test("DL should acknowledge program download") {
    val sim = spawnSimulator()
    val response = send(sim, "DL").utf8String
    assert(response == ":", s"DL should return prompt, got: '$response'")
  }

  // ==========================================================================
  // 14. Edge cases and boundary conditions
  // ==========================================================================

  test("move to current position should complete immediately") {
    val sim = spawnSimulator()
    send(sim, "DPA=500")
    send(sim, "dmd[0]=500")    // demand == position
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")

    // With SnapThreshold=0.5, should snap immediately in first tick
    Thread.sleep(200)

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 500, s"Position should remain at 500")
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) == 0, s"Should not be moving after zero-distance move")
  }

  test("sequential moves should work correctly") {
    val sim = spawnSimulator()
    send(sim, "speed[0]=10000")

    // First move
    send(sim, "dmd[0]=300")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(300)

    val dr1 = sendQR(sim)
    assert(dr1.axisStatuses(0).auxiliaryPosition == 300, s"First move: expected 300, got: ${dr1.axisStatuses(0).auxiliaryPosition}")

    // Second move
    send(sim, "dmd[0]=600")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(300)

    val dr2 = sendQR(sim)
    assert(dr2.axisStatuses(0).auxiliaryPosition == 600, s"Second move: expected 600, got: ${dr2.axisStatuses(0).auxiliaryPosition}")
  }

  test("sample number should increment on each QR") {
    val sim = spawnSimulator()
    val dr1 = sendQR(sim)
    val dr2 = sendQR(sim)
    val dr3 = sendQR(sim)

    assert(dr2.generalState.sampleNumber == (dr1.generalState.sampleNumber + 1).toShort)
    assert(dr3.generalState.sampleNumber == (dr2.generalState.sampleNumber + 1).toShort)
  }

  // ==========================================================================
  // 15. JG and BG direct commands
  // ==========================================================================

  test("JGA should set and query jog speed") {
    val sim = spawnSimulator()
    send(sim, "JGA=500")
    val response = sendText(sim, "JGA=?")
    assert(response == "500.0000", s"JGA? should return 500, got: '$response'")
  }

  test("BG with JG speed should start jog motion") {
    val sim = spawnSimulator()
    send(sim, "SHA")
    send(sim, "JGA=100")
    send(sim, "BGA")
    Thread.sleep(100)

    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) != 0, s"Axis should be moving after BG with JG speed")
  }

  // ==========================================================================
  // 16. Direct query commands (TP, TD, TV, SC, TS) — single and all-axis
  // ==========================================================================

  test("TPA should return single-axis encoder position") {
    val sim = spawnSimulator()
    send(sim, "DPA=4321")
    val response = sendText(sim, "TPA")
    assert(response == "4321", s"TPA should return 4321, got: '$response'")
  }

  test("TDA should return single-axis step position") {
    val sim = spawnSimulator()
    send(sim, "DPA=7890")
    val response = sendText(sim, "TDA")
    assert(response == "7890", s"TDA should return 7890, got: '$response'")
  }

  test("TP with no axis should return all axes comma-separated") {
    val sim = spawnSimulator()
    send(sim, "DPA=100")
    send(sim, "DPB=200")
    val response = sendText(sim, "TP")
    // Should be "100, 200, 0, 0" for 4-axis controller
    assert(response == "100, 200, 0, 0", s"TP should return all axes, got: '$response'")
  }

  test("TD with no axis should return all axes comma-separated") {
    val sim = spawnSimulator()
    send(sim, "DPA=300")
    send(sim, "DPB=400")
    val response = sendText(sim, "TD")
    assert(response == "300, 400, 0, 0", s"TD should return all axes, got: '$response'")
  }

  test("TVA should return velocity for single axis") {
    val sim = spawnSimulator()
    // Stationary — velocity should be 0.0000
    val response1 = sendText(sim, "TVA")
    assert(response1 == "0.0000", s"TVA should return 0.0000 when stationary, got: '$response1'")

    // Moving — velocity should be non-zero
    send(sim, "dmd[0]=50000")
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(50)

    val response2 = sendText(sim, "TVA")
    assert(response2.toDouble != 0, s"TVA should return non-zero during motion, got: '$response2'")
  }

  test("TV with no axis should return all axes") {
    val sim = spawnSimulator()
    val response = sendText(sim, "TV")
    // All stationary — should be "0.0000, 0.0000, 0.0000, 0.0000"
    assert(response.contains(", "), s"TV should return comma-separated values, got: '$response'")
    val values = response.split(",").map(_.trim.toDouble)
    assert(values.length == 4, s"TV should return 4 values, got: ${values.length}")
  }

  test("SCA should return stop code for single axis") {
    val sim = spawnSimulator()
    val response1 = sendText(sim, "SCA")
    assert(response1 == "0", s"SCA should return 0 initially, got: '$response1'")

    // After a completed move, stop code = 1
    send(sim, "dmd[0]=100")
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(300)

    val response2 = sendText(sim, "SCA")
    assert(response2 == "1", s"SCA should return 1 after normal stop, got: '$response2'")
  }

  test("SC with no axis should return all axes") {
    val sim = spawnSimulator()
    val response = sendText(sim, "SC")
    assert(response == "0, 0, 0, 0", s"SC should return all axes, got: '$response'")
  }

  test("TSA should return switches byte with stepper mode bit") {
    val sim = spawnSimulator()
    // Default motorType = 2.0 (stepper) — stepper bit should be set
    val response = sendText(sim, "TSA")
    val sw = response.toInt
    assert((sw & 0x01) != 0, s"TSA should have stepper bit set, got: $sw")
  }

  test("TS with no axis should return all axes") {
    val sim = spawnSimulator()
    val response = sendText(sim, "TS")
    // All default stepper, not homed → 1, 1, 1, 1
    assert(response == "1, 1, 1, 1", s"TS should return switches for all axes, got: '$response'")
  }

  test("TS should show homed bit after #Home") {
    val sim = spawnSimulator()
    send(sim, "XQ #HomeA,1")
    Thread.sleep(200)

    // Axis A: stepper (bit 0) + homed (bit 1) = 3
    val tsa = sendText(sim, "TSA")
    assert(tsa.toInt == 3, s"TSA after home should be 3 (stepper+homed), got: '$tsa'")

    // Axis B: stepper only = 1
    val tsb = sendText(sim, "TSB")
    assert(tsb.toInt == 1, s"TSB should still be 1 (not homed), got: '$tsb'")
  }

  test("TP and TD should reflect position changes from motion") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=250")
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(300)

    val tp = sendText(sim, "TPA").toInt
    val td = sendText(sim, "TDA").toInt
    assert(tp == 250, s"TPA should be 250 after move, got: $tp")
    assert(td == 250, s"TDA should be 250 after move, got: $td")
  }

  test("TP and TD work on axis B") {
    val sim = spawnSimulator()
    send(sim, "DPB=999")
    val tp = sendText(sim, "TPB")
    val td = sendText(sim, "TDB")
    assert(tp == "999", s"TPB should return 999, got: '$tp'")
    assert(td == "999", s"TDB should return 999, got: '$td'")
  }

  // ==========================================================================
  // 17. LV — List Variables, LA — List Arrays
  // ==========================================================================

  test("LV should list scalar variables only, not arrays") {
    val sim = spawnSimulator()
    // Set a scalar variable and an array variable
    send(sim, "tcon=1000")
    send(sim, "dmd[0]=500")

    val response = sendText(sim, "LV")
    // Should show scalar "tcon" but NOT array "dmd[0]"
    assert(response.contains("tcon="), s"LV should list scalar tcon, got: '$response'")
    assert(!response.contains("dmd[0]"), s"LV should NOT list array variable dmd[0], got: '$response'")
  }

  test("LV should show updated scalar values") {
    val sim = spawnSimulator()
    send(sim, "version=20260302")
    val response = sendText(sim, "LV")
    assert(response.contains("version= 20260302.0000"), s"LV should show assigned value, got: '$response'")
  }

  test("LV should return empty when only array variables are set") {
    val sim = spawnSimulator()
    send(sim, "XQ #Init,0")
    Thread.sleep(200)
    // #Init only sets array variables — LV should be empty
    val response = send(sim, "LV").utf8String
    assert(response == ":", s"LV with only arrays should return prompt only, got: '$response'")
  }

  test("LA should list array names with dimensions") {
    val sim = spawnSimulator()
    send(sim, "XQ #Init,0")
    Thread.sleep(200)

    val response = sendText(sim, "LA")
    // #Init creates speed[0], speed[1], accel[0], accel[1], etc.
    // so "speed" array should appear with dimension 2
    assert(response.contains("speed[2]"), s"LA should show speed[2], got: '$response'")
    assert(response.contains("accel[2]"), s"LA should show accel[2], got: '$response'")
    assert(response.contains("dmd[2]"), s"LA should show dmd[2], got: '$response'")
  }

  test("LA should return empty when no variables set") {
    val sim = spawnSimulator()
    val response = send(sim, "LA").utf8String
    assert(response == ":", s"LA with no variables should return prompt only, got: '$response'")
  }
}