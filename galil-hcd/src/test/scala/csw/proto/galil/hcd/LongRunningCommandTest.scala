package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import csw.command.client.CommandResponseManager
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.{CommandName, Setup}
import csw.params.core.generics.KeyType.ChoiceKey
import csw.params.core.models.{Id, ObsId}
import csw.prefix.models.Prefix
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.concurrent.duration._

/**
 * Integration tests for long-running commands.
 *
 * Tests the full actor chain without a real Galil controller:
 *   CommandHandlerActor → MockCIActor → InternalStateActor → CommandWatcherActor
 *
 * The MockCIActor responds to SendCommand messages with success (":") responses,
 * simulating the controller accepting commands. After the XQ command is sent,
 * the test simulates ControllerStatusActor-like QR updates to drive the CommandWatcher
 * to completion.
 *
 * Section 1: positionAxis — full lifecycle
 * Section 2: homeAxis — full lifecycle
 * Section 3: stopAxis — interrupts existing command
 * Section 4: offsetAxis — relative motion
 * Section 5: Error scenarios
 */
class LongRunningCommandTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()
  private val hcdPrefix = Prefix("APS.ICS.HCD.GalilMotion")

  override def afterAll(): Unit =
    testKit.shutdownTestKit()

  // ========================================
  // Mock Controller Interface Actor
  // ========================================

  /**
   * Records all commands sent to the controller and responds with success.
   * Also captures the commands for test verification.
   */
  object MockCIActor:
    val commandLog = new ConcurrentLinkedQueue[String]()
    // Configurable: simulate thread being active after XQ (default: true for most commands)
    @volatile var simulateThreadActive: Boolean = true
    // Track next thread to allocate (simulates pool)
    private val nextThread = new java.util.concurrent.atomic.AtomicInteger(1)

    def behavior(): Behavior[GalilCommandMessage] =
      Behaviors.receiveMessage {
        case GalilCommandMessage.SendCommand(cmdString, replyTo) =>
          commandLog.add(cmdString)
          replyTo ! GalilCommandMessage.SendCommandResult(":")
          Behaviors.same
        case GalilCommandMessage.ExecuteProgram(label, replyTo, preCommands) =>
          preCommands.foreach(cmd => commandLog.add(cmd))
          val thread = nextThread.getAndIncrement()
          commandLog.add(s"XQ #$label,$thread")
          replyTo ! GalilCommandMessage.ExecuteProgramResult(
            thread = thread,
            threadWasActive = simulateThreadActive, error = None)
          Behaviors.same
        case GalilCommandMessage.HaltExecution(thread, axis, replyTo) =>
          // Simulate successful halt — records HX/ST commands and replies immediately
          commandLog.add(s"HX $thread")
          commandLog.add(s"ST ${axis.char}")
          replyTo ! GalilCommandMessage.HaltExecutionResult(success = true, error = None)
          Behaviors.same
        case _ =>
          Behaviors.same
      }

    def clear(): Unit =
      commandLog.clear()
      simulateThreadActive = true
      nextThread.set(1)

    def commands: List[String] =
      import scala.jdk.CollectionConverters._
      commandLog.asScala.toList

  // ========================================
  // Test infrastructure
  // ========================================

  /**
   * Create a CommandHandlerActor wired to mock CI and real IS actors.
   * Returns (handler, isActor, mockCIActor).
   *
   * Since we can't construct a real CRM outside the framework, we pass null.
   * The CommandWatcherActor handles null CRM gracefully via reportResult().
   * For verifying command completion, we use IS actor state queries
   * and CommandWatcher's resultReporter (if needed).
   */
  private def createTestActors(
    axes: Seq[Axis] = Seq(Axis.A)
  ): (ActorRef[CommandHandlerActor.Command], ActorRef[InternalStateActor.Command], ActorRef[GalilCommandMessage]) =
    MockCIActor.clear()

    var state = HcdState()
    axes.foreach(a => state = state.initializeAxis(a))

    val isActor = testKit.spawn(InternalStateActor(state))
    val ciActor = testKit.spawn(MockCIActor.behavior())
    val loggerFactory = new LoggerFactory(hcdPrefix)

    // Stub ControllerStatusActor — absorbs SetPollingRate messages without doing anything
    val smStub = testKit.spawn(Behaviors.receiveMessage[ControllerStatusActor.Command] { _ => Behaviors.same })

    val handler = testKit.spawn(
      CommandHandlerActor.behavior(ciActor, isActor, null, loggerFactory, smStub)
    )

    (handler, isActor, ciActor)

  /**
   * Build a Setup with the given command name and axis.
   */
  private def makeSetup(commandName: String, axis: String, extraParams: Map[String, Any] = Map.empty): Setup =
    var setup = Setup(hcdPrefix, CommandName(commandName), None)

    // All long-running commands have an axis parameter
    val axisKey = ChoiceKey.make("axis", "A", "B", "C", "D", "E", "F", "G", "H")
    setup = setup.add(axisKey.set(axis))

    // Add extra parameters based on type
    extraParams.foreach {
      case ("target", v: Float) =>
        setup = setup.add(PositionAxisCommand.targetKey.set(v))
      case ("target", v: Double) =>
        setup = setup.add(PositionAxisCommand.targetKey.set(v.toFloat))
      case ("distance", v: Float) =>
        setup = setup.add(OffsetAxisCommand.distanceKey.set(v))
      case ("distance", v: Double) =>
        setup = setup.add(OffsetAxisCommand.distanceKey.set(v.toFloat))
      case ("position", v: Int) =>
        // Int → SelectWheelCommand.positionKey (filter-wheel slot index)
        setup = setup.add(SelectWheelCommand.positionKey.set(v))
      case ("position", v: Float) =>
        // Float → TrackAxisCommand.positionKey (degrees rotating / counts linear)
        setup = setup.add(TrackAxisCommand.positionKey.set(v))
      case ("position", v: Double) =>
        setup = setup.add(TrackAxisCommand.positionKey.set(v.toFloat))
      case ("rate", v: Float) =>
        setup = setup.add(TrackAxisCommand.rateKey.set(v))
      case ("rate", v: Double) =>
        setup = setup.add(TrackAxisCommand.rateKey.set(v.toFloat))
      case ("validTime", v: csw.time.core.models.TAITime) =>
        setup = setup.add(TrackAxisCommand.validTimeKey.set(v))
      case (key, value) =>
        fail(s"Unknown extra parameter: $key=$value")
    }
    setup

  /**
   * Simulate ControllerStatusActor QR updates for a completed position move.
   * First simulates the motor being active (as SM would see from QR),
   * then simulates completion.
   */
  private def simulateMotionComplete(
    isActor: ActorRef[InternalStateActor.Command],
    axis: Axis,
    finalPosition: Double = 0.0,
    delay: FiniteDuration = 50.millis
  ): Unit =
    Thread.sleep(delay.toMillis)

    // SM sees thread active, motor moving (from QR poll)
    val thread = axis.index + 1
    isActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeThread" -> thread, "moving" -> true),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    // SM sees position reaching target (triggers inPosition mirroring)
    isActor ! InternalStateActor.UpdateAxisState(axis,
      Map("position" -> finalPosition),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    // SM sees thread released, not moving (from QR poll)
    isActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeThread" -> 0, "moving" -> false),
      testKit.system.ignoreRef)

  private def simulateHomeComplete(
    isActor: ActorRef[InternalStateActor.Command],
    axis: Axis,
    delay: FiniteDuration = 50.millis
  ): Unit =
    Thread.sleep(delay.toMillis)

    // SM sees thread active, motor moving
    val thread = axis.index + 1
    isActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeThread" -> thread, "moving" -> true),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    // SM sees thread released, not moving (home complete)
    isActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeThread" -> 0, "moving" -> false),
      testKit.system.ignoreRef)

  // ========================================
  // Section 1: positionAxis
  // ========================================

  test("positionAxis should send correct Galil commands") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    val setup = makeSetup("positionAxis", "A", Map("target" -> 50000.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)

    // Allow command handler to process
    Thread.sleep(200)

    // Verify commands sent to controller
    val cmds = MockCIActor.commands
    cmds should contain("dmd[0]=50000.0")
    cmds should contain("XQ #MoveA,1")

    // Verify dmd was set before XQ
    val dmdIdx = cmds.indexOf("dmd[0]=50000.0")
    val xqIdx = cmds.indexOf("XQ #MoveA,1")
    dmdIdx should be < xqIdx
  }

  test("positionAxis should update AxisState demand and transition to Moving") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    val setup = makeSetup("positionAxis", "A", Map("target" -> 50000.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Check AxisState
    val probe = testKit.createTestProbe[Option[AxisState]]()
    isActor ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    val axisState = probe.receiveMessage().get
    axisState.demand should be(50000.0)
    axisState.axisState should be(AxisStateEnum.Moving)
  }

  test("positionAxis should set activeCommand in AxisCmdState") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    val setup = makeSetup("positionAxis", "A", Map("target" -> 50000.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val cmdState = probe.receiveMessage().get
    cmdState.activeCommand should be(Some(ActiveCommand.Move))
    cmdState.axisErrorMsg should be("")
  }

  test("positionAxis should complete when motion reaches target") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    // Set inPositionThreshold so inPosition will trigger
    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("inPositionThreshold" -> 10.0),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    val setup = makeSetup("positionAxis", "A", Map("target" -> 50000.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Simulate motion complete — position reaches target
    simulateMotionComplete(isActor, Axis.A, finalPosition = 50000.0)
    Thread.sleep(300)

    // Verify activeCommand was cleared (watcher completed and cleaned up)
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val cmdState = probe.receiveMessage().get
    cmdState.activeCommand should be(None)
  }

  test("positionAxis for axis B should use correct index and thread") {
    val (handler, isActor, _) = createTestActors(axes = Seq(Axis.A, Axis.B))
    val runId = Id()

    setAxisIdle(isActor, Axis.B)
    val setup = makeSetup("positionAxis", "B", Map("target" -> 25000.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    val cmds = MockCIActor.commands
    cmds should contain("dmd[1]=25000.0")   // B index = 1
    cmds.exists(_.startsWith("XQ #MoveB,")) should be(true)  // Thread is pool-allocated
  }

  // ========================================
  // Section 2: homeAxis
  // ========================================

  test("homeAxis should send XQ #HomeX command") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    val setup = makeSetup("homeAxis", "A")
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    MockCIActor.commands should contain("XQ #HomeA,1")
  }

  test("homeAxis should transition axisState to Homing then complete to Idle") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    val setup = makeSetup("homeAxis", "A")
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Simulate ControllerStatusActor: home complete — thread released, not moving
    simulateHomeComplete(isActor, Axis.A)
    Thread.sleep(300)

    val probe = testKit.createTestProbe[Option[AxisState]]()
    isActor ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    val axisState = probe.receiveMessage().get
    axisState.axisState should be(AxisStateEnum.Idle)
  }

  test("homeAxis should set activeCommand to Home then clear on completion") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    val setup = makeSetup("homeAxis", "A")
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Verify activeCommand is Home while command is active
    val probe1 = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe1.ref)
    probe1.receiveMessage().get.activeCommand should be(Some(ActiveCommand.Home))

    // Simulate ControllerStatusActor: home complete
    simulateHomeComplete(isActor, Axis.A)
    Thread.sleep(300)

    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val cmdState = probe.receiveMessage().get
    cmdState.activeCommand should be(None)
  }

  test("homeAxis should complete when thread released and motion stopped") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    val setup = makeSetup("homeAxis", "A")
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Simulate ControllerStatusActor: QR poll sees thread 1 active, motor moving
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("activeThread" -> 1, "moving" -> true),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    // Simulate ControllerStatusActor: QR poll sees home complete
    simulateHomeComplete(isActor, Axis.A)
    Thread.sleep(300)

    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val cmdState = probe.receiveMessage().get
    cmdState.activeCommand should be(None)
  }

  // ========================================

  /**
   * Axis state prerequisites for unit tests (SDD Figure 4-2).
   * Axes start in Lost after initializeAxis(). Commands that require
   * Idle or Tracking state must call the appropriate helper first.
   */

  /** Put axis into Idle state (required for positionAxis/offsetAxis/selectWheel/trackAxis). */
  private def setAxisIdle(isActor: ActorRef[InternalStateActor.Command], axis: Axis): Unit =
    isActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Idle),
      testKit.system.ignoreRef)
    Thread.sleep(50) // allow IS message to be processed

  /** Put axis into Tracking state so stopAxis is permitted (SDD Figure 4-2). */
  private def setAxisTracking(isActor: ActorRef[InternalStateActor.Command], axis: Axis): Unit =
    isActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Tracking),
      testKit.system.ignoreRef)
    Thread.sleep(50) // allow IS message to be processed

  // Section 3: stopAxis
  // ========================================
  // stopAxis is valid from any state. From Moving or Homing states, checkAndInterrupt
  // (called upstream in the command dispatch path) halts the active thread and stops
  // the motor before stopAxis runs. stopAxis then simply executes #StopX.

  test("stopAxis should send XQ #StopX command") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisTracking(isActor, Axis.A)
    val setup = makeSetup("stopAxis", "A")
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    MockCIActor.commands should contain("XQ #StopA,1")
  }

  test("stopAxis from Tracking should NOT signal commandHalted — no embedded thread to interrupt under PVT") {
    val (handler, isActor, _) = createTestActors()
    setAxisTracking(isActor, Axis.A)

    // Subscribe to CmdState changes to observe any halted flag.
    // Note: SubscribeCmdState does NOT deliver an initial snapshot (unlike Subscribe),
    // so no drain is needed.
    val cmdProbe = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    isActor ! InternalStateActor.SubscribeCmdState(Axis.A, cmdProbe.ref)

    val runId = Id()
    val setup = makeSetup("stopAxis", "A")
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)

    // Under PVT, Tracking has no embedded thread (no #TrackX program — segments run
    // FIFO-driven on the controller).  handleStopAxis skips checkAndInterrupt for
    // Tracking entirely and goes straight to #StopX, so commandHalted is never set.
    // checkAndInterrupt is only invoked for Moving and Homing axes.
    var sawHalted = false
    val deadline = System.currentTimeMillis() + 500
    while (System.currentTimeMillis() < deadline) {
      try {
        val notification = cmdProbe.receiveMessage(100.millis)
        if (notification.cmdState.commandHalted) sawHalted = true
      } catch {
        case _: AssertionError => // timeout, continue
      }
    }
    sawHalted should be(false)
  }

  test("stopAxis should complete when thread released and not moving") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisTracking(isActor, Axis.A)
    val setup = makeSetup("stopAxis", "A")
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Simulate ControllerStatusActor: QR poll sees thread active, motor decelerating
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("activeThread" -> 1, "moving" -> true),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    // Simulate ControllerStatusActor: QR poll sees stop complete
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("activeThread" -> 0, "moving" -> false),
      testKit.system.ignoreRef)
    Thread.sleep(300)

    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val cmdState = probe.receiveMessage().get
    cmdState.activeCommand should be(None)
  }

  // ========================================
  // Section 4: offsetAxis
  // ========================================

  test("offsetAxis should compute absolute target from current position + distance") {
    val (handler, isActor, _) = createTestActors()

    setAxisIdle(isActor, Axis.A)
    // Set current position to 10000
    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("position" -> 10000.0),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    val runId = Id()
    val setup = makeSetup("offsetAxis", "A", Map("distance" -> 5000.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Verify: dmd should be 10000 + 5000 = 15000
    val cmds = MockCIActor.commands
    cmds should contain("dmd[0]=15000.0")
    cmds should contain("XQ #MoveA,1")

    // Verify demand set in AxisState
    val probe = testKit.createTestProbe[Option[AxisState]]()
    isActor ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    val axisState = probe.receiveMessage().get
    axisState.demand should be(15000.0)
  }

  test("offsetAxis should support negative distances") {
    val (handler, isActor, _) = createTestActors()

    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("axisState" -> AxisStateEnum.Idle, "position" -> 20000.0),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    val runId = Id()
    val setup = makeSetup("offsetAxis", "A", Map("distance" -> -3000.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // 20000 + (-3000) = 17000
    MockCIActor.commands should contain("dmd[0]=17000.0")
  }

  // ========================================
  // Section 5: Error scenarios and edge cases
  // ========================================

  test("positionAxis should complete immediately when already at target") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    // Pre-set axis A to Idle and position to 5000 with appropriate threshold
    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("axisState" -> AxisStateEnum.Idle, "position" -> 5000.0, "demand" -> 5000.0, "inPositionThreshold" -> 1.0),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    // Request move to where we already are
    val setup = makeSetup("positionAxis", "A", Map("target" -> 5000.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Should NOT have sent any motion commands to controller
    val cmds = MockCIActor.commands
    cmds should not contain ("XQ #MoveA,1")

    // AxisState should NOT be Moving (no motion was initiated)
    val stateProbe = testKit.createTestProbe[Option[AxisState]]()
    isActor ! InternalStateActor.GetAxisState(Axis.A, stateProbe.ref)
    val state = stateProbe.receiveMessage().get
    state.axisState should not be AxisStateEnum.Moving
  }

  test("offsetAxis should complete immediately when distance is zero") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    // Pre-set axis A to Idle with position
    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("axisState" -> AxisStateEnum.Idle, "position" -> 3000.0, "inPositionThreshold" -> 1.0),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    // Request zero-distance offset
    val setup = makeSetup("offsetAxis", "A", Map("distance" -> 0.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Should NOT have sent motion commands
    val cmds = MockCIActor.commands
    cmds should not contain ("XQ #MoveA,1")
  }

  test("positionAxis watcher should detect axis error") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    val setup = makeSetup("positionAxis", "A", Map("target" -> 50000.0))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Simulate ControllerStatusActor: QR poll sees thread active, motor moving
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("activeThread" -> 1, "moving" -> true),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    // Simulate: controller reports an error on the axis
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("axisErrorMsg" -> "forward limit reached"),
      testKit.system.ignoreRef)
    Thread.sleep(300)

    // Watcher should have terminated and cleared activeCommand
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val cmdState = probe.receiveMessage().get
    cmdState.activeCommand should be(None)
  }

  test("Multiple commands on different axes should be independent") {
    val (handler, isActor, _) = createTestActors(axes = Seq(Axis.A, Axis.B))

    // Set Idle state and thresholds for both axes
    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("axisState" -> AxisStateEnum.Idle, "inPositionThreshold" -> 10.0), testKit.system.ignoreRef)
    isActor ! InternalStateActor.UpdateAxisState(Axis.B,
      Map("axisState" -> AxisStateEnum.Idle, "inPositionThreshold" -> 10.0), testKit.system.ignoreRef)
    Thread.sleep(50)

    val runIdA = Id()
    val runIdB = Id()

    // Start moves on both axes
    handler ! CommandHandlerActor.HandleCommand(
      makeSetup("positionAxis", "A", Map("target" -> 10000.0)), runIdA, None)
    handler ! CommandHandlerActor.HandleCommand(
      makeSetup("positionAxis", "B", Map("target" -> 20000.0)), runIdB, None)
    Thread.sleep(300)

    // Verify both axes have active commands
    val probeA1 = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probeA1.ref)
    probeA1.receiveMessage().get.activeCommand should be(Some(ActiveCommand.Move))

    val probeB1 = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.B, probeB1.ref)
    probeB1.receiveMessage().get.activeCommand should be(Some(ActiveCommand.Move))

    // Complete axis A only
    simulateMotionComplete(isActor, Axis.A, finalPosition = 10000.0)
    Thread.sleep(300)

    // Axis A should be cleared, Axis B should still be active
    val probeA2 = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probeA2.ref)
    probeA2.receiveMessage().get.activeCommand should be(None)

    val probeB2 = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.B, probeB2.ref)
    probeB2.receiveMessage().get.activeCommand should be(Some(ActiveCommand.Move))

    // Now complete axis B
    simulateMotionComplete(isActor, Axis.B, finalPosition = 20000.0)
    Thread.sleep(300)

    val probeB3 = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.B, probeB3.ref)
    probeB3.receiveMessage().get.activeCommand should be(None)

    // Verify all expected Galil commands were sent
    val cmds = MockCIActor.commands
    cmds should contain("dmd[0]=10000.0")
    cmds.exists(_.startsWith("XQ #MoveA,")) should be(true)
    cmds should contain("dmd[1]=20000.0")
    cmds.exists(_.startsWith("XQ #MoveB,")) should be(true)
  }

  // ========================================
  // Section 6: selectWheel
  // ========================================

  private def simulateSelectComplete(
    isActor: ActorRef[InternalStateActor.Command],
    axis: Axis,
    delay: FiniteDuration = 50.millis
  ): Unit =
    Thread.sleep(delay.toMillis)

    // SM sees thread active, motor moving (embedded program executing)
    val thread = axis.index + 1
    isActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeThread" -> thread, "moving" -> true),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    // SM sees thread released, not moving (select complete)
    // selectWheel mask: activeThread==0, axisErrorMsg=="", moving==false (no inPosition)
    isActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeThread" -> 0, "moving" -> false),
      testKit.system.ignoreRef)

  test("selectWheel should send correct Galil commands") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    val setup = makeSetup("selectWheel", "A", Map("position" -> 3))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    val cmds = MockCIActor.commands
    cmds should contain("dmd[0]=3")
    cmds.exists(_.startsWith("XQ #SelectA,")) should be(true)

    // Verify dmd was set before XQ
    val dmdIdx = cmds.indexOf("dmd[0]=3")
    val xqIdx = cmds.indexWhere(_.startsWith("XQ #SelectA,"))
    dmdIdx should be < xqIdx
  }

  test("selectWheel should transition axisState to Moving") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    val setup = makeSetup("selectWheel", "A", Map("position" -> 5))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    val probe = testKit.createTestProbe[Option[AxisState]]()
    isActor ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    val axisState = probe.receiveMessage().get
    axisState.axisState should be(AxisStateEnum.Moving)
  }

  test("selectWheel should set activeCommand to Select") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    val setup = makeSetup("selectWheel", "A", Map("position" -> 2))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val cmdState = probe.receiveMessage().get
    cmdState.activeCommand should be(Some(ActiveCommand.Select))
  }

  test("selectWheel should complete when motion finishes") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    val setup = makeSetup("selectWheel", "A", Map("position" -> 4))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    // Simulate: select complete
    simulateSelectComplete(isActor, Axis.A)
    Thread.sleep(300)

    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val cmdState = probe.receiveMessage().get
    cmdState.activeCommand should be(None)

    // Verify axisState transitioned to Idle
    val stateProbe = testKit.createTestProbe[Option[AxisState]]()
    isActor ! InternalStateActor.GetAxisState(Axis.A, stateProbe.ref)
    stateProbe.receiveMessage().get.axisState should be(AxisStateEnum.Idle)
  }

  test("selectWheel on axis B should use correct index and thread") {
    val (handler, isActor, _) = createTestActors(axes = Seq(Axis.A, Axis.B))
    val runId = Id()

    setAxisIdle(isActor, Axis.B)
    val setup = makeSetup("selectWheel", "B", Map("position" -> 7))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    val cmds = MockCIActor.commands
    cmds should contain("dmd[1]=7")
    cmds.exists(_.startsWith("XQ #SelectB,")) should be(true)  // Thread is pool-allocated
  }

  // ========================================
  // Section 7: trackAxis
  // ========================================

  // ============================================================================
  // trackAxis tests
  //
  // Tests for the new ICD (position/rate/validTime).  This is currently a thin
  // smoke test for the state-machine transition; the broader PVT behaviors
  // (PVA wire format, T_samples math, FIFO acceptance Completion semantics,
  // underrun preemption, multi-segment ledger evolution, deg→counts conversion
  // on rotating axes) deserve dedicated coverage and don't have it yet.
  // ============================================================================

  test("trackAxis should transition axisState to Tracking") {
    val (handler, isActor, _) = createTestActors()
    val runId = Id()

    setAxisIdle(isActor, Axis.A)
    // The handleTrackAxis PVT path requires HcdState.controllerSamplePeriodMicros > 0
    // (this is read from the controller's `MG _TM` during HCD init in production;
    // the mock-IS test fixture defaults to 0).  Without this seed the handler
    // Errors with "controller sample period not yet known" before touching axisState.
    isActor ! InternalStateActor.UpdateHcdState(
      Map("controllerSamplePeriodMicros" -> 1000),  // standard 1 kHz servo loop
      testKit.system.ignoreRef
    )
    // handleTrackAxis (S65) also requires a configured per-axis velocity envelope:
    // maxSpeed = None ⇒ reject ("cannot start tracking — maxSpeed not configured").
    // The guard checks both the requested rate (10 here) and the implied average
    // segment velocity (ΔP=500 counts over ~2 s ≈ 250 counts/sec from the default
    // position of 0); 20000 clears both.  In production maxSpeed is set via
    // configAxis before any trackAxis is accepted.
    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("maxSpeed" -> 20000.0),
      testKit.system.ignoreRef)
    Thread.sleep(50)

    val vt = csw.time.core.models.TAITime(java.time.Instant.now().plusSeconds(2))
    val setup = makeSetup("trackAxis", "A",
      Map("position" -> 500.0, "rate" -> 10.0, "validTime" -> vt))
    handler ! CommandHandlerActor.HandleCommand(setup, runId, None)
    Thread.sleep(200)

    val probe = testKit.createTestProbe[Option[AxisState]]()
    isActor ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    val axisState = probe.receiveMessage().get
    axisState.axisState should be(AxisStateEnum.Tracking)
    axisState.demand should be(500.0)
  }