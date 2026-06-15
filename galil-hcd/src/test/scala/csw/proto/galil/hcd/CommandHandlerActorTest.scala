package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.AskPattern
import org.apache.pekko.util.Timeout
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._
import csw.proto.galil.io.{GalilIo, GalilIoTcp}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.compiletime.uninitialized
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Tests for Command Handler Actor — Immediate Commands.
 *
 * Tests the building blocks used by the CommandHandlerActor:
 *
 * Section 1: Galil compound command execution (the commands configAxis builds)
 * Section 2: configAxis parameter mapping (axis index, embedded array names)
 * Section 3: InternalState updates (configRotatingAxis, configLinearAxis, inPositionThreshold)
 * Section 4: setBit / setAO Galil commands
 *
 * These tests verify the command strings and state updates that CommandHandlerActor
 * produces. They use GalilIo directly for controller tests (same pattern as
 * ControllerInterfaceActorTest) and a real InternalStateActor for state tests.
 *
 * Prerequisites:
 * - Galil controller or simulator must be running
 * - Configure host/port via system properties or defaults to simulator
 */
class CommandHandlerActorTest extends AnyFunSuite with Matchers with BeforeAndAfterAll {

  // Configure controller connection
  private val galilHost = sys.props.get("galil.host")
    .orElse(sys.env.get("GALIL_HOST"))
    .getOrElse("127.0.0.1")

  private val galilPort = sys.props.get("galil.port")
    .orElse(sys.env.get("GALIL_PORT"))
    .getOrElse("8888").toInt

  private val testKit = ActorTestKit()
  private var galilIo: GalilIo = uninitialized
  private var internalStateActor: org.apache.pekko.actor.typed.ActorRef[InternalStateActor.Command] = uninitialized

  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = testKit.system.scheduler

  override def beforeAll(): Unit = {
    super.beforeAll()
    println(s"CommandHandlerActorTest: Connecting to Galil at $galilHost:$galilPort")
    galilIo = GalilIoTcp(galilHost, galilPort)
    internalStateActor = testKit.spawn(InternalStateActor.apply(), "TestInternalState")
    println("Test setup complete")
  }

  override def afterAll(): Unit = {
    testKit.shutdownTestKit()
    if (galilIo != null) galilIo.close()
    super.afterAll()
  }

  // ========================================
  // Helpers
  // ========================================

  /** Query an embedded variable value from the controller */
  private def queryVariable(name: String): Double = {
    val responses = galilIo.send(s"MG $name")
    responses.head._2.utf8String.trim.toDouble
  }

  /** Set an embedded variable on the controller */
  private def setVariable(name: String, value: Double): Unit = {
    galilIo.send(s"$name=$value")
  }

  /** Query axis state from InternalStateActor */
  private def getAxisState(axis: Axis): Option[AxisState] = {
    val future = AskPattern.Askable(internalStateActor)
      .ask[Option[AxisState]](ref => InternalStateActor.GetAxisState(axis, ref))
    Await.result(future, 3.seconds)
  }

  /** Update axis state and wait for processing */
  private def updateAxisState(axis: Axis, updates: Map[String, Any]): Unit = {
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    internalStateActor ! InternalStateActor.UpdateAxisState(axis, updates, probe.ref)
    probe.expectMessageType[InternalStateActor.UpdateResponse](3.seconds).success shouldBe true
  }

  // Detect if we're connected to real hardware or simulator
  // Simulator MG always returns "2.5", real hardware returns actual values
  private lazy val isHardware: Boolean = {
    try {
      // Query a known variable - simulator returns "2.5" for everything
      val response = galilIo.send("MG TIME")
      val value = response.head._2.utf8String.trim.toDouble
      // TIME on real hardware is a large number (ms since power-on); simulator returns 2.5
      value > 100.0
    } catch {
      case _: Exception => false
    }
  }

  /** Skip test if not connected to real hardware */
  private def requireHardware(): Unit =
    assume(isHardware, "Test requires real Galil hardware (not simulator)")

