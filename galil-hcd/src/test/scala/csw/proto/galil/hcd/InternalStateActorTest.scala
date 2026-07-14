package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import scala.concurrent.duration._

/**
 * Tests for Internal State Actor and State Model.
 * 
 * Validates:
 * - State model update logic (AxisState + AxisCmdState)
 * - InPosition calculation and mirroring to AxisCmdState
 * - Actor state management for both operational and command channels
 * - Query operations
 * - Subscription mechanism (StateChanged for CSP, CmdStateChanged for CommandWatchers)
 * - Named switch fields
 * - Thread status tracking
 */
class InternalStateActorTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:
  
  private val testKit = ActorTestKit()
  
  override def afterAll(): Unit =
    testKit.shutdownTestKit()

  /**
   * Drain the initial snapshot delivered by IS Subscribe handler.
   * Subscribe sends an immediate StateChanged(currentState, Set.empty, Set.empty)
   * so late subscribers don't miss the initial state. Tests that subscribe and
   * then assert on a *subsequent* change must drain this snapshot first.
   */
  private def drainSnapshot(probe: org.apache.pekko.actor.testkit.typed.scaladsl.TestProbe[InternalStateActor.StateChanged]): Unit =
    probe.receiveMessage(500.millis)
    ()

  // ========================================
  // State Model Tests — AxisState
  // ========================================
  
  test("AxisState should calculate inPosition correctly") {
    val axis = AxisState(
      position = 100.0,
      demand = 100.0,
      inPositionThreshold = 0.1
    )
    
    axis.calculateInPosition should be (true)
    
    val outOfPosition = axis.copy(position = 100.5)
    outOfPosition.calculateInPosition should be (false)
    
    val justInPosition = axis.copy(position = 100.099)
    justInPosition.calculateInPosition should be (true)
  }
  
  test("AxisState.update should modify fields correctly") {
    val initial = AxisState()
    
    val updated = initial.update(Map(
      "position" -> 123.45,
      "velocity" -> 10.0,
      "axisState" -> AxisStateEnum.Moving
    ))
    
    updated.position should be (123.45)
    updated.velocity should be (10.0)
    updated.axisState should be (AxisStateEnum.Moving)
  }
  
  test("AxisState.update should recalculate inPosition when position changes") {
    val initial = AxisState(
      position = 100.0,
      demand = 200.0,
      inPositionThreshold = 0.1
    )
    
    initial.inPosition should be (false)
    
    // Update position to be in range
    val updated = initial.update(Map("position" -> 200.05))
    updated.inPosition should be (true)
  }
  
  test("AxisState should have correct defaults") {
    val state = AxisState()
    
    state.axisState shouldBe AxisStateEnum.Lost
    state.axisError shouldBe ""
    state.position shouldBe 0.0
    state.velocity shouldBe 0.0
    state.inPosition shouldBe false
    // Named switch defaults
    state.forwardLimit shouldBe false
    state.reverseLimit shouldBe false
    state.homeSwitch shouldBe false
    state.isStepper shouldBe false
    state.negativeDirection shouldBe false
    state.motorOff shouldBe true   // Default: amplifier off
    state.mechanismType shouldBe MechanismType.Linear
    // Motion config defaults (None until configAxis or controller query)
    state.maxSpeed shouldBe None
    state.acceleration shouldBe None
    state.deceleration shouldBe None
    state.indexOffset shouldBe None
    state.indexSpeed shouldBe None
    state.motionDelay shouldBe None
  }

  test("AxisState.update should handle named switch fields") {
    val updated = AxisState().update(Map(
      "forwardLimit" -> true,
      "reverseLimit" -> true,
      "homeSwitch" -> true,
      "motorOff" -> false
    ))
    
    updated.forwardLimit shouldBe true
    updated.reverseLimit shouldBe true
    updated.homeSwitch shouldBe true
    updated.motorOff shouldBe false
    // Unchanged fields retain defaults
    updated.isStepper shouldBe false
    updated.negativeDirection shouldBe false
  }

  test("AxisState should have correct motion config defaults (all None)") {
    val state = AxisState()

    state.maxSpeed shouldBe None
    state.acceleration shouldBe None
    state.deceleration shouldBe None
    state.indexOffset shouldBe None
    state.indexSpeed shouldBe None
    state.motionDelay shouldBe None
  }

  test("AxisState.update should handle motion config fields") {
    val updated = AxisState().update(Map(
      "maxSpeed" -> 50000.0,
      "acceleration" -> 100000.0,
      "deceleration" -> 100000.0,
      "indexOffset" -> 10.0,
      "indexSpeed" -> 5000.0,
      "motionDelay" -> 100.0
    ))

    updated.maxSpeed shouldBe Some(50000.0)
    updated.acceleration shouldBe Some(100000.0)
    updated.deceleration shouldBe Some(100000.0)
    updated.indexOffset shouldBe Some(10.0)
    updated.indexSpeed shouldBe Some(5000.0)
    updated.motionDelay shouldBe Some(100.0)
  }

  test("AxisState.update should allow partial motion config updates") {
    // Only set maxSpeed — others remain None
    val partial = AxisState().update(Map("maxSpeed" -> 25000.0))
    partial.maxSpeed shouldBe Some(25000.0)
    partial.acceleration shouldBe None
    partial.deceleration shouldBe None

    // Update existing — maxSpeed changes, acceleration added
    val updated = partial.update(Map(
      "maxSpeed" -> 30000.0,
      "acceleration" -> 80000.0
    ))
    updated.maxSpeed shouldBe Some(30000.0)
    updated.acceleration shouldBe Some(80000.0)
    updated.deceleration shouldBe None
  }

  // ========================================
  // Computed Field Tests — motorPosition, motorDemand, angularPosition
  // ========================================

  test("motorPosition should return raw position for linear axes (no countsPerRevolution)") {
    val state = AxisState(position = 800.0)  // linear: no cpr set
    state.motorPosition shouldBe 800.0
  }

  test("motorPosition should wrap accumulated counts into [0, cpr) for rotating axes") {
    val cpr = 400.0
    val state = AxisState(position = 0.0, countsPerRevolution = Some(cpr))

    // Exact multiples of cpr → 0
    state.copy(position = 0.0).motorPosition   shouldBe 0.0
    state.copy(position = 400.0).motorPosition shouldBe 0.0
    state.copy(position = 800.0).motorPosition shouldBe 0.0

    // Mid-revolution positions
    state.copy(position = 100.0).motorPosition shouldBe 100.0
    state.copy(position = 500.0).motorPosition shouldBe 100.0  // 500 % 400 = 100
    state.copy(position = 750.0).motorPosition shouldBe 350.0  // 750 % 400 = 350

    // Negative positions (reverse from home)
    state.copy(position = -100.0).motorPosition shouldBe 300.0  // -100 + 400
    state.copy(position = -400.0).motorPosition shouldBe 0.0    // full negative rev
    state.copy(position = -500.0).motorPosition shouldBe 300.0  // -500 % 400 = -100 → +300
  }

  test("motorPosition should return raw position when countsPerRevolution is zero (uninitialized)") {
    // cpr=0.0 is the sentinel for \"not yet configured\" — must not divide by zero
    val state = AxisState(position = 123.0, countsPerRevolution = Some(0.0))
    state.motorPosition shouldBe 123.0
  }

  test("motorDemand should wrap accumulated demand into [0, cpr) for rotating axes") {
    val cpr = 400.0
    val state = AxisState(demand = 0.0, countsPerRevolution = Some(cpr))

    state.copy(demand = 800.0).motorDemand  shouldBe 0.0    // two full wraps
    state.copy(demand = 500.0).motorDemand  shouldBe 100.0
    state.copy(demand = -100.0).motorDemand shouldBe 300.0
  }

  test("motorDemand should return raw demand for linear axes") {
    val state = AxisState(demand = 800.0)
    state.motorDemand shouldBe 800.0
  }

  test("motorPosition and motorDemand should be consistent after approach algorithm wraps target") {
    // Simulate: user commands position 0 on a 400-count axis that has already done
    // two full revolutions. applyApproachAlgorithm resolves rawTarget=0 to adjusted=800
    // (Shortest path: already at 800, distance to next 0 is 0). Demand is stored as 800.
    // Both motorPosition and motorDemand should display as 0.
    val cpr = 400.0
    val state = AxisState(
      position = 800.0,
      demand   = 800.0,
      countsPerRevolution = Some(cpr)
    )
    state.motorPosition shouldBe 0.0
    state.motorDemand   shouldBe 0.0
  }

  test("angularPosition should return None for linear axes") {
    AxisState(position = 100.0).angularPosition shouldBe None
  }

  test("angularPosition should compute degrees in [0, 360) for rotating axes") {
    val cpr = 400.0
    val state = AxisState(position = 0.0, countsPerRevolution = Some(cpr))

    state.copy(position = 0.0).angularPosition   shouldBe Some(0.0)
    state.copy(position = 100.0).angularPosition shouldBe Some(90.0)   // quarter rev
    state.copy(position = 200.0).angularPosition shouldBe Some(180.0)  // half rev
    state.copy(position = 300.0).angularPosition shouldBe Some(270.0)  // three-quarter
    state.copy(position = 400.0).angularPosition shouldBe Some(0.0)    // full rev wraps
    state.copy(position = 500.0).angularPosition shouldBe Some(90.0)   // 500 % 400 = 100 → 90°

    // Negative positions
    state.copy(position = -100.0).angularPosition shouldBe Some(270.0) // -100/400*360 = -90 → +270
  }

  test("angularPosition should return None when countsPerRevolution is zero") {
    AxisState(position = 100.0, countsPerRevolution = Some(0.0)).angularPosition shouldBe None
  }

  // ========================================
  // State Model Tests — AxisCmdState
  // ========================================
  // State Machine Tests — AxisStateEnum (SDD Figure 4-2)
  // ========================================

  test("Lost state should only accept homeAxis and stopAxis") {
    import AxisStateEnum._
    Lost.validateCommand("homeAxis") shouldBe None
    Lost.validateCommand("stopAxis") shouldBe None   // safety command, valid from any state
    Lost.validateCommand("positionAxis") shouldBe defined
    Lost.validateCommand("offsetAxis") shouldBe defined
    Lost.validateCommand("selectWheel") shouldBe defined
    Lost.validateCommand("trackAxis") shouldBe defined
  }

  test("Homing state should only accept stopAxis") {
    import AxisStateEnum._
    Homing.validateCommand("stopAxis") shouldBe None
    Homing.validateCommand("homeAxis") shouldBe defined
    Homing.validateCommand("positionAxis") shouldBe defined
    Homing.validateCommand("trackAxis") shouldBe defined
  }

  test("Idle state should accept homeAxis, motion commands, trackAxis, and stopAxis") {
    import AxisStateEnum._
    Idle.validateCommand("homeAxis") shouldBe None
    Idle.validateCommand("positionAxis") shouldBe None
    Idle.validateCommand("offsetAxis") shouldBe None
    Idle.validateCommand("selectWheel") shouldBe None
    Idle.validateCommand("trackAxis") shouldBe None
    Idle.validateCommand("stopAxis") shouldBe None   // safety command, valid from any state
  }

  test("Moving state should accept stopAxis and interruptible motion commands") {
    import AxisStateEnum._
    Moving.validateCommand("stopAxis")      shouldBe None
    Moving.validateCommand("positionAxis")  shouldBe None   // CommandHandler will interrupt
    Moving.validateCommand("offsetAxis")    shouldBe None   // CommandHandler will interrupt
    Moving.validateCommand("selectWheel")   shouldBe None   // CommandHandler will interrupt
    Moving.validateCommand("homeAxis")      shouldBe defined
    Moving.validateCommand("trackAxis")     shouldBe defined
  }

  test("Tracking state should accept stopAxis and trackAxis (re-issue)") {
    import AxisStateEnum._
    Tracking.validateCommand("stopAxis") shouldBe None
    Tracking.validateCommand("trackAxis") shouldBe None
    Tracking.validateCommand("homeAxis") shouldBe defined
    Tracking.validateCommand("positionAxis") shouldBe defined
  }

  test("Error state should accept homeAxis and stopAxis (recovery)") {
    import AxisStateEnum._
    Error.validateCommand("homeAxis") shouldBe None
    Error.validateCommand("stopAxis") shouldBe None   // safety command, valid from any state
    Error.validateCommand("positionAxis") shouldBe defined
    Error.validateCommand("trackAxis") shouldBe defined
  }

  test("stopAxis completion state depends on prior state (SDD Figure 4-2)") {
    import AxisStateEnum._
    Homing.stopCompletionState(homed = false) shouldBe Lost   // interrupted home = not homed
    Moving.stopCompletionState(homed = true)  shouldBe Idle   // interrupted move = still homed
    Tracking.stopCompletionState(homed = true) shouldBe Idle  // stopped tracking = idle
    Error.stopCompletionState(homed = false)  shouldBe Lost   // failed home latched Error = not homed
    Error.stopCompletionState(homed = true)   shouldBe Idle   // fault on homed axis = still homed
  }

  test("validateCommand rejection message should include state and command") {
    val result = AxisStateEnum.Lost.validateCommand("positionAxis")
    result shouldBe defined
    result.get should include("Lost")
    result.get should include("positionAxis")
  }

  // ========================================
  // State Model Tests — AxisCmdState

  test("AxisCmdState should have correct defaults") {
    val cmd = AxisCmdState()
    
    cmd.activeThread shouldBe 0
    cmd.axisErrorMsg shouldBe ""
    cmd.inPosition shouldBe false
    cmd.moving shouldBe false
    cmd.activeCommand shouldBe None
    cmd.commandHalted shouldBe false
    cmd.stopCode shouldBe 0
  }

  test("AxisCmdState.update should modify published and internal fields") {
    val updated = AxisCmdState().update(Map(
      "activeThread" -> 3,
      "moving" -> true,
      "activeCommand" -> ActiveCommand.Move,
      "stopCode" -> 1
    ))
    
    updated.activeThread shouldBe 3
    updated.moving shouldBe true
    updated.activeCommand shouldBe Some(ActiveCommand.Move)
    updated.stopCode shouldBe 1
  }

  test("AxisCmdState.update should support clearActiveCommand") {
    val withCommand = AxisCmdState(activeCommand = Some(ActiveCommand.Home))
    val cleared = withCommand.update(Map("clearActiveCommand" -> true))
    
    cleared.activeCommand shouldBe None
  }

  // ========================================
  // State Model Tests — HcdState
  // ========================================

  test("HcdState should initialize axes with both state structures") {
    val state = HcdState()
    
    val withAxisA = state.initializeAxis(Axis.A, MechanismType.Linear)
    
    withAxisA.activeAxes(Axis.A.index) should be (true)
    withAxisA.getAxis(Axis.A) should not be (None)
    withAxisA.getAxis(Axis.A).get.mechanismType should be (MechanismType.Linear)
    // Should also have AxisCmdState
    withAxisA.getCmdState(Axis.A) should not be (None)
    withAxisA.getCmdState(Axis.A).get.activeThread shouldBe 0
  }
  
  test("HcdState.updateAxis should update specific axis") {
    val state = HcdState().initializeAxis(Axis.A)
    
    val updated = state.updateAxis(Axis.A, Map(
      "position" -> 500.0,
      "axisState" -> AxisStateEnum.Moving
    ))
    
    val axisA = updated.getAxis(Axis.A).get
    axisA.position should be (500.0)
    axisA.axisState should be (AxisStateEnum.Moving)
  }
  
  test("HcdState.update should update HCD-level fields") {
    val initial = HcdState()
    
    val updated = initial.update(Map(
      "state" -> HcdStateEnum.Faulted,
      "controllerErrorMsg" -> "Test error",
      "version" -> 12345,
      "debug" -> true
    ))
    
    updated.state should be (HcdStateEnum.Faulted)
    updated.controllerErrorMsg should be ("Test error")
    updated.version should be (12345)
    updated.debug should be (true)
  }

  test("HcdState.updateCmdState should update axis command state") {
    val state = HcdState().initializeAxis(Axis.B)
    
    val updated = state.updateCmdState(Axis.B, Map(
      "activeThread" -> 2,
      "moving" -> true,
      "activeCommand" -> ActiveCommand.Move
    ))
    
    val cmdB = updated.getCmdState(Axis.B).get
    cmdB.activeThread shouldBe 2
    cmdB.moving shouldBe true
    cmdB.activeCommand shouldBe Some(ActiveCommand.Move)
  }

  test("HcdState.isThreadActive should decode threadStatus bitmask") {
    val state = HcdState(threadStatus = 0x06)  // bits 1 and 2 set (threads 1 and 2)
    
    state.isThreadActive(0) shouldBe false
    state.isThreadActive(1) shouldBe true
    state.isThreadActive(2) shouldBe true
    state.isThreadActive(3) shouldBe false
    state.isThreadActive(7) shouldBe false
  }

  // ========================================
  // Connection Status Tests
  // ========================================

  test("HcdState connection fields should default to Disconnected") {
    val state = HcdState()
    state.commandConnection shouldBe ConnectionStatus.Disconnected
    state.statusConnection  shouldBe ConnectionStatus.Disconnected
    state.consoleConnection shouldBe ConnectionStatus.Disconnected
  }

  test("HcdState.isOperational requires command and status connections; console is irrelevant") {
    // Neither connected → not operational
    HcdState().isOperational shouldBe false

    // Only command connected → not operational
    HcdState(commandConnection = ConnectionStatus.Connected).isOperational shouldBe false

    // Only status connected → not operational
    HcdState(statusConnection = ConnectionStatus.Connected).isOperational shouldBe false

    // Both command and status connected → operational (console irrelevant)
    HcdState(
      commandConnection = ConnectionStatus.Connected,
      statusConnection  = ConnectionStatus.Connected
    ).isOperational shouldBe true

    // All three connected → operational
    HcdState(
      commandConnection = ConnectionStatus.Connected,
      statusConnection  = ConnectionStatus.Connected,
      consoleConnection = ConnectionStatus.Connected
    ).isOperational shouldBe true

    // Command + status connected, console disconnected → still operational
    HcdState(
      commandConnection = ConnectionStatus.Connected,
      statusConnection  = ConnectionStatus.Connected,
      consoleConnection = ConnectionStatus.Disconnected
    ).isOperational shouldBe true
  }

  test("HcdState.update should update individual connection fields") {
    val state = HcdState()

    val afterCmd = state.update(Map("commandConnection" -> ConnectionStatus.Connected))
    afterCmd.commandConnection shouldBe ConnectionStatus.Connected
    afterCmd.statusConnection  shouldBe ConnectionStatus.Disconnected
    afterCmd.consoleConnection shouldBe ConnectionStatus.Disconnected

    val afterSts = afterCmd.update(Map("statusConnection" -> ConnectionStatus.Connected))
    afterSts.commandConnection shouldBe ConnectionStatus.Connected
    afterSts.statusConnection  shouldBe ConnectionStatus.Connected
    afterSts.consoleConnection shouldBe ConnectionStatus.Disconnected
    afterSts.isOperational     shouldBe true

    val afterCon = afterSts.update(Map("consoleConnection" -> ConnectionStatus.Connected))
    afterCon.consoleConnection shouldBe ConnectionStatus.Connected
    afterCon.isOperational     shouldBe true
  }

  test("InternalStateActor should handle ReportConnectionStatus messages") {
    val actor = testKit.spawn(InternalStateActor())
    val probe = testKit.createTestProbe[HcdState]()

    // Report command connection
    actor ! InternalStateActor.ReportConnectionStatus("commandConnection", ConnectionStatus.Connected)

    // Allow message to process
    Thread.sleep(100)

    actor ! InternalStateActor.GetHcdState(probe.ref)
    val state1 = probe.receiveMessage()
    state1.commandConnection shouldBe ConnectionStatus.Connected
    state1.statusConnection  shouldBe ConnectionStatus.Disconnected
    state1.isOperational     shouldBe false

    // Report status connection
    actor ! InternalStateActor.ReportConnectionStatus("statusConnection", ConnectionStatus.Connected)
    Thread.sleep(100)

    actor ! InternalStateActor.GetHcdState(probe.ref)
    val state2 = probe.receiveMessage()
    state2.commandConnection shouldBe ConnectionStatus.Connected
    state2.statusConnection  shouldBe ConnectionStatus.Connected
    state2.isOperational     shouldBe true

    // Console connection doesn't affect isOperational
    actor ! InternalStateActor.ReportConnectionStatus("consoleConnection", ConnectionStatus.Connected)
    Thread.sleep(100)

    actor ! InternalStateActor.GetHcdState(probe.ref)
    val state3 = probe.receiveMessage()
    state3.consoleConnection shouldBe ConnectionStatus.Connected
    state3.isOperational     shouldBe true
  }

  // ========================================
  // Actor Tests - Basic Operations
  // ========================================
  
  test("InternalStateActor should handle HCD state updates") {
    val actor = testKit.spawn(InternalStateActor())
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Update HCD state
    actor ! InternalStateActor.UpdateHcdState(
      Map("version" -> 99999, "debug" -> true),
      probe.ref
    )
    
    // Should get acknowledgment
    val response = probe.receiveMessage()
    response.success should be (true)
    
    // Verify state was updated
    val queryProbe = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(queryProbe.ref)
    
    val state = queryProbe.receiveMessage()
    state.version should be (99999)
    state.debug should be (true)
  }
  
  test("InternalStateActor should handle axis state updates") {
    val initialState = HcdState().initializeAxis(Axis.A)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Update axis A
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("position" -> 100.0, "velocity" -> 5.0),
      probe.ref
    )
    
    // Should get acknowledgment
    val response = probe.receiveMessage()
    response.success should be (true)
    
    // Verify axis was updated
    val queryProbe = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.A, queryProbe.ref)
    
    val axisState = queryProbe.receiveMessage()
    axisState should not be (None)
    axisState.get.position should be (100.0)
    axisState.get.velocity should be (5.0)
  }
  
  test("InternalStateActor should query non-existent axis") {
    val actor = testKit.spawn(InternalStateActor())
    val probe = testKit.createTestProbe[Option[AxisState]]()
    
    // Query axis that doesn't exist
    actor ! InternalStateActor.GetAxisState(Axis.B, probe.ref)
    
    val result = probe.receiveMessage()
    result should be (None)
  }

  test("InternalStateActor should handle axis cmd state updates") {
    val initialState = HcdState().initializeAxis(Axis.A)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    actor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A,
      Map("activeThread" -> 1, "moving" -> true, "activeCommand" -> ActiveCommand.Move),
      probe.ref
    )
    probe.receiveMessage().success shouldBe true
    
    // Verify
    val queryProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    actor ! InternalStateActor.GetAxisCmdState(Axis.A, queryProbe.ref)
    
    val cmdState = queryProbe.receiveMessage()
    cmdState should not be None
    cmdState.get.activeThread shouldBe 1
    cmdState.get.moving shouldBe true
    cmdState.get.activeCommand shouldBe Some(ActiveCommand.Move)
  }

  // ========================================
  // Subscription Tests — StateChanged (for CSP)
  // ========================================
  
  test("InternalStateActor should notify subscribers on HCD state changes") {
    val actor = testKit.spawn(InternalStateActor())
    val subscriberProbe = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // Subscribe — IS sends an immediate snapshot; drain it before asserting on changes
    actor ! InternalStateActor.Subscribe(subscriberProbe.ref)
    drainSnapshot(subscriberProbe)

    // Update state
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateHcdState(
      Map("version" -> 123),
      updateProbe.ref
    )
    updateProbe.receiveMessage()  // Wait for update to complete
    
    // Should receive notification
    val notification = subscriberProbe.receiveMessage()
    notification.changedFields should contain ("version")
    notification.hcdState.version should be (123)
  }
  
  test("InternalStateActor should notify subscribers on axis state changes") {
    val initialState = HcdState().initializeAxis(Axis.C)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val subscriberProbe = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // Subscribe — drain initial snapshot before asserting on changes
    actor ! InternalStateActor.Subscribe(subscriberProbe.ref)
    drainSnapshot(subscriberProbe)

    // Update axis
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateAxisState(
      Axis.C,
      Map("position" -> 999.0),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should receive notification
    val notification = subscriberProbe.receiveMessage()
    notification.changedAxes should contain (Axis.C)
    notification.changedFields should contain ("position")
  }
  
  test("InternalStateActor should support filtered subscriptions") {
    val initialState = HcdState()
      .initializeAxis(Axis.A)
      .initializeAxis(Axis.B)
    
    val actor = testKit.spawn(InternalStateActor(initialState))
    val subscriberProbe = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // Subscribe only to Axis A changes — drain initial snapshot first
    val filter = InternalStateActor.AxisFilter(Set(Axis.A))
    actor ! InternalStateActor.Subscribe(subscriberProbe.ref, Some(filter))
    drainSnapshot(subscriberProbe)

    // Update Axis B (should NOT notify)
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateAxisState(
      Axis.B,
      Map("position" -> 100.0),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should NOT receive notification
    subscriberProbe.expectNoMessage(200.millis)
    
    // Update Axis A (should notify)
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("position" -> 200.0),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should receive notification
    val notification = subscriberProbe.receiveMessage()
    notification.changedAxes should contain (Axis.A)
  }
  
  test("InternalStateActor should support unsubscribe") {
    val actor = testKit.spawn(InternalStateActor())
    val subscriberProbe = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // Subscribe — drain initial snapshot, then unsubscribe
    actor ! InternalStateActor.Subscribe(subscriberProbe.ref)
    drainSnapshot(subscriberProbe)

    // Unsubscribe
    actor ! InternalStateActor.Unsubscribe(subscriberProbe.ref)
    
    // Update state
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateHcdState(
      Map("version" -> 456),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should NOT receive notification
    subscriberProbe.expectNoMessage(200.millis)
  }
  
  // ========================================
  // Subscription Tests — CmdStateChanged (for CommandWatchers)
  // ========================================

  test("InternalStateActor should notify cmd state subscribers on axis cmd changes") {
    val initialState = HcdState().initializeAxis(Axis.A)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val cmdProbe = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Subscribe to Axis A command state
    actor ! InternalStateActor.SubscribeCmdState(Axis.A, cmdProbe.ref)
    
    // Update cmd state for Axis A
    actor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A,
      Map("activeThread" -> 1, "moving" -> true),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should receive cmd state notification
    val notification = cmdProbe.receiveMessage()
    notification.axis shouldBe Axis.A
    notification.cmdState.activeThread shouldBe 1
    notification.cmdState.moving shouldBe true
    notification.changedFields should contain ("activeThread")
    notification.changedFields should contain ("moving")
  }

  test("InternalStateActor cmd subscriber should NOT be notified by axis state updates") {
    val initialState = HcdState().initializeAxis(Axis.A)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val cmdProbe = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Subscribe to cmd state only
    actor ! InternalStateActor.SubscribeCmdState(Axis.A, cmdProbe.ref)
    
    // Update operational state (position, velocity) — NOT cmd state
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("position" -> 500.0, "velocity" -> 10.0),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Cmd subscriber should NOT be notified (position doesn't affect cmd state)
    cmdProbe.expectNoMessage(200.millis)
  }

  test("InternalStateActor cmd subscriber for axis A should NOT see axis B changes") {
    val initialState = HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val cmdProbeA = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Subscribe to Axis A cmd state only
    actor ! InternalStateActor.SubscribeCmdState(Axis.A, cmdProbeA.ref)
    
    // Update Axis B cmd state
    actor ! InternalStateActor.UpdateAxisCmdState(
      Axis.B,
      Map("activeThread" -> 2, "moving" -> true),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Axis A subscriber should NOT be notified
    cmdProbeA.expectNoMessage(200.millis)
  }

  test("InternalStateActor should not notify cmd subscribers when values unchanged") {
    val initialState = HcdState().initializeAxis(Axis.A)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val cmdProbe = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Subscribe
    actor ! InternalStateActor.SubscribeCmdState(Axis.A, cmdProbe.ref)
    
    // Send same default values (nothing actually changes)
    actor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A,
      Map("activeThread" -> 0, "moving" -> false),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should NOT notify (values didn't actually change)
    cmdProbe.expectNoMessage(200.millis)
  }

  test("InternalStateActor should support UnsubscribeCmdState") {
    val initialState = HcdState().initializeAxis(Axis.A)
    val actor = testKit.spawn(InternalStateActor(initialState))
    val cmdProbe = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Subscribe then unsubscribe
    actor ! InternalStateActor.SubscribeCmdState(Axis.A, cmdProbe.ref)
    actor ! InternalStateActor.UnsubscribeCmdState(cmdProbe.ref)
    
    // Update cmd state
    actor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A,
      Map("activeThread" -> 1),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should NOT be notified
    cmdProbe.expectNoMessage(200.millis)
  }

  // ========================================
  // InPosition Mirroring Tests
  // ========================================

  test("InternalStateActor should mirror inPosition from AxisState to AxisCmdState") {
    // Set up axis with demand far from position (inPosition = false)
    val initialState = HcdState().initializeAxis(Axis.A)
      .updateAxis(Axis.A, Map("demand" -> 1000.0, "inPositionThreshold" -> 1.0))
    
    val actor = testKit.spawn(InternalStateActor(initialState))
    val cmdProbe = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Subscribe to cmd state
    actor ! InternalStateActor.SubscribeCmdState(Axis.A, cmdProbe.ref)
    
    // Update position to reach demand (triggers inPosition = true)
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("position" -> 1000.5),  // Within threshold of 1.0
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Cmd subscriber SHOULD be notified because inPosition was mirrored
    val notification = cmdProbe.receiveMessage()
    notification.axis shouldBe Axis.A
    notification.cmdState.inPosition shouldBe true
    notification.changedFields should contain ("inPosition")
  }

  test("InternalStateActor should not mirror inPosition when it does not change") {
    // Start with position == demand (inPosition already true)
    val initialState = HcdState().initializeAxis(Axis.A)
      .updateAxis(Axis.A, Map("position" -> 100.0, "demand" -> 100.0, "inPositionThreshold" -> 1.0))
    // Also need cmd state inPosition to match
      .updateCmdState(Axis.A, Map("inPosition" -> true))
    
    val actor = testKit.spawn(InternalStateActor(initialState))
    val cmdProbe = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // Subscribe
    actor ! InternalStateActor.SubscribeCmdState(Axis.A, cmdProbe.ref)
    
    // Update position but still in range (inPosition stays true)
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("position" -> 100.5),  // Still within threshold
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // Should NOT notify cmd subscriber (inPosition didn't change)
    cmdProbe.expectNoMessage(200.millis)
  }

  // ========================================
  // Integration Tests
  // ========================================
  
  test("InternalStateActor should support multiple simultaneous subscribers") {
    val actor = testKit.spawn(InternalStateActor())
    val subscriber1 = testKit.createTestProbe[InternalStateActor.StateChanged]()
    val subscriber2 = testKit.createTestProbe[InternalStateActor.StateChanged]()
    val subscriber3 = testKit.createTestProbe[InternalStateActor.StateChanged]()
    
    // All subscribe — drain initial snapshots before asserting on changes
    actor ! InternalStateActor.Subscribe(subscriber1.ref)
    actor ! InternalStateActor.Subscribe(subscriber2.ref)
    actor ! InternalStateActor.Subscribe(subscriber3.ref)
    drainSnapshot(subscriber1)
    drainSnapshot(subscriber2)
    drainSnapshot(subscriber3)

    // Update
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateHcdState(
      Map("simulation" -> true),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // All should receive notification
    subscriber1.receiveMessage().hcdState.simulation should be (true)
    subscriber2.receiveMessage().hcdState.simulation should be (true)
    subscriber3.receiveMessage().hcdState.simulation should be (true)
  }
  
  test("InternalStateActor should track command completion scenario using dual channels") {
    // Simulate a positionAxis command completion workflow.
    // The CommandHandler sets demand and starts motion;
    // StatusMonitor updates position/velocity (AxisState) and moving/thread (AxisCmdState);
    // CommandWatcher subscribes to CmdState and evaluates completion mask.
    val initialState = HcdState()
      .initializeAxis(Axis.D)
      .updateAxis(Axis.D, Map("demand" -> 100.0, "inPositionThreshold" -> 1.0))
    
    val actor = testKit.spawn(InternalStateActor(initialState))
    
    val stateWatcher = testKit.createTestProbe[InternalStateActor.StateChanged]()
    val cmdWatcher = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    val updateProbe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    
    // CSP subscribes to all state changes — drain initial snapshot
    actor ! InternalStateActor.Subscribe(stateWatcher.ref)
    drainSnapshot(stateWatcher)
    // CommandWatcher subscribes to Axis D cmd state
    actor ! InternalStateActor.SubscribeCmdState(Axis.D, cmdWatcher.ref)
    
    // 1. CommandHandler starts command — sets cmd state
    actor ! InternalStateActor.UpdateAxisCmdState(
      Axis.D,
      Map(
        "activeThread" -> 4,
        "moving" -> true,
        "activeCommand" -> ActiveCommand.Move
      ),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // CommandWatcher should be notified of command start
    val startNotification = cmdWatcher.receiveMessage()
    startNotification.cmdState.activeThread shouldBe 4
    startNotification.cmdState.moving shouldBe true
    
    // 2. StatusMonitor polls — position updates (high frequency AxisState updates)
    actor ! InternalStateActor.UpdateAxisState(
      Axis.D,
      Map("position" -> 50.0, "velocity" -> 100.0),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // CSP gets notified (position changed)
    stateWatcher.receiveMessage().changedFields should contain ("position")
    // CommandWatcher should NOT be notified (position doesn't trigger cmd state)
    cmdWatcher.expectNoMessage(200.millis)
    
    // 3. Motor reaches target — position update triggers inPosition
    actor ! InternalStateActor.UpdateAxisState(
      Axis.D,
      Map("position" -> 100.5),  // Within threshold of 1.0 from demand=100.0
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // CSP gets notified
    stateWatcher.receiveMessage()
    // CommandWatcher SHOULD be notified because inPosition was mirrored
    val inPosNotification = cmdWatcher.receiveMessage()
    inPosNotification.cmdState.inPosition shouldBe true
    inPosNotification.changedFields should contain ("inPosition")
    
    // 4. StatusMonitor detects thread stopped and motor not moving
    actor ! InternalStateActor.UpdateAxisCmdState(
      Axis.D,
      Map("activeThread" -> 0, "moving" -> false, "stopCode" -> 1),
      updateProbe.ref
    )
    updateProbe.receiveMessage()
    
    // CommandWatcher should get final notification — can now evaluate completion mask:
    //   activeThread==0, inPosition==true, axisErrorMsg=="", moving==false  → Completed!
    val completionNotification = cmdWatcher.receiveMessage()
    completionNotification.cmdState.activeThread shouldBe 0
    completionNotification.cmdState.moving shouldBe false
    completionNotification.cmdState.inPosition shouldBe true  // Preserved from mirror
    completionNotification.cmdState.axisErrorMsg shouldBe ""
    completionNotification.cmdState.stopCode shouldBe 1  // Normal decel stop
  }

  // ========================================
  // EnterFaulted central fault transition (S53)
  //
  // Verifies that InternalStateActor.EnterFaulted atomically:
  //   - transitions HCD state to Faulted (with reason message)
  //   - applies per-axis state transitions: Homing→Lost, Moving→Error,
  //     Tracking→Error, others unchanged
  //   - clears activeCommand on any axis that had one
  //   - is idempotent (calling twice doesn't break invariants)
  //
  // EnterFaulted is the single chokepoint for all fault paths (CS-detected
  // controller error, connection loss, manually-triggered faults).  A
  // regression here would silently mishandle every fault scenario.
  // ========================================

  // Build an HcdState with several axes in known motion-related states for
  // EnterFaulted to act on.  Caller can specify each axis's state.
  private def faultTestState(
    axisStates: Map[Axis, AxisStateEnum],
    withActiveCommand: Set[Axis] = Set.empty
  ): HcdState =
    var hcd = HcdState(state = HcdStateEnum.Ready)
    axisStates.foreach { (axis, st) =>
      hcd = hcd.initializeAxis(axis)
      // Apply the desired starting axisState
      val updatedAxis = hcd.axes(axis).copy(axisState = st)
      hcd = hcd.copy(axes = hcd.axes + (axis -> updatedAxis))
      // If requested, give the axis an active command
      if withActiveCommand.contains(axis) then
        val updatedCmd = hcd.cmdStates(axis).copy(
          activeCommand = Some(ActiveCommand.Move),
          activeThread = 1
        )
        hcd = hcd.copy(cmdStates = hcd.cmdStates + (axis -> updatedCmd))
    }
    hcd

  test("EnterFaulted should transition HCD state to Faulted with reason") {
    val initial = faultTestState(Map(Axis.A -> AxisStateEnum.Idle))
    val actor = testKit.spawn(InternalStateActor(initial))
    actor ! InternalStateActor.EnterFaulted("Controller Error: 1 Unrecognized command")
    Thread.sleep(50)
    val probe = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(probe.ref)
    val state = probe.receiveMessage()
    state.state shouldBe HcdStateEnum.Faulted
    state.controllerErrorMsg shouldBe "Controller Error: 1 Unrecognized command"
  }

  test("EnterFaulted should transition Homing axes to Lost (position unknown)") {
    val initial = faultTestState(Map(Axis.A -> AxisStateEnum.Homing))
    val actor = testKit.spawn(InternalStateActor(initial))
    actor ! InternalStateActor.EnterFaulted("test fault")
    Thread.sleep(50)
    val probe = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    probe.receiveMessage().get.axisState shouldBe AxisStateEnum.Lost
  }

  test("EnterFaulted should transition Moving axes to Error") {
    val initial = faultTestState(Map(Axis.A -> AxisStateEnum.Moving))
    val actor = testKit.spawn(InternalStateActor(initial))
    actor ! InternalStateActor.EnterFaulted("test fault")
    Thread.sleep(50)
    val probe = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    probe.receiveMessage().get.axisState shouldBe AxisStateEnum.Error
  }

  test("EnterFaulted should transition Tracking axes to Error") {
    val initial = faultTestState(Map(Axis.A -> AxisStateEnum.Tracking))
    val actor = testKit.spawn(InternalStateActor(initial))
    actor ! InternalStateActor.EnterFaulted("test fault")
    Thread.sleep(50)
    val probe = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.A, probe.ref)
    probe.receiveMessage().get.axisState shouldBe AxisStateEnum.Error
  }

  test("EnterFaulted should leave Idle, Lost, Error axes unchanged") {
    val initial = faultTestState(Map(
      Axis.A -> AxisStateEnum.Idle,
      Axis.B -> AxisStateEnum.Lost,
      Axis.C -> AxisStateEnum.Error
    ))
    val actor = testKit.spawn(InternalStateActor(initial))
    actor ! InternalStateActor.EnterFaulted("test fault")
    Thread.sleep(50)
    val probeA = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.A, probeA.ref)
    probeA.receiveMessage().get.axisState shouldBe AxisStateEnum.Idle
    val probeB = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.B, probeB.ref)
    probeB.receiveMessage().get.axisState shouldBe AxisStateEnum.Lost
    val probeC = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.C, probeC.ref)
    probeC.receiveMessage().get.axisState shouldBe AxisStateEnum.Error
  }

  test("EnterFaulted should transition mixed axes correctly in one call") {
    // The fault scenario: Homing axis A, Moving axis B, Idle axis C, Lost axis D.
    // After fault: A → Lost, B → Error, C unchanged, D unchanged.
    val initial = faultTestState(Map(
      Axis.A -> AxisStateEnum.Homing,
      Axis.B -> AxisStateEnum.Moving,
      Axis.C -> AxisStateEnum.Idle,
      Axis.D -> AxisStateEnum.Lost
    ))
    val actor = testKit.spawn(InternalStateActor(initial))
    actor ! InternalStateActor.EnterFaulted("test fault")
    Thread.sleep(50)
    val probeA = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.A, probeA.ref)
    probeA.receiveMessage().get.axisState shouldBe AxisStateEnum.Lost
    val probeB = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.B, probeB.ref)
    probeB.receiveMessage().get.axisState shouldBe AxisStateEnum.Error
    val probeC = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.C, probeC.ref)
    probeC.receiveMessage().get.axisState shouldBe AxisStateEnum.Idle
    val probeD = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.D, probeD.ref)
    probeD.receiveMessage().get.axisState shouldBe AxisStateEnum.Lost
  }

  test("EnterFaulted should clear activeCommand on axes that had one") {
    // Axis A has an active Move command (e.g. positionAxis in flight) at fault time.
    val initial = faultTestState(
      Map(Axis.A -> AxisStateEnum.Moving),
      withActiveCommand = Set(Axis.A)
    )
    val actor = testKit.spawn(InternalStateActor(initial))
    actor ! InternalStateActor.EnterFaulted("test fault")
    Thread.sleep(50)
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    actor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val cmdState = probe.receiveMessage().get
    cmdState.activeCommand shouldBe None
    // clearActiveCommand also zeroes activeThread per StateModel line 473
    cmdState.activeThread shouldBe 0
  }

  test("EnterFaulted should be idempotent (second call doesn't break invariants)") {
    val initial = faultTestState(Map(Axis.A -> AxisStateEnum.Moving))
    val actor = testKit.spawn(InternalStateActor(initial))
    actor ! InternalStateActor.EnterFaulted("first fault")
    Thread.sleep(50)
    actor ! InternalStateActor.EnterFaulted("second fault")
    Thread.sleep(50)
    val hcdProbe = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(hcdProbe.ref)
    val state = hcdProbe.receiveMessage()
    state.state shouldBe HcdStateEnum.Faulted
    // Second message overwrote the reason — acceptable behavior.
    state.controllerErrorMsg shouldBe "second fault"
    // Axis remains in Error (was Error after first fault; Error → unchanged).
    val axisProbe = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(Axis.A, axisProbe.ref)
    axisProbe.receiveMessage().get.axisState shouldBe AxisStateEnum.Error
  }

  test("EnterFaulted should clear initializingReason") {
    // Faulting from Initializing state (or after a faultReset Init that
    // populated initializingReason) should clear the reason field, since the
    // HCD is no longer initializing.
    val initial = HcdState(
      state = HcdStateEnum.Uninitialized,
      initializingReason = "startup"
    ).initializeAxis(Axis.A)
    val actor = testKit.spawn(InternalStateActor(initial))
    actor ! InternalStateActor.EnterFaulted("init failed")
    Thread.sleep(50)
    val probe = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(probe.ref)
    val state = probe.receiveMessage()
    state.state shouldBe HcdStateEnum.Faulted
    state.initializingReason shouldBe ""
  }

  // ========================================
  // Tracking session invariant (S65)
  //
  // handleUpdateAxisState enforces declaratively: axisState != Tracking ⇒
  // trackingSession == None.  The session ledger anchors the next PVA segment's
  // ΔP/T; a stale ledger left behind after leaving Tracking would make the next
  // trackAxis SEED from garbage and slam the motor / fault the controller.
  // Leaving Tracking also resets the PVT telemetry readings to defaults.
  // ========================================

  private def trackingSession(segments: Long = 5L): TrackingSession =
    TrackingSession(
      lastTargetCounts  = 1000L,
      lastValidTime     = Instant.now(),
      btFiredAt         = Instant.now(),
      segmentsSubmitted = segments
    )

  private def enterTracking(
    actor: org.apache.pekko.actor.typed.ActorRef[InternalStateActor.Command],
    axis: Axis,
    session: TrackingSession,
    extra: Map[String, Any] = Map.empty
  ): Unit =
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateAxisState(
      axis,
      Map("axisState" -> AxisStateEnum.Tracking, "trackingSession" -> Some(session)) ++ extra,
      probe.ref
    )
    probe.receiveMessage().success shouldBe true

  private def axisStateOf(
    actor: org.apache.pekko.actor.typed.ActorRef[InternalStateActor.Command],
    axis: Axis
  ): AxisState =
    val q = testKit.createTestProbe[Option[AxisState]]()
    actor ! InternalStateActor.GetAxisState(axis, q.ref)
    q.receiveMessage().getOrElse(fail(s"axis $axis missing"))

  test("leaving Tracking auto-clears trackingSession and resets PVT telemetry") {
    val actor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(Axis.A)))
    val session = trackingSession()

    // Enter Tracking with a session and non-default telemetry readings.
    enterTracking(actor, Axis.A, session,
      extra = Map("pvFreeSlots" -> 100, "btSegmentsExecuted" -> 7))
    val tracking = axisStateOf(actor, Axis.A)
    tracking.axisState shouldBe AxisStateEnum.Tracking
    tracking.trackingSession shouldBe Some(session)

    // Transition to Idle WITHOUT supplying a trackingSession key.
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A, Map("axisState" -> AxisStateEnum.Idle), probe.ref)
    probe.receiveMessage().success shouldBe true

    val idle = axisStateOf(actor, Axis.A)
    idle.axisState shouldBe AxisStateEnum.Idle
    idle.trackingSession shouldBe None         // auto-cleared
    idle.pvFreeSlots shouldBe 255              // telemetry reset to default
    idle.btSegmentsExecuted shouldBe 0
  }

  test("leaving Tracking to Error clears trackingSession (the historically-leaky path)") {
    // CS.reportAxisError / CHA.setErrorState transition Tracking → Error without
    // explicitly clearing the session; the declarative invariant must catch it.
    val actor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(Axis.B)))
    enterTracking(actor, Axis.B, trackingSession())

    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateAxisState(
      Axis.B, Map("axisState" -> AxisStateEnum.Error), probe.ref)
    probe.receiveMessage().success shouldBe true

    val errored = axisStateOf(actor, Axis.B)
    errored.axisState shouldBe AxisStateEnum.Error
    errored.trackingSession shouldBe None
  }

  test("staying in Tracking preserves the trackingSession") {
    val actor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(Axis.A)))
    val session = trackingSession()
    enterTracking(actor, Axis.A, session)

    // A position-only update while still Tracking must NOT clear the ledger.
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A, Map("position" -> 123.0), probe.ref)
    probe.receiveMessage().success shouldBe true

    val still = axisStateOf(actor, Axis.A)
    still.axisState shouldBe AxisStateEnum.Tracking
    still.trackingSession shouldBe Some(session)
  }

  test("an explicit trackingSession in the same update wins over the auto-clear") {
    // The auto-clear only fires when no trackingSession key is supplied; a caller
    // that sets one explicitly (e.g. re-seeding) keeps its value.
    val actor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(Axis.A)))
    enterTracking(actor, Axis.A, trackingSession(segments = 1L))

    val reseeded = trackingSession(segments = 99L)
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    actor ! InternalStateActor.UpdateAxisState(
      Axis.A,
      Map("axisState" -> AxisStateEnum.Idle, "trackingSession" -> Some(reseeded)),
      probe.ref
    )
    probe.receiveMessage().success shouldBe true

    val s = axisStateOf(actor, Axis.A)
    s.axisState shouldBe AxisStateEnum.Idle
    s.trackingSession shouldBe Some(reseeded)  // explicit value preserved
  }
  // ========================================
  // Scan attribution in IS (ADR-001, Amendment A)
  //
  // IS owns the thread→axis registry and performs ALL attribution —
  // completions AND errors — from CS's per-scan ScanObservations, under one
  // invariant: an entry participates only if it is not Halted, its thread's
  // bit is clear, AND the observation is fresher than the registration
  // (observedAt > registeredAt). The freshness clause is what makes message
  // delivery latency harmless (the S85 storm delivered scans up to ~1.4s
  // late, after threads had been released and reallocated).
  //
  // These are pure-message tests: no GalilIo stubs — registry + observations
  // in, state changes out. The end-to-end pipeline (CS scan → ScanObservations
  // → these decisions) is covered by ControllerStatusActorTest.
  // ========================================

  private def cmdStateOf(
    actor: org.apache.pekko.actor.typed.ActorRef[InternalStateActor.Command],
    axis: Axis
  ): AxisCmdState =
    val q = testKit.createTestProbe[Option[AxisCmdState]]()
    actor ! InternalStateActor.GetAxisCmdState(axis, q.ref)
    q.receiveMessage().getOrElse(fail(s"axis $axis cmd state missing"))

  /**
   * Mailbox barrier: a synchronous ask guarantees every message previously
   * sent to `actor` from this test has been processed (single-sender FIFO).
   * Used to order test-side System.nanoTime() captures against RegisterThread
   * processing, which stamps the registeredAt staleness fence.
   */
  private def barrier(actor: org.apache.pekko.actor.typed.ActorRef[InternalStateActor.Command]): Unit =
    val q = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(q.ref)
    q.receiveMessage()
    ()

  /** A timestamp guaranteed fresher than every registration already sent. */
  private def freshObservedAt(actor: org.apache.pekko.actor.typed.ActorRef[InternalStateActor.Command]): Long =
    barrier(actor)
    System.nanoTime()

  test("S85 regression: in-flight program's ae==1 is not misattributed (stop_storm false positive)") {
    // The original 2026-07-13 18:24 incident: axis A's #MoveA (thread 4) had
    // completed; #StopA had just launched on thread 2 with its ae[A]=1 entry
    // flag. Under Amendment A the scan observes all 8 threads: bit 2 SET
    // (StopA running) — so ae[A]==1 means "in flight", not "died". No error,
    // no completion.
    val actor = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)))
    actor ! InternalStateActor.RegisterThread(2, Axis.A)   // #StopA just launched
    actor ! InternalStateActor.ScanObservations(1 << 2, freshObservedAt(actor), Map(Axis.A -> 1), 0, None)
    Thread.sleep(50)
    val cmd = cmdStateOf(actor, Axis.A)
    cmd.axisErrorMsg shouldBe ""             // the S85 bug latched an error here
    cmd.activeThread shouldBe 2              // StopA still running, not completed
    axisStateOf(actor, Axis.A).axisState should not be AxisStateEnum.Error
  }

  test("S85 storm regression: a stale scan cannot attribute against a reallocated thread") {
    // HCD.1 23:11:21.416 replay: #StopB completed on thread 2 and was
    // attributed; thread 2 was released and reallocated to #SelectB. A scan
    // whose _XQ read PREdated the SelectB launch (bit 2 clear — it observed
    // StopB's completed incarnation) was delivered ~1.4s late, after the
    // SelectB registration, and both failed and completed the just-started
    // command. The staleness gate (observedAt < registeredAt) excludes it.
    val actor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(Axis.B)))
    val staleObservedAt = System.nanoTime()                // read before the launch
    actor ! InternalStateActor.RegisterThread(2, Axis.B)   // #SelectB launches
    barrier(actor)
    actor ! InternalStateActor.ScanObservations(0x00, staleObservedAt, Map(Axis.B -> 1), 0, None)
    Thread.sleep(50)
    val cmd = cmdStateOf(actor, Axis.B)
    cmd.axisErrorMsg shouldBe ""
    cmd.activeThread shouldBe 2              // no spurious completion either
    axisStateOf(actor, Axis.B).axisState should not be AxisStateEnum.Error
  }

  test("S85 storm regression: a halt-window scan cannot attribute after reuse re-registration") {
    // HCD.2 23:11:46.955 replay: #MoveD (thread 5) was HX'd; #StopD reused
    // thread 5 (Halted → Active). A scan whose _XQ read fell in the halt
    // window (bit clear) was delivered after the re-registration. The
    // re-registration refreshes registeredAt, so the stale observation is
    // excluded; a fresh scan then completes StopD normally.
    val actor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(Axis.D)))
    actor ! InternalStateActor.RegisterThread(5, Axis.D)   // #MoveD
    val ack = testKit.createTestProbe[InternalStateActor.ThreadHaltedAck]()
    actor ! InternalStateActor.ThreadHalted(5, Axis.D, ack.ref)  // HX
    ack.receiveMessage(500.millis)
    val haltWindowObservedAt = System.nanoTime()           // scan reads _XQ5=-1 here
    actor ! InternalStateActor.RegisterThread(5, Axis.D)   // #StopD reuses thread 5
    barrier(actor)
    actor ! InternalStateActor.ScanObservations(0x00, haltWindowObservedAt, Map(Axis.D -> 1), 0, None)
    Thread.sleep(50)
    val cmd = cmdStateOf(actor, Axis.D)
    cmd.axisErrorMsg shouldBe ""
    cmd.activeThread shouldBe 5
    // Fresh observation: StopD's clean completion attributes normally.
    actor ! InternalStateActor.ScanObservations(0x00, freshObservedAt(actor), Map(Axis.D -> 0), 0, None)
    Thread.sleep(50)
    cmdStateOf(actor, Axis.D).activeThread shouldBe 0
  }

  test("Halted entry is excluded from attribution: no error, no completion") {
    // After checkAndInterrupt's HX + ThreadHalted, the dead thread's observed
    // clear must neither complete the interrupted command nor misattribute
    // its residual ae==1 (the S55 hazard, now an explicit registry state).
    // The observation here is FRESH — the Halted flag, not staleness, is
    // what excludes it.
    val actor = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A)))
    actor ! InternalStateActor.RegisterThread(3, Axis.A)
    val ack = testKit.createTestProbe[InternalStateActor.ThreadHaltedAck]()
    actor ! InternalStateActor.ThreadHalted(3, Axis.A, ack.ref)
    ack.receiveMessage(500.millis)
    actor ! InternalStateActor.ScanObservations(0x00, freshObservedAt(actor), Map(Axis.A -> 1), 0, None)
    Thread.sleep(50)
    val cmd = cmdStateOf(actor, Axis.A)
    cmd.axisErrorMsg shouldBe ""
    cmd.activeThread shouldBe 3              // entry retained, awaiting exit
  }

  test("S84 reuse lifecycle: Halted → re-register same thread → normal completion") {
    // checkAndInterrupt(reuseHaltedThread=true) retains the reservation and
    // registry entry; executeProgramAndWatch re-registers the SAME thread for
    // the SAME axis (Halted → Active). The follow-on then completes normally.
    val actor = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A)))
    val cmdProbe = testKit.createTestProbe[GalilCommandMessage]()
    actor ! InternalStateActor.SetCommandActor(cmdProbe.ref)
    actor ! InternalStateActor.RegisterThread(2, Axis.A)         // interrupted move
    val ack = testKit.createTestProbe[InternalStateActor.ThreadHaltedAck]()
    actor ! InternalStateActor.ThreadHalted(2, Axis.A, ack.ref)  // HX'd
    ack.receiveMessage(500.millis)
    actor ! InternalStateActor.RegisterThread(2, Axis.A)         // follow-on reuses thread 2
    // In-flight scan: thread 2 active, ae[A]=1 (follow-on's entry flag) → no error.
    actor ! InternalStateActor.ScanObservations(1 << 2, freshObservedAt(actor), Map(Axis.A -> 1), 0, None)
    Thread.sleep(50)
    cmdStateOf(actor, Axis.A).axisErrorMsg shouldBe ""
    // Completion scan: thread 2 cleared, ae cleaned by the program's success path.
    actor ! InternalStateActor.ScanObservations(0x00, freshObservedAt(actor), Map(Axis.A -> 0), 0, None)
    Thread.sleep(50)
    val cmd = cmdStateOf(actor, Axis.A)
    cmd.axisErrorMsg shouldBe ""
    cmd.activeThread shouldBe 0
    // Exactly one ReleaseThread(2), from the completion (reuse retained the
    // reservation across the halt; no release at halt time).
    cmdProbe.expectMessage(GalilCommandMessage.ReleaseThread(2))
    cmdProbe.expectNoMessage(200.millis)
  }

  test("controller error with a single candidate is attributed to that axis, then completed") {
    // errorCode!=0 evidence + exactly one axis whose current thread was
    // observed-cleared (fresh) with ae==1 → per-axis "Embedded program
    // error", and the watcher-ordering contract: axisErrorMsg lands, THEN
    // activeThread→0.
    val actor = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)))
    actor ! InternalStateActor.RegisterThread(3, Axis.B)
    actor ! InternalStateActor.ScanObservations(
      0x00, freshObservedAt(actor), Map(Axis.B -> 1), 17, Some("17 Program not valid"))
    Thread.sleep(50)
    val cmd = cmdStateOf(actor, Axis.B)
    cmd.axisErrorMsg shouldBe "Embedded program error: 17 Program not valid"
    cmd.activeThread shouldBe 0              // completion still attributed after the error
    axisStateOf(actor, Axis.B).axisState shouldBe AxisStateEnum.Error
  }

  test("controller error defers one scan, then attributes when the thread settles") {
    // errorCode latches before _XQ reports -1 for the dead thread. First scan:
    // evidence but thread still reads active → hold the TC text. Second scan:
    // thread observed-cleared with ae==1 → attributed with the HELD text (the
    // hardware latch was already consumed by CS's eager TC fetch).
    val actor = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.C)))
    actor ! InternalStateActor.RegisterThread(4, Axis.C)
    actor ! InternalStateActor.ScanObservations(
      1 << 4, freshObservedAt(actor), Map(Axis.C -> 1), 17, Some("17 Program not valid"))
    Thread.sleep(50)
    cmdStateOf(actor, Axis.C).axisErrorMsg shouldBe ""   // deferred, not yet attributed
    // Retry scan: errorCode back to 0 (latch consumed), thread now cleared.
    actor ! InternalStateActor.ScanObservations(0x00, freshObservedAt(actor), Map(Axis.C -> 1), 0, None)
    Thread.sleep(50)
    cmdStateOf(actor, Axis.C).axisErrorMsg shouldBe "Embedded program error: 17 Program not valid"
  }

  test("unattributable controller error faults the HCD after the one-scan defer and safes motors") {
    val actor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(Axis.A)))
    val cmdProbe = testKit.createTestProbe[GalilCommandMessage]()
    actor ! InternalStateActor.SetCommandActor(cmdProbe.ref)
    // No registry entry can explain the error; two scans exhaust the defer.
    actor ! InternalStateActor.ScanObservations(0x00, freshObservedAt(actor), Map.empty, 17, Some("17 Program not valid"))
    Thread.sleep(50)
    val hcdProbe1 = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(hcdProbe1.ref)
    hcdProbe1.receiveMessage().state should not be HcdStateEnum.Faulted  // deferred
    actor ! InternalStateActor.ScanObservations(0x00, freshObservedAt(actor), Map.empty, 0, None)
    Thread.sleep(50)
    val hcdProbe2 = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(hcdProbe2.ref)
    val faulted = hcdProbe2.receiveMessage()
    faulted.state shouldBe HcdStateEnum.Faulted
    faulted.controllerErrorMsg shouldBe "Controller Error: 17 Program not valid"
    // Defensive motor safing went out on the CI actor (command connection).
    val sent = cmdProbe.expectMessageType[GalilCommandMessage.SendCommand]
    sent.commandString shouldBe "ST;MO"
  }

  test("controller error with 2+ candidates faults the HCD immediately (multi-axis ambiguity)") {
    val actor = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)))
    actor ! InternalStateActor.RegisterThread(2, Axis.A)
    actor ! InternalStateActor.RegisterThread(3, Axis.B)
    actor ! InternalStateActor.ScanObservations(
      0x00, freshObservedAt(actor), Map(Axis.A -> 1, Axis.B -> 1), 17, Some("17 Program not valid"))
    Thread.sleep(50)
    val hcdProbe = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(hcdProbe.ref)
    hcdProbe.receiveMessage().state shouldBe HcdStateEnum.Faulted
  }

  test("controller-error attribution is suppressed while the HCD is already Faulted") {
    // A running embedded error handler can re-latch CMDERR every few seconds
    // post-fault; the evidence is consumed by CS's eager TC fetch and must be
    // dropped here (the old CS-side controllerFaulted gate, relocated).
    val actor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(Axis.A)))
    actor ! InternalStateActor.EnterFaulted("pre-existing fault")
    actor ! InternalStateActor.RegisterThread(2, Axis.A)
    actor ! InternalStateActor.ScanObservations(
      0x00, freshObservedAt(actor), Map(Axis.A -> 1), 17, Some("17 Program not valid"))
    Thread.sleep(50)
    // Neither per-axis attribution nor a new fault reason.
    cmdStateOf(actor, Axis.A).axisErrorMsg shouldBe ""
    val hcdProbe = testKit.createTestProbe[HcdState]()
    actor ! InternalStateActor.GetHcdState(hcdProbe.ref)
    hcdProbe.receiveMessage().controllerErrorMsg shouldBe "pre-existing fault"
  }

  test("ae codes 2/3/4 report per-axis errors directly from observations, deduped across scans") {
    val actor = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)))
    val watcher = testKit.createTestProbe[InternalStateActor.CmdStateChanged]()
    actor ! InternalStateActor.SubscribeCmdState(Axis.A, watcher.ref)
    actor ! InternalStateActor.ScanObservations(0x00, freshObservedAt(actor), Map(Axis.A -> 2), 0, None)
    Thread.sleep(50)
    cmdStateOf(actor, Axis.A).axisErrorMsg shouldBe "Position error exceeded limit"
    axisStateOf(actor, Axis.A).axisState shouldBe AxisStateEnum.Error
    watcher.expectMessageType[InternalStateActor.CmdStateChanged]
    // Same steady-state ae on the next scan: deduped, no second notification.
    actor ! InternalStateActor.ScanObservations(0x00, freshObservedAt(actor), Map(Axis.A -> 2), 0, None)
    watcher.expectNoMessage(200.millis)
  }

  test("registry activity is signalled to CS on empty↔non-empty transitions only") {
    val actor = testKit.spawn(InternalStateActor(
      HcdState().initializeAxis(Axis.A).initializeAxis(Axis.B)))
    val cs = testKit.createTestProbe[ControllerStatusActor.Command]()
    actor ! InternalStateActor.SetStatusActor(cs.ref)
    actor ! InternalStateActor.RegisterThread(2, Axis.A)
    cs.expectMessage(ControllerStatusActor.ThreadRegistryActivity(true))
    actor ! InternalStateActor.RegisterThread(3, Axis.B)      // still non-empty: edge-triggered, no signal
    cs.expectNoMessage(200.millis)
    // One fresh scan completes both entries; only the non-empty→empty edge signals.
    actor ! InternalStateActor.ScanObservations(0x00, freshObservedAt(actor), Map.empty, 0, None)
    cs.expectMessage(ControllerStatusActor.ThreadRegistryActivity(false))
    cs.expectNoMessage(200.millis)
  }

  test("GetAxisThread answers from the registry and excludes Halted entries") {
    val actor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(Axis.A)))
    val q = testKit.createTestProbe[Option[Int]]()
    actor ! InternalStateActor.GetAxisThread(Axis.A, q.ref)
    q.receiveMessage() shouldBe None                          // nothing registered
    actor ! InternalStateActor.RegisterThread(4, Axis.A)
    actor ! InternalStateActor.GetAxisThread(Axis.A, q.ref)
    q.receiveMessage() shouldBe Some(4)                       // registry-authoritative
    // Display-state divergence (S85 finding 4): clearActiveCommand zeroes
    // AxisCmdState.activeThread (e.g. on a watcher timeout) while the program
    // still runs — the registry answer must be unaffected.
    actor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("clearActiveCommand" -> true), testKit.system.ignoreRef)
    cmdStateOf(actor, Axis.A).activeThread shouldBe 0         // display state zeroed...
    actor ! InternalStateActor.GetAxisThread(Axis.A, q.ref)
    q.receiveMessage() shouldBe Some(4)                       // ...registry still knows
    val ack = testKit.createTestProbe[InternalStateActor.ThreadHaltedAck]()
    actor ! InternalStateActor.ThreadHalted(4, Axis.A, ack.ref)
    ack.receiveMessage(500.millis)
    actor ! InternalStateActor.GetAxisThread(Axis.A, q.ref)
    q.receiveMessage() shouldBe None                          // Halted: nothing to interrupt
  }
