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

  test("ID should return DMC50080 firmware identification") {
    val sim = spawnSimulator()
    val response = sendText(sim, "ID")
    assert(response.contains("DMC50080"), s"ID response should contain DMC50080: $response")
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

  test("unhandled commands should return ? error response") {
    val sim = spawnSimulator()
    val response = send(sim, "BV").utf8String
    assert(response == "?", s"Unhandled command should return '?' error, got: '$response'")
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

  test("MG @AN[1] should return simulated analog value (2.5V baseline)") {
    val sim = spawnSimulator()
    val response = sendText(sim, "MG @AN[1]")
    assert(response == "2.5000", s"@AN[1] should return 2.5000, got: '$response'")
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

  test("XQ on a busy thread should be rejected with ? (S86 controller fidelity)") {
    val sim = spawnSimulator()
    // Long-running move occupies thread 1
    send(sim, "dmd[0]=50000")
    send(sim, "speed[0]=1000")
    send(sim, "XQ #MoveA,1")
    val before = sendText(sim, "MG _NO").toDouble.toInt
    assert((before & 0x02) != 0, s"Thread 1 should be active, _NO=$before")

    // Second XQ on the SAME thread must be rejected like the real controller
    val response = send(sim, "XQ #StopA,1").utf8String
    assert(response == "?", s"XQ on busy thread should return '?', got: '$response'")

    // The original program is undisturbed: still running, still on thread 1
    val after = sendText(sim, "MG _NO").toDouble.toInt
    assert((after & 0x02) != 0, s"Thread 1 should still be active after rejected XQ, _NO=$after")
    assert(sendText(sim, "MG _XQ1").toDouble.toInt >= 0, "_XQ1 should still show the thread executing")
  }

  test("HX then re-XQ on the same thread should succeed (S84 reuse path)") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=50000")
    send(sim, "speed[0]=1000")
    send(sim, "XQ #MoveA,1")
    send(sim, "HX1")
    // The reuse path: after HX the bit is clear, so the follow-on XQ must land
    val response = send(sim, "XQ #StopA,1").utf8String
    assert(response == ":", s"XQ after HX on the same thread should succeed, got: '$response'")
    val no = sendText(sim, "MG _NO").toDouble.toInt
    assert((no & 0x02) != 0, s"Thread 1 should be active again after reuse XQ, _NO=$no")
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
    Thread.sleep(600)  // > ProgramCompleteDelay (250ms) with margin

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

    // Thread releases after ProgramCompleteDelay (250ms) while motor keeps jogging
    Thread.sleep(400)
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

    // Should have header blocks for S, T, I, A-H
    assert(dr.header != null, "DataRecord should have a valid header")
    assert(dr.axisStatuses.length == 8, s"Should have 8 axis statuses, got: ${dr.axisStatuses.length}")
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

  test("DL alone should return no ack — only the terminator does") {
    // Real Galil DMC behaviour: DL puts the controller into receive mode and
    // is silent.  The ":" prompt only arrives after the "\" terminator, once
    // the program text has been accumulated.  Session 58 made the simulator
    // match this: DL → empty response, "\" → ":" ack.
    val sim = spawnSimulator()
    val response = send(sim, "DL").utf8String
    assert(response == "", s"DL alone should return no response, got: '$response'")
  }

  test("DL + program lines + \\ terminator should accept and store the program") {
    // Full DL handshake: DL → program lines → "\" terminator → ":".
    // Session 58: simulator now implements DL receive mode correctly so
    // the Reload severity faultReset can exercise it end-to-end.  We then
    // verify the program by reading it back via UL.
    val sim = spawnSimulator()
    assert(send(sim, "DL").utf8String == "", "DL should be silent")
    assert(send(sim, "MG \"hello\"").utf8String == "", "Program lines should be silent in DL mode")
    assert(send(sim, "EN").utf8String == "", "Program lines should be silent in DL mode")
    val ack = send(sim, "\\").utf8String
    assert(ack == ":", s"Terminator should return ':' ack, got: '$ack'")

    // Round-trip: UL should now return the stored program text.
    val ulResponse = send(sim, "UL").utf8String
    assert(ulResponse.contains("MG \"hello\""), s"UL should include uploaded line, got: '$ulResponse'")
    assert(ulResponse.contains("EN"),           s"UL should include uploaded EN, got: '$ulResponse'")
  }

  test("BP should ack with prompt") {
    // Session 58: Burn Program — no flash on the simulator, just an ack.
    val sim = spawnSimulator()
    val response = send(sim, "BP").utf8String
    assert(response == ":", s"BP should return prompt ack, got: '$response'")
  }

  test("RS should reset state and preserve the burnt program") {
    // Session 58: Reset Controller — clears axes, threads, errors, embedded
    // vars, but preserves programText (the burnt program lives in EEPROM on
    // real hardware).  Simulator does NOT drop the TCP connection on RS;
    // recovery code's MG 0 reconnect test will succeed without a fresh socket.
    val sim = spawnSimulator()

    // Set up some state to verify it gets cleared.
    send(sim, "DPA=1234")
    send(sim, "dmd[0]=999")  // custom embedded var

    // Upload + (sim) burn a program so we can verify it survives RS.
    send(sim, "DL")
    send(sim, "MG \"survives RS\"")
    send(sim, "EN")
    send(sim, "\\")
    send(sim, "BP")

    // Issue RS.
    val rsResponse = send(sim, "RS").utf8String
    assert(rsResponse == ":", s"RS should ack with prompt, got: '$rsResponse'")

    // After RS: position cleared (axis A back to 0), demand var reset to default.
    val pos = send(sim, "TPA").utf8String
    assert(pos.startsWith("0"), s"Position should be reset to 0 after RS, got: '$pos'")

    // Embedded program should still be there (programText preserved).
    val ulResponse = send(sim, "UL").utf8String
    assert(ulResponse.contains("survives RS"), s"Program should survive RS, got: '$ulResponse'")
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

    // First move. Sleep must comfortably exceed motion time + ProgramCompleteDelay
    // (250ms): re-XQ'ing thread 1 before its ThreadComplete fires would replace
    // the pending timer and drop the first completion (see scheduleThreadComplete).
    send(sim, "dmd[0]=300")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(600)

    val dr1 = sendQR(sim)
    assert(dr1.axisStatuses(0).auxiliaryPosition == 300, s"First move: expected 300, got: ${dr1.axisStatuses(0).auxiliaryPosition}")

    // Second move
    send(sim, "dmd[0]=600")
    send(sim, "XQ #MoveA,1")
    Thread.sleep(600)

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
    // 8-axis simulator: A=100, B=200, C-H=0
    assert(response == "100, 200, 0, 0, 0, 0, 0, 0", s"TP should return all axes, got: '$response'")
  }

  test("TD with no axis should return all axes comma-separated") {
    val sim = spawnSimulator()
    send(sim, "DPA=300")
    send(sim, "DPB=400")
    val response = sendText(sim, "TD")
    assert(response == "300, 400, 0, 0, 0, 0, 0, 0", s"TD should return all axes, got: '$response'")
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
    // All stationary — should be 8 comma-separated zero values
    assert(response.contains(", "), s"TV should return comma-separated values, got: '$response'")
    val values = response.split(",").map(_.trim.toDouble)
    assert(values.length == 8, s"TV should return 8 values, got: ${values.length}")
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
    assert(response == "0, 0, 0, 0, 0, 0, 0, 0", s"SC should return all axes, got: '$response'")
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
    // All default: stepper (bit 0) + reverse limit clear (bit 2) + forward limit clear (bit 3)
    // = 0x01 | 0x04 | 0x08 = 13. Not homed (bit 1 = 0).
    assert(response == "13, 13, 13, 13, 13, 13, 13, 13", s"TS should return switches for all axes, got: '$response'")
  }

  test("TS should show homed bit after #Home") {
    val sim = spawnSimulator()
    send(sim, "XQ #HomeA,1")
    Thread.sleep(200)

    // Axis A: stepper (1) + homed (2) + rev clear (4) + fwd clear (8) = 15
    val tsa = sendText(sim, "TSA")
    assert(tsa.toInt == 15, s"TSA after home should be 15 (stepper+homed+limits clear), got: '$tsa'")

    // Axis B: stepper (1) + rev clear (4) + fwd clear (8) = 13
    val tsb = sendText(sim, "TSB")
    assert(tsb.toInt == 13, s"TSB should be 13 (stepper+limits clear, not homed), got: '$tsb'")
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

  // ==========================================================================
  // 12. Digital and Analog I/O
  // ==========================================================================

  test("SB should set a digital output bit and QR should reflect it") {
    val sim = spawnSimulator()
    val before = sendQR(sim)
    assert((before.generalState.outputs(0) & 0x01) == 0, "bit 1 should start clear")

    sendText(sim, "SB 1")

    val after = sendQR(sim)
    assert((after.generalState.outputs(0) & 0x01) != 0, "bit 1 should be set after SB 1")
  }

  test("CB should clear a previously set digital output bit") {
    val sim = spawnSimulator()
    sendText(sim, "SB 1")
    val set = sendQR(sim)
    assert((set.generalState.outputs(0) & 0x01) != 0, "bit 1 should be set")

    sendText(sim, "CB 1")
    val cleared = sendQR(sim)
    assert((cleared.generalState.outputs(0) & 0x01) == 0, "bit 1 should be cleared after CB 1")
  }

  test("SB should set the correct byte and bit for outputs 1-8") {
    for bit1 <- 1 to 8 do
      val sim = spawnSimulator()
      sendText(sim, s"SB $bit1")
      val dr = sendQR(sim)
      val mask = 1 << (bit1 - 1)
      assert((dr.generalState.outputs(0) & mask) != 0,
        s"SB $bit1: bit ${bit1-1} of outputs(0) should be set")
  }

  test("multiple SB commands should accumulate independently") {
    val sim = spawnSimulator()
    sendText(sim, "SB 1")
    sendText(sim, "SB 3")
    sendText(sim, "SB 5")
    val dr = sendQR(sim)
    val byte0 = dr.generalState.outputs(0) & 0xFF
    assert((byte0 & 0x01) != 0, "bit 1 should be set")
    assert((byte0 & 0x04) != 0, "bit 3 should be set")
    assert((byte0 & 0x10) != 0, "bit 5 should be set")
    assert((byte0 & 0x02) == 0, "bit 2 should remain clear")
  }

  test("CB should not affect other bits") {
    val sim = spawnSimulator()
    sendText(sim, "SB 1")
    sendText(sim, "SB 2")
    sendText(sim, "CB 1")
    val dr = sendQR(sim)
    val byte0 = dr.generalState.outputs(0) & 0xFF
    assert((byte0 & 0x01) == 0, "bit 1 should be cleared")
    assert((byte0 & 0x02) != 0, "bit 2 should remain set")
  }

  test("MG @AN[n] should return a numeric value for all 8 channels") {
    val sim = spawnSimulator()
    for channel <- 1 to 8 do
      val response = sendText(sim, s"MG @AN[$channel]")
      val value = response.toDouble
      assert(value >= 0.0 && value <= 10.0,
        s"@AN[$channel] should return a value in [0,10], got: '$response'")
  }

  test("SB/CB should work for bits 9-16 (second byte, slave module range)") {
    val sim = spawnSimulator()
    // Bit 9 = bit 0 of byte 1
    sendText(sim, "SB 9")
    val afterSet = sendQR(sim)
    assert((afterSet.generalState.outputs(1) & 0x01) != 0,
      "SB 9: bit 0 of outputs(1) should be set")
    assert(afterSet.generalState.outputs(0) == 0.toByte,
      "SB 9: outputs(0) should be unaffected")

    sendText(sim, "CB 9")
    val afterClear = sendQR(sim)
    assert((afterClear.generalState.outputs(1) & 0x01) == 0,
      "CB 9: bit 0 of outputs(1) should be cleared")

    // Bit 16 = bit 7 of byte 1
    sendText(sim, "SB 16")
    val afterSB16 = sendQR(sim)
    assert((afterSB16.generalState.outputs(1) & 0x80.toByte) != 0,
      "SB 16: bit 7 of outputs(1) should be set")
  }

  test("MG @AN[n] should return 2.5V baseline for all 8 channels (1-based, matching HCD poll)") {
    val sim = spawnSimulator()
    for channel <- 1 to 8 do
      val response = sendText(sim, s"MG @AN[$channel]")
      assert(response == "2.5000",
        s"@AN[$channel] should return simulator baseline 2.5000, got: '$response'")
  }

  test("MG @AN[1],@AN[2],...,@AN[8] compound query should return 8 space-separated 2.5V values") {
    val sim = spawnSimulator()
    val compound = s"MG ${(1 to 8).map(n => s"@AN[$n]").mkString(",")}"
    val response = sendText(sim, compound)
    val tokens = response.trim.split("\\s+").filter(_.nonEmpty)
    assert(tokens.length == 8, s"Compound MG @AN should return 8 values, got ${tokens.length}: '$response'")
    tokens.foreach { token =>
      assert(token == "2.5000", s"Each compound @AN value should be 2.5000, got: '$token'")
    }
  }

  test("MG _LDx defaults to 0 (both limits enabled) for an axis that has no LD set") {
    val sim = spawnSimulator()
    val response = sendText(sim, "MG _LDA")
    assert(response == "0.0000", s"_LDA default should be 0.0000, got: '$response'")
  }

  test("LDx=N round-trip: write LDA=1, MG _LDA returns 1.0000") {
    val sim = spawnSimulator()
    send(sim, "LDA=1")
    val response = sendText(sim, "MG _LDA")
    assert(response == "1.0000", s"After LDA=1, _LDA should be 1.0000, got: '$response'")
  }

  test("MG _LDA,_LDB,_LDC compound query returns three space-separated values") {
    val sim = spawnSimulator()
    send(sim, "LDA=0")
    send(sim, "LDB=1")
    send(sim, "LDC=3")
    val response = sendText(sim, "MG _LDA,_LDB,_LDC")
    val tokens = response.trim.split("\\s+").filter(_.nonEmpty)
    assert(tokens.length == 3, s"Compound MG _LD should return 3 values, got ${tokens.length}: '$response'")
    assert(tokens(0) == "0.0000", s"_LDA expected 0.0000, got: '${tokens(0)}'")
    assert(tokens(1) == "1.0000", s"_LDB expected 1.0000, got: '${tokens(1)}'")
    assert(tokens(2) == "3.0000", s"_LDC expected 3.0000, got: '${tokens(2)}'")
  }

  // ==========================================================================
  // PVT tracking (Session 65)
  //
  // These tests exercise the simulator's emulation of Galil PVT (Position-
  // Velocity-Time) streaming.  The HCD's tracking pipeline (handleTrackAxis +
  // ControllerStatusActor's _PV/_BT polling + IS preemptive underrun detection)
  // is verified against this simulator before lab-controller integration.
  //
  // Wire format reminder (the S64 lesson): `PV<axis>=ΔP,V,T` — the third letter
  // of the wire command IS the axis designator.  `PVA=10,5,100` queues a segment
  // for axis A; `PVB=10,5,100` queues for axis B.
  // ==========================================================================

  test("PVA segment write should be accepted with prompt ack") {
    val sim = spawnSimulator()
    val response = send(sim, "PVA=1000,500,100").utf8String
    assert(response == ":", s"PVA accept should return ':', got: '$response'")
  }

  test("malformed PVA (wrong arg count) should return ? error") {
    val sim = spawnSimulator()
    val r1 = send(sim, "PVA=1000,500").utf8String
    assert(r1 == "?", s"PVA with 2 args should return '?', got: '$r1'")
    val r2 = send(sim, "PVA=1000,500,100,200").utf8String
    assert(r2 == "?", s"PVA with 4 args should return '?', got: '$r2'")
  }

  test("malformed PVA (non-numeric args) should return ? error") {
    val sim = spawnSimulator()
    val response = send(sim, "PVA=foo,bar,baz").utf8String
    assert(response == "?", s"PVA with non-numeric args should return '?', got: '$response'")
  }

  test("_PVA on empty FIFO should return 255 (max free slots)") {
    val sim = spawnSimulator()
    val response = sendText(sim, "MG _PVA")
    assert(response == "255.0000", s"_PVA on empty FIFO should be 255.0000, got: '$response'")
  }

  test("_PVA should decrease as PVA segments are queued") {
    val sim = spawnSimulator()
    send(sim, "PVA=100,50,10")
    val r1 = sendText(sim, "MG _PVA")
    assert(r1 == "254.0000", s"After 1 segment _PVA should be 254.0000, got: '$r1'")
    send(sim, "PVA=200,75,10")
    send(sim, "PVA=300,100,10")
    val r3 = sendText(sim, "MG _PVA")
    assert(r3 == "252.0000", s"After 3 segments _PVA should be 252.0000, got: '$r3'")
  }

  test("_BTA on fresh state should be 0") {
    val sim = spawnSimulator()
    val response = sendText(sim, "MG _BTA")
    assert(response == "0.0000", s"_BTA on fresh state should be 0.0000, got: '$response'")
  }

  test("compound MG _PVA,_BTA should return two space-separated values (CS-side query form)") {
    // This is the wire form the HCD's ControllerStatusActor uses to monitor
    // tracking axes.  Format must match: tokens separated by whitespace, two
    // tokens per axis (interleaved _PV<x>,_BT<x>).
    val sim = spawnSimulator()
    send(sim, "PVA=100,50,10")
    send(sim, "PVA=200,75,10")
    val response = sendText(sim, "MG _PVA,_BTA")
    val tokens = response.trim.split("\\s+").filter(_.nonEmpty)
    assert(tokens.length == 2, s"Compound MG _PVA,_BTA should return 2 tokens, got ${tokens.length}: '$response'")
    assert(tokens(0) == "253.0000", s"_PVA expected 253.0000, got: '${tokens(0)}'")
    assert(tokens(1) == "0.0000",   s"_BTA expected 0.0000, got: '${tokens(1)}'")
  }

  test("compound MG _PVA,_BTA,_PVB,_BTB (multi-axis) should return four interleaved values") {
    // CS-side wire form when multiple axes are tracking.  Order matches
    // sortedAxes.flatMap(a => Seq(_PV<x>, _BT<x>)) — stride 2 per axis.
    val sim = spawnSimulator()
    send(sim, "PVA=100,50,10")
    send(sim, "PVB=200,75,10")
    send(sim, "PVB=300,100,10")
    val response = sendText(sim, "MG _PVA,_BTA,_PVB,_BTB")
    val tokens = response.trim.split("\\s+").filter(_.nonEmpty)
    assert(tokens.length == 4, s"Expected 4 tokens, got ${tokens.length}: '$response'")
    assert(tokens(0) == "254.0000", s"_PVA expected 254.0000, got: '${tokens(0)}'")
    assert(tokens(1) == "0.0000",   s"_BTA expected 0.0000, got: '${tokens(1)}'")
    assert(tokens(2) == "253.0000", s"_PVB expected 253.0000, got: '${tokens(2)}'")
    assert(tokens(3) == "0.0000",   s"_BTB expected 0.0000, got: '${tokens(3)}'")
  }

  test("PVA queuing should be independent across axes") {
    val sim = spawnSimulator()
    send(sim, "PVA=100,50,10")
    send(sim, "PVA=200,75,10")
    send(sim, "PVB=300,100,10")
    val pvA = sendText(sim, "MG _PVA")
    val pvB = sendText(sim, "MG _PVB")
    val pvC = sendText(sim, "MG _PVC")
    assert(pvA == "253.0000", s"_PVA with 2 queued should be 253.0000, got: '$pvA'")
    assert(pvB == "254.0000", s"_PVB with 1 queued should be 254.0000, got: '$pvB'")
    assert(pvC == "255.0000", s"_PVC with 0 queued should be 255.0000, got: '$pvC'")
  }

  test("BTA should begin trajectory and reset _BTA counter") {
    val sim = spawnSimulator()
    send(sim, "PVA=100,50,200")  // T=200 samples = 200ms — long enough to observe
    val response = send(sim, "BTA").utf8String
    assert(response == ":", s"BTA should ack with ':', got: '$response'")

    // After BTA, the active segment is dequeued from the FIFO immediately, so
    // _PVA should rise back to 255 (queue is empty, the segment is now active
    // not queued).  _BTA reset to 0.
    val pv = sendText(sim, "MG _PVA")
    val bt = sendText(sim, "MG _BTA")
    assert(pv == "255.0000", s"After BTA dequeues active segment, _PVA should be 255.0000, got: '$pv'")
    assert(bt == "0.0000",   s"_BTA reset on BT, got: '$bt'")
  }

  test("BTA on empty FIFO should ack but not begin motion") {
    val sim = spawnSimulator()
    send(sim, "BTA")  // No segments queued
    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) == 0, s"BTA on empty FIFO should not start motion, status=0x${hex(status)}")
  }

  test("PVT motion: position advances, _BTA increments as segments execute") {
    // Single segment: ΔP=500, V=5000, T=100 samples (= 100 ms at _TM=1000µs).
    // At our motion tick of 10ms, one segment = 10 ticks.  After ~150ms the
    // segment should be complete, position at 500, _BTA=1.
    val sim = spawnSimulator()
    send(sim, "PVA=500,5000,100")
    send(sim, "BTA")

    Thread.sleep(300)  // generous: segment is 100ms, ramp + idle time

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 500,
      s"After segment complete position should be 500, got: ${dr.axisStatuses(0).auxiliaryPosition}")
    val bt = sendText(sim, "MG _BTA").toDouble.toInt
    assert(bt == 1, s"_BTA should be 1 after one segment executed, got: $bt")
  }

  test("PVT motion: status word bit 15 set during segment, bit 14 (PA/PR) clear") {
    val sim = spawnSimulator()
    send(sim, "PVA=2000,5000,500")  // 500ms segment
    send(sim, "BTA")

    Thread.sleep(50)  // Mid-segment

    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) != 0, s"Bit 15 (moving) should be set during PVT, status=0x${hex(status)}")
    assert((status & (1 << 14)) == 0, s"Bit 14 (PA/PR mode) should be clear during PVT, status=0x${hex(status)}")
  }

  test("PVT motion: motor turned on implicitly by BTA") {
    val sim = spawnSimulator()
    // Don't issue SH.  PVT segments + BT should energize the motor.
    send(sim, "PVA=500,5000,100")
    send(sim, "BTA")

    Thread.sleep(20)
    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    // bit 0 = Motor Off; should be CLEAR after BTA implicitly energizes
    assert((status & (1 << 0)) == 0, s"Bit 0 (motorOff) should be clear after BTA, status=0x${hex(status)}")
  }

  test("PVT multi-segment: continuous execution advances through FIFO") {
    val sim = spawnSimulator()
    // 3 segments × 100ms each = 300ms total trajectory.
    // Total ΔP = 100 + 200 + 300 = 600 counts.
    send(sim, "PVA=100,1000,100")
    send(sim, "PVA=200,2000,100")
    send(sim, "PVA=300,3000,100")
    send(sim, "BTA")

    Thread.sleep(500)

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 600,
      s"After 3 segments position should be 600, got: ${dr.axisStatuses(0).auxiliaryPosition}")
    val bt = sendText(sim, "MG _BTA").toDouble.toInt
    assert(bt == 3, s"_BTA should be 3 after 3 segments, got: $bt")
  }

  test("PVT (0,0,0) terminator stops trajectory and discards trailing segments") {
    val sim = spawnSimulator()
    send(sim, "PVA=100,1000,100")
    send(sim, "PVA=0,0,0")              // terminator
    send(sim, "PVA=999,9999,100")        // should be discarded
    send(sim, "BTA")

    Thread.sleep(300)

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 100,
      s"After terminator at seg 2, position should stop at 100, got: ${dr.axisStatuses(0).auxiliaryPosition}")
    // Trailing segment discarded → motion stopped
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) == 0, s"Bit 15 (moving) should be clear after terminator, status=0x${hex(status)}")
    // FIFO drained
    val pv = sendText(sim, "MG _PVA")
    assert(pv == "255.0000", s"FIFO should be empty (255 free) after terminator, got: '$pv'")
  }

  test("STA drains PVT FIFO and stops tracking") {
    val sim = spawnSimulator()
    send(sim, "PVA=1000,1000,500")   // long segment
    send(sim, "PVA=2000,2000,500")
    send(sim, "BTA")

    Thread.sleep(50)  // partway through first segment

    send(sim, "STA")

    Thread.sleep(50)  // give the motion tick a chance to settle

    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) == 0, s"Bit 15 (moving) should be clear after ST, status=0x${hex(status)}")
    // FIFO drained
    val pv = sendText(sim, "MG _PVA")
    assert(pv == "255.0000", s"FIFO should be empty after ST, got: '$pv'")
  }

  test("PVT underrun (empty FIFO without terminator) stops motor silently — no error code") {
    val sim = spawnSimulator()
    send(sim, "PVA=500,5000,50")  // 50ms segment
    send(sim, "BTA")

    Thread.sleep(300)  // Well past the segment end, no more segments arrive

    // Underrun: motion stopped, but no error code latched (TC remains 0)
    val tc = sendText(sim, "TC0")
    assert(tc == "0", s"TC should remain 0 on underrun (silent), got: '$tc'")

    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) == 0, s"Bit 15 (moving) should be clear after underrun, status=0x${hex(status)}")
  }

  test("MO drains PVT FIFO and clears tracking state") {
    val sim = spawnSimulator()
    send(sim, "PVA=1000,1000,500")
    send(sim, "PVA=2000,2000,500")
    send(sim, "BTA")

    Thread.sleep(50)

    send(sim, "MOA")

    Thread.sleep(50)

    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) == 0, s"Bit 15 (moving) should be clear after MO, status=0x${hex(status)}")
    assert((status & (1 << 0))  != 0, s"Bit 0 (motorOff) should be set after MO, status=0x${hex(status)}")
    val pv = sendText(sim, "MG _PVA")
    assert(pv == "255.0000", s"FIFO should be empty after MO, got: '$pv'")
  }

  test("#StopA program drains PVT FIFO (HCD trackAxis→stopAxis recovery path)") {
    // The HCD's handleStopAxis Tracking branch goes through executeProgramAndWatch(#StopX)
    // rather than direct STx.  The embedded #StopX program begins with STx so we must
    // model that the XQ #StopX path also drains PVT state.
    val sim = spawnSimulator()
    send(sim, "PVA=1000,1000,500")
    send(sim, "BTA")
    Thread.sleep(50)

    send(sim, "XQ #StopA,2")
    Thread.sleep(150)

    val dr = sendQR(sim)
    val status = dr.axisStatuses(0).status
    assert((status & (1 << 15)) == 0, s"Bit 15 (moving) should be clear after #StopA, status=0x${hex(status)}")
    val pv = sendText(sim, "MG _PVA")
    assert(pv == "255.0000", s"FIFO should be empty after #StopA, got: '$pv'")
  }

  test("RS preserves PVT capability — fresh FIFO after reset") {
    val sim = spawnSimulator()
    send(sim, "PVA=100,50,100")
    send(sim, "PVA=200,75,100")
    send(sim, "RS")
    // Post-reset, FIFO should be empty (255 free) and a fresh BTA should still work.
    val pv = sendText(sim, "MG _PVA")
    assert(pv == "255.0000", s"After RS, _PVA should be back to 255.0000, got: '$pv'")
    val r = send(sim, "PVA=300,100,10").utf8String
    assert(r == ":", s"PVA after RS should still be accepted, got: '$r'")
  }

  test("PVT FIFO at capacity rejects further segments with ?") {
    // Fill the FIFO to capacity (255 segments queued) and verify the 256th
    // is rejected.  We don't issue BT — segments stay queued.
    val sim = spawnSimulator()
    for (_ <- 1 to 255) {
      send(sim, "PVA=1,1,10")
    }
    val pv = sendText(sim, "MG _PVA")
    assert(pv == "0.0000", s"After 255 queued, _PVA should be 0.0000 (FIFO full), got: '$pv'")
    val response = send(sim, "PVA=999,999,10").utf8String
    assert(response == "?", s"PVA on full FIFO should return '?', got: '$response'")
  }

  test("PVT for axis B works alongside PA motion on axis A (axes are independent)") {
    val sim = spawnSimulator()
    // Axis A: classic PA motion via embedded #MoveA
    send(sim, "dmd[0]=400")
    send(sim, "speed[0]=10000")
    send(sim, "XQ #MoveA,1")

    // Axis B: PVT segment
    send(sim, "PVB=500,5000,100")
    send(sim, "BTB")

    Thread.sleep(300)

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 400,
      s"Axis A PA should reach 400, got: ${dr.axisStatuses(0).auxiliaryPosition}")
    assert(dr.axisStatuses(1).auxiliaryPosition == 500,
      s"Axis B PVT should reach 500, got: ${dr.axisStatuses(1).auxiliaryPosition}")
  }

  // ==========================================================================
  // 13. #Select — discrete-position wheels and the whlpos[] achieved-slot handshake
  //
  // This is the path the wheel assemblies' in-position logic depends on: the HCD
  // compares the achieved slot (whlpos[idx], polled at the standby rate) against the
  // commanded slot, so the simulator must invalidate whlpos while the wheel moves and
  // publish it only on arrival.
  // ==========================================================================

  test("whlpos[] defaults to -1 (slot unknown) for all eight axes after #Init") {
    val sim = spawnSimulator()
    send(sim, "XQ #Init,0")
    Thread.sleep(150)

    (0 until 8).foreach { i =>
      val v = sendText(sim, s"MG whlpos[$i]").toDouble
      assert(v == -1.0, s"whlpos[$i] should default to -1 (unknown), got $v")
    }
  }

  test("XQ #SelectA invalidates whlpos while the wheel is moving") {
    val sim = spawnSimulator()
    send(sim, "cpr[0]=3600")
    send(sim, "whlpos[0]=1")          // wheel starts at a known slot
    send(sim, "dmd[0]=3")             // select slot 3
    send(sim, "speed[0]=2000")
    send(sim, "XQ #SelectA,1")

    // Checked immediately: the wheel is between slots, so the achieved slot is unknown.
    val whl = sendText(sim, "MG whlpos[0]").toDouble
    assert(whl == -1.0, s"whlpos[0] should be invalidated during the select, got $whl")

    val status = sendQR(sim).axisStatuses(0).status
    assert((status & (1 << 15)) != 0, s"axis should be moving, status=0x${hex(status)}")
  }

  test("#SelectA computes the slot target as (slot - 1) * cpr / 8 and publishes it on arrival") {
    val sim = spawnSimulator()
    send(sim, "cpr[0]=3600")
    send(sim, "dmd[0]=3")             // slot 3 → (3-1) * 3600/8 = 900 counts
    send(sim, "speed[0]=20000")
    send(sim, "XQ #SelectA,1")

    Thread.sleep(600)

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 900,
      s"slot 3 of a 3600-count wheel is 900 counts, got: ${dr.axisStatuses(0).auxiliaryPosition}")

    // The achieved slot is published only now, on arrival.
    val whl = sendText(sim, "MG whlpos[0]").toDouble
    assert(whl == 3.0, s"whlpos[0] should be the achieved slot 3, got $whl")

    // Thread released and the axis error element cleared by the success path.
    val noVal = sendText(sim, "MG _NO").toDouble.toInt
    assert(noVal == 0, s"select thread should be released, _NO=$noVal")
    assert(sendText(sim, "MG ae[0]").toDouble == 0.0, "ae[0] should be cleared on success")
  }

  test("#SelectA slot 1 is the home angle (zero counts)") {
    val sim = spawnSimulator()
    send(sim, "DPA=900")              // start away from slot 1
    send(sim, "cpr[0]=3600")
    send(sim, "dmd[0]=1")
    send(sim, "speed[0]=20000")
    send(sim, "XQ #SelectA,1")

    Thread.sleep(600)

    assert(sendQR(sim).axisStatuses(0).auxiliaryPosition == 0,
      "slot 1 is the home angle, i.e. zero counts")
    assert(sendText(sim, "MG whlpos[0]").toDouble == 1.0, "whlpos[0] should be 1")
  }

  test("#SelectA falls back to the demand as a raw count target when cpr is unset") {
    val sim = spawnSimulator()
    send(sim, "dmd[0]=250")           // no cpr[0] set
    send(sim, "speed[0]=20000")
    send(sim, "XQ #SelectA,1")

    Thread.sleep(600)

    assert(sendQR(sim).axisStatuses(0).auxiliaryPosition == 250,
      "with cpr unset the demand is used directly as a count target")
    assert(sendText(sim, "MG whlpos[0]").toDouble == 250.0,
      "the published slot mirrors the demand on the fallback path")
  }

  test("a plain #Move leaves whlpos untouched — only a select publishes an achieved slot") {
    val sim = spawnSimulator()
    send(sim, "cpr[0]=3600")
    send(sim, "whlpos[0]=4")          // wheel known to be at slot 4
    send(sim, "dmd[0]=1500")
    send(sim, "speed[0]=20000")
    send(sim, "XQ #MoveA,1")

    Thread.sleep(600)

    assert(sendQR(sim).axisStatuses(0).auxiliaryPosition == 1500, "the move should reach 1500")
    assert(sendText(sim, "MG whlpos[0]").toDouble == 4.0,
      "a non-select move must not republish or invalidate the achieved slot")
  }

  test("an interrupted select leaves the slot unknown, and a later move cannot publish it") {
    val sim = spawnSimulator()
    send(sim, "cpr[0]=3600")
    send(sim, "dmd[0]=5")             // slot 5 → 1800 counts, far enough to interrupt
    send(sim, "speed[0]=2000")
    send(sim, "XQ #SelectA,1")
    Thread.sleep(100)                 // in flight

    send(sim, "XQ #StopA,2")          // abandon the select
    Thread.sleep(300)

    assert(sendText(sim, "MG whlpos[0]").toDouble == -1.0,
      "an abandoned select must leave the achieved slot unknown")

    // A later, unrelated move that reaches its target must NOT inherit the abandoned
    // select's slot: before the marker was cleared on abandon, this published slot 5.
    send(sim, "dmd[0]=100")
    send(sim, "speed[0]=20000")
    send(sim, "XQ #MoveA,3")
    Thread.sleep(600)

    assert(sendQR(sim).axisStatuses(0).auxiliaryPosition == 100, "the later move should reach 100")
    assert(sendText(sim, "MG whlpos[0]").toDouble == -1.0,
      "the abandoned select's slot must not be published by a subsequent move")
  }

  test("ST during a select also leaves the slot unknown") {
    val sim = spawnSimulator()
    send(sim, "cpr[0]=3600")
    send(sim, "dmd[0]=5")
    send(sim, "speed[0]=2000")
    send(sim, "XQ #SelectA,1")
    Thread.sleep(100)

    send(sim, "STA")
    Thread.sleep(200)

    send(sim, "dmd[0]=100")
    send(sim, "speed[0]=20000")
    send(sim, "XQ #MoveA,2")
    Thread.sleep(600)

    assert(sendText(sim, "MG whlpos[0]").toDouble == -1.0,
      "an ST-abandoned select must not publish its slot on a later move")
  }

  test("selects on two axes publish their own slots independently") {
    val sim = spawnSimulator()
    send(sim, "cpr[0]=3600")
    send(sim, "cpr[1]=800")
    send(sim, "dmd[0]=2")             // A: slot 2 → 450 counts
    send(sim, "dmd[1]=5")             // B: slot 5 → 400 counts
    send(sim, "speed[0]=20000")
    send(sim, "speed[1]=20000")
    send(sim, "XQ #SelectA,1")
    send(sim, "XQ #SelectB,2")

    Thread.sleep(700)

    val dr = sendQR(sim)
    assert(dr.axisStatuses(0).auxiliaryPosition == 450,
      s"axis A slot 2 of 3600 is 450, got ${dr.axisStatuses(0).auxiliaryPosition}")
    assert(dr.axisStatuses(1).auxiliaryPosition == 400,
      s"axis B slot 5 of 800 is 400, got ${dr.axisStatuses(1).auxiliaryPosition}")
    assert(sendText(sim, "MG whlpos[0]").toDouble == 2.0, "axis A should report slot 2")
    assert(sendText(sim, "MG whlpos[1]").toDouble == 5.0, "axis B should report slot 5")
  }

  test("compound MG whlpos[0],whlpos[1] returns both slots space-separated (the HCD poll form)") {
    val sim = spawnSimulator()
    send(sim, "whlpos[0]=3")
    send(sim, "whlpos[1]=7")

    val parts = sendText(sim, "MG whlpos[0],whlpos[1]").split("\\s+").filter(_.nonEmpty)
    assert(parts.length == 2, s"expected two values, got: ${parts.mkString(",")}")
    assert(parts(0).toDouble == 3.0 && parts(1).toDouble == 7.0,
      s"expected 3 and 7, got: ${parts.mkString(",")}")
  }
}