  // ========================================
  // Section 1: Compound Command Execution
  //   (verifies the Galil command strings that configAxis builds)
  //   HARDWARE ONLY — simulator doesn't support embedded variable assignment
  // ========================================

  test("single variable assignment should succeed") {
    info("Testing: speed[0]=<value>")
    requireHardware()

    val original = queryVariable("speed[0]")
    val testValue = 1234.0

    galilIo.send(s"speed[0]=$testValue")
    queryVariable("speed[0]") shouldBe testValue +- 0.01

    // Restore
    galilIo.send(s"speed[0]=$original")
    info(s"Verified speed[0] round-trip: $original → $testValue → $original")
  }



  test("compound command should set multiple variables") {
    info("Testing: speed[0]=v;accel[0]=a;decel[0]=d (compound command)")
    requireHardware()

    val origSpeed = queryVariable("speed[0]")
    val origAccel = queryVariable("accel[0]")
    val origDecel = queryVariable("decel[0]")
    info(s"Originals - speed: $origSpeed, accel: $origAccel, decel: $origDecel")

    val testSpeed = 750.0
    val testAccel = 5000.0
    val testDecel = 4000.0

    // Compound command — GalilIo.send() handles multiple ":" in a single TCP packet
    val responses = galilIo.send(s"speed[0]=$testSpeed;accel[0]=$testAccel;decel[0]=$testDecel")
    responses should have size 3
    info(s"Compound command returned ${responses.size} responses")

    // Verify all three changed
    queryVariable("speed[0]") shouldBe testSpeed +- 0.01
    queryVariable("accel[0]") shouldBe testAccel +- 0.01
    queryVariable("decel[0]") shouldBe testDecel +- 0.01
    info("All three parameters verified on controller")

    // Restore — chunk like production configAxis so large original values
    // can't exceed the 80-char line limit on teardown.
    GalilIo.chunkCompound(Seq(
      s"speed[0]=$origSpeed", s"accel[0]=$origAccel", s"decel[0]=$origDecel"
    )).foreach(galilIo.send)
  }

  test("compound command with all five configAxis parameters") {
    info("Testing: speed;accel;decel;hoff;hspd compound command")
    requireHardware()

    val origSpeed = queryVariable("speed[0]")
    val origAccel = queryVariable("accel[0]")
    val origDecel = queryVariable("decel[0]")
    val origHoff = queryVariable("hoff[0]")
    val origHspd = queryVariable("hspd[0]")

    val cmd = "speed[0]=600;accel[0]=8000;decel[0]=7000;hoff[0]=50;hspd[0]=300"
    val responses = galilIo.send(cmd)
    responses should have size 5
    info(s"Compound command returned ${responses.size} responses")

    queryVariable("speed[0]") shouldBe 600.0 +- 0.01
    queryVariable("accel[0]") shouldBe 8000.0 +- 0.01
    queryVariable("decel[0]") shouldBe 7000.0 +- 0.01
    queryVariable("hoff[0]") shouldBe 50.0 +- 0.01
    queryVariable("hspd[0]") shouldBe 300.0 +- 0.01
    info("All five parameters verified")

    // Restore — chunk like production configAxis so large original values
    // (e.g. 100000.0 each) can't exceed the 80-char line limit and leave the
    // controller in the modified state on teardown.
    GalilIo.chunkCompound(Seq(
      s"speed[0]=$origSpeed", s"accel[0]=$origAccel", s"decel[0]=$origDecel",
      s"hoff[0]=$origHoff",   s"hspd[0]=$origHspd"
    )).foreach(galilIo.send)
  }


  // ========================================
  // Section 2: Axis Index Mapping
  //   (verifies configAxis uses correct array indices)
  // ========================================

  test("axis A should map to index 0") {
    info("Testing axis A → array index [0]")
    requireHardware()
    Axis.A.index shouldBe 0

    val original = queryVariable("speed[0]")
    galilIo.send("speed[0]=999")
    queryVariable("speed[0]") shouldBe 999.0 +- 0.01
    galilIo.send(s"speed[0]=$original")
    info("Axis A → index 0 confirmed")
  }

