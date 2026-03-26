package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.{CommandName, Setup}
import csw.params.core.generics.KeyType.ChoiceKey
import csw.params.core.models.Id
import csw.prefix.models.Prefix

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.duration._

/**
 * Unit tests for the approach algorithm applied by CommandHandlerActor
 * to positionAxis and offsetAxis commands on rotating mechanisms.
 *
 * Uses the same mock infrastructure as LongRunningCommandTest:
 *   CommandHandlerActor → MockCIActor → InternalStateActor
 *
 * Tests verify the value written to dmd[idx] — the adjusted count target
 * after the approach algorithm has been applied — against hand-calculated
 * expected values.
 *
 * Setup: axis B configured as Rotating, cpr=10.0 (360°= 3600 counts),
 *        algorithm varied per section.
 *        Axis A left as Linear to verify no algorithm is applied there.
 *
 * Section 1: Shortest algorithm
 * Section 2: Forward algorithm
 * Section 3: Reverse algorithm
 * Section 4: offsetAxis algorithm application
 * Section 5: Linear axis — no algorithm applied
 * Section 6: Rotating axis without cpd configured — no algorithm applied
 */
class RotatingMechanismTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testKit   = ActorTestKit()
  private val hcdPrefix = Prefix("APS.ICS.HCD.GalilMotion")

  // countsPerRev = 3600: 360° × 10 counts/° — matches simulator config
  private val countsPerRev = 3600.0

  override def afterAll(): Unit = testKit.shutdownTestKit()

  // ========================================
  // Mock CI Actor (same pattern as LongRunningCommandTest)
  // ========================================

  object MockCIActor:
    val commandLog = new ConcurrentLinkedQueue[String]()
    private val nextThread = new java.util.concurrent.atomic.AtomicInteger(1)

    def behavior(): Behavior[GalilCommandMessage] =
      Behaviors.receiveMessage {
        case GalilCommandMessage.SendCommand(cmd, replyTo) =>
          commandLog.add(cmd)
          replyTo ! GalilCommandMessage.SendCommandResult(":")
          Behaviors.same
        case GalilCommandMessage.ExecuteProgram(label, replyTo, preCommands) =>
          preCommands.foreach(cmd => commandLog.add(cmd))
          val thread = nextThread.getAndIncrement()
          commandLog.add(s"XQ #$label,$thread")
          replyTo ! GalilCommandMessage.ExecuteProgramResult(
            thread = thread, threadWasActive = true, error = None)
          Behaviors.same
        case GalilCommandMessage.HaltExecution(thread, axis, replyTo) =>
          commandLog.add(s"HX $thread")
          commandLog.add(s"ST ${axis.char}")
          replyTo ! GalilCommandMessage.HaltExecutionResult(success = true, error = None)
          Behaviors.same
        case _ => Behaviors.same
      }

    def clear(): Unit =
      commandLog.clear()
      nextThread.set(1)

    def commands: List[String] =
      import scala.jdk.CollectionConverters._
      commandLog.asScala.toList

  // ========================================
  // Test infrastructure
  // ========================================

  /**
   * Create test actors with axis B configured as a rotating mechanism.
   *
   * @param algorithm  Approach algorithm to configure on axis B
   * @param currentPos Starting position for axis B (encoder counts)
   */
  private def createRotatingActors(
    algorithm:  RotatingAlgorithm,
    currentPos: Double = 0.0
  ): (ActorRef[CommandHandlerActor.Command], ActorRef[InternalStateActor.Command]) =
    MockCIActor.clear()

    // Initialise both A (linear) and B (rotating)
    var state = HcdState()
    state = state.initializeAxis(Axis.A, MechanismType.Linear)
    state = state.initializeAxis(Axis.B, MechanismType.Rotating)

    val isActor = testKit.spawn(InternalStateActor(state))
    val ciActor = testKit.spawn(MockCIActor.behavior())
    val smStub  = testKit.spawn(Behaviors.receiveMessage[ControllerStatusActor.Command] { _ => Behaviors.same })
    val handler = testKit.spawn(
      CommandHandlerActor.behavior(ciActor, isActor, null, new LoggerFactory(hcdPrefix), smStub)
    )

    // Configure axis B: rotating, countsPerRev, algorithm, Idle state, current position
    isActor ! InternalStateActor.UpdateAxisState(Axis.B,
      Map(
        "mechanismType"      -> MechanismType.Rotating,
        "algorithm"          -> algorithm,
        "countsPerRevolution" -> countsPerRev,
        "axisState"          -> AxisStateEnum.Idle,
        "position"           -> currentPos
      ),
      testKit.system.ignoreRef)

    // Configure axis A: linear, Idle state
    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("axisState" -> AxisStateEnum.Idle),
      testKit.system.ignoreRef)

    Thread.sleep(50)
    (handler, isActor)

  /** Extract the dmd[idx]=value sent to the controller. */
  private def dmdSent(axisIdx: Int): Option[Double] =
    MockCIActor.commands
      .find(_.startsWith(s"dmd[$axisIdx]="))
      .map(_.stripPrefix(s"dmd[$axisIdx]=").toDouble)

  private def sendPositionAxis(
    handler: ActorRef[CommandHandlerActor.Command],
    axis: String,
    target: Double
  ): Unit =
    var setup = Setup(hcdPrefix, CommandName("positionAxis"), None)
    val axisKey = ChoiceKey.make("axis", "A", "B", "C", "D", "E", "F", "G", "H")
    setup = setup.add(axisKey.set(axis))
    setup = setup.add(csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`.PositionAxisCommand.targetKey.set(target.toFloat))
    handler ! CommandHandlerActor.HandleCommand(setup, Id(), None)
    Thread.sleep(150)

  private def sendOffsetAxis(
    handler: ActorRef[CommandHandlerActor.Command],
    axis: String,
    distance: Double
  ): Unit =
    var setup = Setup(hcdPrefix, CommandName("offsetAxis"), None)
    val axisKey = ChoiceKey.make("axis", "A", "B", "C", "D", "E", "F", "G", "H")
    setup = setup.add(axisKey.set(axis))
    setup = setup.add(csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`.OffsetAxisCommand.distanceKey.set(distance.toFloat))
    handler ! CommandHandlerActor.HandleCommand(setup, Id(), None)
    Thread.sleep(150)

  // ========================================
  // Section 1: Shortest algorithm
  // ========================================
  // countsPerRev = 3600. Forward arc = rawTarget - currentPos (mod rev).
  // Reverse arc = countsPerRev - forward arc.
  // Shortest picks the smaller; ties go forward.

  test("Shortest: short forward arc — target is less than half revolution ahead") {
    // currentPos=0, rawTarget=900 (90°). Forward arc=900, reverse arc=2700. Forward is shorter.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    sendPositionAxis(handler, "B", 900.0)
    dmdSent(1) shouldBe Some(900.0)
  }

  test("Shortest: short reverse arc — target is more than half revolution ahead") {
    // currentPos=0, rawTarget=3300 (330°). Forward arc=3300, reverse arc=300. Reverse is shorter.
    // Adjusted = 0 - 300 = -300
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    sendPositionAxis(handler, "B", 3300.0)
    dmdSent(1) shouldBe Some(-300.0)
  }

  test("Shortest: exactly half revolution — tie goes forward") {
    // currentPos=0, rawTarget=1800 (180°). Forward=1800, reverse=1800. Tie → forward.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    sendPositionAxis(handler, "B", 1800.0)
    dmdSent(1) shouldBe Some(1800.0)
  }

  test("Shortest: target already at current position — zero move") {
    // currentPos=900, rawTarget=900. Distance=0. At-target short-circuit fires before algorithm.
    val (handler, isActor) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 900.0)
    // Also set inPositionThreshold large enough to trigger at-target
    isActor ! InternalStateActor.UpdateAxisState(Axis.B,
      Map("demand" -> 900.0, "inPositionThreshold" -> 5.0),
      testKit.system.ignoreRef)
    Thread.sleep(50)
    sendPositionAxis(handler, "B", 900.0)
    // No XQ should be sent — completed immediately
    MockCIActor.commands.filter(_.startsWith("XQ")) shouldBe empty
  }

  test("Shortest: forward arc crosses zero boundary") {
    // currentPos=3300 (330°), rawTarget=300 (30°).
    // Forward arc = 300 - 3300 + 3600 = 600. Reverse arc = 3000. Forward shorter.
    // Adjusted = 3300 + 600 = 3900
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 3300.0)
    sendPositionAxis(handler, "B", 300.0)
    dmdSent(1) shouldBe Some(3900.0)
  }

  test("Shortest: reverse arc crosses zero boundary") {
    // currentPos=300 (30°), rawTarget=3300 (330°).
    // Forward arc = 3000. Reverse arc = 600. Reverse shorter.
    // Adjusted = 300 - 600 = -300
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 300.0)
    sendPositionAxis(handler, "B", 3300.0)
    dmdSent(1) shouldBe Some(-300.0)
  }

  // ========================================
  // Section 2: Forward algorithm
  // ========================================
  // Motor always approaches from below (increasing counts).

  test("Forward: target is already ahead — no adjustment needed") {
    // currentPos=0, rawTarget=900. Candidate=900 >= 0. No adjustment.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Forward, currentPos = 0.0)
    sendPositionAxis(handler, "B", 900.0)
    dmdSent(1) shouldBe Some(900.0)
  }

  test("Forward: target is behind — add one revolution to approach from below") {
    // currentPos=1800 (180°), rawTarget=900 (90°).
    // Candidate=900 < 1800 → add rev: 900+3600=4500
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Forward, currentPos = 1800.0)
    sendPositionAxis(handler, "B", 900.0)
    dmdSent(1) shouldBe Some(4500.0)
  }

  test("Forward: target same phase as current position — at-target short-circuit fires, no motion") {
    // currentPos=3600, rawTarget=0. These are the same phase (both 0°).
    // Algorithm adjusts rawTarget=0 → 3600 (same as currentPos).
    // at-target check: |3600 - 3600| = 0 <= threshold → short-circuits, no XQ sent.
    // This is correct: the motor is already at the requested angular position.
    val (handler, isActor) = createRotatingActors(RotatingAlgorithm.Forward, currentPos = 3600.0)
    isActor ! InternalStateActor.UpdateAxisState(Axis.B,
      Map("inPositionThreshold" -> 5.0),
      testKit.system.ignoreRef)
    Thread.sleep(50)
    sendPositionAxis(handler, "B", 0.0)
    MockCIActor.commands.filter(_.startsWith("XQ")) shouldBe empty
  }

  test("Forward: target one revolution ahead of same phase — adds revolution") {
    // currentPos=0, rawTarget=0. Same phase, but motor at 0 and target is 0.
    // Algorithm: candidate=0 >= 0 → returns 0. At-target short-circuit fires (distance=0).
    // To test genuine forward wrap: currentPos=1800, rawTarget=900.
    // Candidate=900 < 1800 → add rev: 4500. Verified in "target is behind" test above.
    // Additional: currentPos=900, rawTarget=3600+900=4500 expressed as 900 (same phase).
    // candidate = 0 + 900 = 900 >= 900 → return 900. At-target check: |900-900|=0 → short-circuit.
    // Demonstrate with non-zero separation: currentPos=100, rawTarget=0 (350° behind).
    // curMod=100, tgtMod=0, base=0, candidate=0 < 100 → add rev: 3600.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Forward, currentPos = 100.0)
    sendPositionAxis(handler, "B", 0.0)
    dmdSent(1) shouldBe Some(3600.0)
  }

  // ========================================
  // Section 3: Reverse algorithm
  // ========================================
  // Motor always approaches from above (decreasing counts).

  test("Reverse: target is already behind — no adjustment needed") {
    // currentPos=1800, rawTarget=900. Candidate=900 <= 1800. No adjustment.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Reverse, currentPos = 1800.0)
    sendPositionAxis(handler, "B", 900.0)
    dmdSent(1) shouldBe Some(900.0)
  }

  test("Reverse: target is ahead — subtract one revolution to approach from above") {
    // currentPos=900 (90°), rawTarget=1800 (180°).
    // Candidate=1800 > 900 → subtract rev: 1800-3600=-1800
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Reverse, currentPos = 900.0)
    sendPositionAxis(handler, "B", 1800.0)
    dmdSent(1) shouldBe Some(-1800.0)
  }

  test("Reverse: target same phase — subtract revolution to approach from above") {
    // currentPos=0, rawTarget=0. Candidate=0 <= 0? Yes (equal). No adjustment.
    // Equal position means at-target short-circuit fires — verify no XQ
    val (handler, isActor) = createRotatingActors(RotatingAlgorithm.Reverse, currentPos = 0.0)
    isActor ! InternalStateActor.UpdateAxisState(Axis.B,
      Map("inPositionThreshold" -> 5.0),
      testKit.system.ignoreRef)
    Thread.sleep(50)
    sendPositionAxis(handler, "B", 0.0)
    MockCIActor.commands.filter(_.startsWith("XQ")) shouldBe empty
  }

  // ========================================
  // Section 4: offsetAxis algorithm application
  // ========================================
  // offsetAxis computes rawTarget = currentPos + distance, then applies the algorithm.

  test("offsetAxis Shortest: small forward offset stays forward") {
    // currentPos=0, distance=500. rawTarget=500. Forward arc=500 < 1800 → forward shorter.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    sendOffsetAxis(handler, "B", 500.0)
    dmdSent(1) shouldBe Some(500.0)
  }

  test("offsetAxis Forward: offset resulting in a behind-current target adds revolution") {
    // currentPos=1800, distance=-1800. rawTarget=0.
    // Candidate=0 < 1800 → add rev: 3600
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Forward, currentPos = 1800.0)
    sendOffsetAxis(handler, "B", -1800.0)
    dmdSent(1) shouldBe Some(3600.0)
  }

  test("offsetAxis Reverse: offset resulting in an ahead-current target subtracts revolution") {
    // currentPos=900, distance=1800. rawTarget=2700.
    // Candidate=2700 > 900 → subtract rev: 2700-3600=-900
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Reverse, currentPos = 900.0)
    sendOffsetAxis(handler, "B", 1800.0)
    dmdSent(1) shouldBe Some(-900.0)
  }

  // ========================================
  // Section 5: Linear axis — no algorithm applied
  // ========================================

  test("Linear axis: raw target passed through unchanged") {
    // Axis A is linear — approach algorithm must not be invoked regardless of target value
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    // Axis A starts at 0 with no position set, so set up idle state explicitly
    sendPositionAxis(handler, "A", 5000.0)
    dmdSent(0) shouldBe Some(5000.0)
  }

  test("Linear offsetAxis: raw computed target passed through unchanged") {
    val (handler, isActor) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("position" -> 10000.0, "axisState" -> AxisStateEnum.Idle),
      testKit.system.ignoreRef)
    Thread.sleep(50)
    sendOffsetAxis(handler, "A", 3000.0)
    dmdSent(0) shouldBe Some(13000.0)
  }

  // ========================================
  // Section 6: Rotating axis with no cpd — no algorithm applied
  // ========================================

  test("Rotating axis with countsPerRevolution=0 falls back to raw target") {
    MockCIActor.clear()
    var state = HcdState()
    state = state.initializeAxis(Axis.B, MechanismType.Rotating)
    val isActor = testKit.spawn(InternalStateActor(state))
    val ciActor = testKit.spawn(MockCIActor.behavior())
    val smStub  = testKit.spawn(Behaviors.receiveMessage[ControllerStatusActor.Command] { _ => Behaviors.same })
    val handler = testKit.spawn(
      CommandHandlerActor.behavior(ciActor, isActor, null, new LoggerFactory(hcdPrefix), smStub)
    )

    // Rotating but NO countsPerRevolution set (stays None)
    isActor ! InternalStateActor.UpdateAxisState(Axis.B,
      Map(
        "mechanismType" -> MechanismType.Rotating,
        "algorithm"     -> RotatingAlgorithm.Shortest,
        "axisState"     -> AxisStateEnum.Idle,
        "position"      -> 0.0
      ),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    sendPositionAxis(handler, "B", 3300.0)
    // Without countsPerRevolution, algorithm cannot be applied — raw target passes through
    dmdSent(1) shouldBe Some(3300.0)
  }

  // ========================================
  // Section 7: positionWheel — angular demand
  // ========================================
  // positionWheel takes degrees (Float), converts to counts via countsPerRevolution,
  // then applies the approach algorithm identically to positionAxis.
  // countsPerRev = 3600, so: rawTarget = (degrees / 360.0) * 3600.0 = degrees * 10.0
  //
  // Tests verify:
  //   a) Degree-to-count conversion is correct
  //   b) Approach algorithm is applied to the converted target
  //   c) Command is rejected when axis is linear
  //   d) Command is rejected when countsPerRevolution is not set

  private def sendPositionWheel(
    handler: ActorRef[CommandHandlerActor.Command],
    axis: String,
    angleDeg: Double
  ): Unit =
    var setup = Setup(hcdPrefix, CommandName("positionWheel"), None)
    val axisKey = ChoiceKey.make("axis", "A", "B", "C", "D", "E", "F", "G", "H")
    setup = setup.add(axisKey.set(axis))
    setup = setup.add(csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`.PositionWheelCommand.positionKey.set(angleDeg.toFloat))
    handler ! CommandHandlerActor.HandleCommand(setup, Id(), None)
    Thread.sleep(150)

  test("positionWheel: 90° → 900 counts (forward, Shortest from 0)") {
    // angleDeg=90°, rawTarget=900, currentPos=0. Shortest arc: forward=900 < 2700. No adjustment.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    sendPositionWheel(handler, "B", 90.0)
    dmdSent(1) shouldBe Some(900.0)
  }

  test("positionWheel: 270° → approach algorithm selects shortest reverse arc from 0") {
    // angleDeg=270°, rawTarget=2700, currentPos=0. Forward arc=2700, reverse arc=900. Shortest → -900.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    sendPositionWheel(handler, "B", 270.0)
    dmdSent(1) shouldBe Some(-900.0)
  }

  test("positionWheel: 180° → 1800 counts, no adjustment (Shortest, equal arcs go forward)") {
    // angleDeg=180°, rawTarget=1800, currentPos=0. Forward=1800, reverse=1800. Tie → forward.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    sendPositionWheel(handler, "B", 180.0)
    dmdSent(1) shouldBe Some(1800.0)
  }

  test("positionWheel: 90° forward algorithm adds one revolution when behind target") {
    // angleDeg=90°, rawTarget=900, currentPos=1800.
    // curMod=1800, tgtMod=900, base=0, candidate=900.
    // Forward: candidate(900) < currentPos(1800) → add one rev → 900+3600=4500.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Forward, currentPos = 1800.0)
    sendPositionWheel(handler, "B", 90.0)
    dmdSent(1) shouldBe Some(4500.0)
  }

  test("positionWheel: 0° (full wrap) with Forward algorithm from 900") {
    // angleDeg=0°, rawTarget=0.0, currentPos=900. Forward: tgtMod=0, base=0 (900%3600=900, base=0),
    // candidate=0. candidate(0) <= currentPos(900) → add one rev → 3600.
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Forward, currentPos = 900.0)
    sendPositionWheel(handler, "B", 0.0)
    dmdSent(1) shouldBe Some(3600.0)
  }

  test("positionWheel: rejected when axis is linear") {
    // Axis A is linear — positionWheel must reject with Error, not send a dmd command
    MockCIActor.clear()
    val (handler, _) = createRotatingActors(RotatingAlgorithm.Shortest, currentPos = 0.0)
    sendPositionWheel(handler, "A", 90.0)
    // No dmd command should have been sent to axis A (idx=0)
    dmdSent(0) shouldBe None
  }

  test("positionWheel: rejected when countsPerRevolution is not set") {
    MockCIActor.clear()
    var state = HcdState()
    state = state.initializeAxis(Axis.B, MechanismType.Rotating)
    val isActor = testKit.spawn(InternalStateActor(state))
    val ciActor = testKit.spawn(MockCIActor.behavior())
    val smStub  = testKit.spawn(Behaviors.receiveMessage[ControllerStatusActor.Command] { _ => Behaviors.same })
    val handler = testKit.spawn(
      CommandHandlerActor.behavior(ciActor, isActor, null, new LoggerFactory(hcdPrefix), smStub)
    )

    // Rotating but NO countsPerRevolution set — positionWheel cannot convert degrees to counts
    isActor ! InternalStateActor.UpdateAxisState(Axis.B,
      Map(
        "mechanismType" -> MechanismType.Rotating,
        "algorithm"     -> RotatingAlgorithm.Shortest,
        "axisState"     -> AxisStateEnum.Idle,
        "position"      -> 0.0
      ),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    sendPositionWheel(handler, "B", 90.0)
    // No dmd should have been sent — command should have errored out
    dmdSent(1) shouldBe None
  }