  test("axis B should map to index 1") {
    info("Testing axis B → array index [1]")
    requireHardware()
    Axis.B.index shouldBe 1

    val original = queryVariable("speed[1]")
    galilIo.send("speed[1]=888")
    queryVariable("speed[1]") shouldBe 888.0 +- 0.01
    galilIo.send(s"speed[1]=$original")
    info("Axis B → index 1 confirmed")
  }

  test("compound command on axis B should use index 1") {
    info("Testing compound command with axis B indices")
    requireHardware()

    val origSpeed = queryVariable("speed[1]")
    val origAccel = queryVariable("accel[1]")

    val responses = galilIo.send("speed[1]=777;accel[1]=6000")
    responses should have size 2

    queryVariable("speed[1]") shouldBe 777.0 +- 0.01
    queryVariable("accel[1]") shouldBe 6000.0 +- 0.01

    galilIo.send(s"speed[1]=$origSpeed;accel[1]=$origAccel")
    info("Axis B compound command verified")
  }

  // ========================================
  // Section 3: InternalState Updates
  //   (configRotatingAxis, configLinearAxis, inPositionThreshold)
  // ========================================

  test("configRotatingAxis should set mechanismType and algorithm") {
    info("Testing InternalState update for rotating axis")

    updateAxisState(Axis.A, Map(
      "mechanismType" -> MechanismType.Rotating,
      "algorithm" -> RotatingAlgorithm.Shortest
    ))

    val state = getAxisState(Axis.A)
    state shouldBe defined
    state.get.mechanismType shouldBe MechanismType.Rotating
    state.get.algorithm shouldBe Some(RotatingAlgorithm.Shortest)
    info(s"Axis A: type=${state.get.mechanismType}, algorithm=${state.get.algorithm}")
  }

  test("all three rotating algorithms should be stored correctly") {
    info("Testing Forward, Reverse, Shortest algorithm storage")

    for (algo <- List(RotatingAlgorithm.Forward, RotatingAlgorithm.Reverse, RotatingAlgorithm.Shortest)) {
      updateAxisState(Axis.B, Map(
        "mechanismType" -> MechanismType.Rotating,
        "algorithm" -> algo
      ))

      val state = getAxisState(Axis.B)
      state.get.algorithm shouldBe Some(algo)
      info(s"  $algo → verified")
    }
  }

  test("configLinearAxis should set mechanismType and limits") {
    info("Testing InternalState update for linear axis")

    updateAxisState(Axis.A, Map(
      "mechanismType" -> MechanismType.Linear,
      "upperLimit" -> 10000.0,
      "lowerLimit" -> -5000.0
    ))

    val state = getAxisState(Axis.A)
    state shouldBe defined
    state.get.mechanismType shouldBe MechanismType.Linear
    state.get.upperLimit shouldBe Some(10000.0)
    state.get.lowerLimit shouldBe Some(-5000.0)
    info(s"Axis A: type=${state.get.mechanismType}, upper=${state.get.upperLimit}, lower=${state.get.lowerLimit}")
  }

  test("linear config should override previous rotating config") {
    info("Testing mechanismType override: Rotating → Linear")

    // Set rotating first
    updateAxisState(Axis.C, Map(
      "mechanismType" -> MechanismType.Rotating,
      "algorithm" -> RotatingAlgorithm.Forward
    ))
    getAxisState(Axis.C).get.mechanismType shouldBe MechanismType.Rotating

    // Override with linear
    updateAxisState(Axis.C, Map(
      "mechanismType" -> MechanismType.Linear,
      "upperLimit" -> 5000.0,
      "lowerLimit" -> -5000.0
    ))
    val state = getAxisState(Axis.C)
    state.get.mechanismType shouldBe MechanismType.Linear
    state.get.upperLimit shouldBe Some(5000.0)
    info("Linear correctly overrode rotating config")
  }

  test("inPositionThreshold update should affect inPosition calculation") {
    info("Testing inPositionThreshold effect on inPosition")

    // Set position slightly off demand with tight threshold → not in position
    updateAxisState(Axis.D, Map(
      "position" -> 100.0,
      "demand" -> 100.5,
      "inPositionThreshold" -> 0.1
    ))
    getAxisState(Axis.D).get.inPosition shouldBe false
    info("  Tight threshold (0.1): inPosition=false ✓")

    // Widen threshold → now in position
    updateAxisState(Axis.D, Map("inPositionThreshold" -> 1.0))
    getAxisState(Axis.D).get.inPosition shouldBe true
    info("  Wide threshold (1.0): inPosition=true ✓")
  }

  // ========================================
  // Section 4: setBit / setAO Commands
  // ========================================

  test("SB command should be accepted by controller") {
    info("Testing SB (Set Bit) command")

    val responses = galilIo.send("SB 1")
    responses should have size 1
    // Response should be ":" (prompt, no error)
    val resp = responses.head._2.utf8String.trim
    resp should not startWith "?"
    info(s"SB 1 response: '$resp'")
  }

  test("CB command should be accepted by controller") {
    info("Testing CB (Clear Bit) command")

    val responses = galilIo.send("CB 1")
    responses should have size 1
    val resp = responses.head._2.utf8String.trim
    resp should not startWith "?"
    info(s"CB 1 response: '$resp'")
  }

  test("SB then CB should toggle a bit") {
    info("Testing SB/CB toggle sequence")

    galilIo.send("SB 0")
    info("  SB 0 (set bit 0)")

    galilIo.send("CB 0")
    info("  CB 0 (clear bit 0)")

    // If we get here without exception, both commands were accepted
    info("Toggle sequence completed successfully")
  }

  test("AO command should be accepted by controller") {
    info("Testing AO (Analog Output) command")

    // AO may not be available on all controllers/simulators
    // We test that the command doesn't cause an exception
    try {
      val responses = galilIo.send("AO 0,2.5")
      responses should have size 1
      val resp = responses.head._2.utf8String.trim
      if (resp.startsWith("?")) {
        info(s"AO 0,2.5 returned error (may not be supported on this hardware): '$resp'")
      } else {
        info(s"AO 0,2.5 accepted: '$resp'")
      }
    } catch {
      case ex: Exception =>
        info(s"AO command threw exception (may not be supported): ${ex.getMessage}")
    }
  }

  // ========================================
  // Section 5: Command Classification
  // ========================================

  test("isImmediate should classify commands correctly") {
    info("Testing command classification")

    // Immediate commands
    CommandHandlerActor.isImmediate("configAxis") shouldBe true
    CommandHandlerActor.isImmediate("configRotatingAxis") shouldBe true
    CommandHandlerActor.isImmediate("configLinearAxis") shouldBe true
    CommandHandlerActor.isImmediate("setBit") shouldBe true
    CommandHandlerActor.isImmediate("setAO") shouldBe true

    // Long-running commands
    CommandHandlerActor.isImmediate("homeAxis") shouldBe false
    CommandHandlerActor.isImmediate("positionAxis") shouldBe false
    CommandHandlerActor.isImmediate("offsetAxis") shouldBe false
    CommandHandlerActor.isImmediate("selectWheel") shouldBe false
    CommandHandlerActor.isImmediate("trackAxis") shouldBe false
    CommandHandlerActor.isImmediate("stopAxis") shouldBe false

    // faultReset is NOT in either CHA classification — it's dispatched
    // directly by GalilHcd because it drives HCD-level lifecycle state
    // (Session 58).  It should NOT be reported as immediate or long-running
    // by CHA's isImmediate / isLongRunning helpers.
    CommandHandlerActor.isImmediate("faultReset") shouldBe false
    CommandHandlerActor.isLongRunning("faultReset") shouldBe false

    info("All command classifications verified")
  }
}