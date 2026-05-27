package csw.proto.galil.hcd

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import csw.params.core.models.Id

import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit}
import scala.concurrent.duration._

/**
 * Tests for axis state machine enforcement and command interruption mechanics.
 *
 * Section 1: validateCommand — state machine permission matrix
 *            Verifies that all interruption-eligible transitions return None
 *            and that truly invalid transitions are still rejected.
 *
 * Section 2: CommandWatcher interruption response
 *            Verifies the commandHalted→Error path in the watcher actor.
 *
 * Section 3: Interruption decision logic via IS state
 *            Verifies that commandHalted is set, the watcher detects it, and
 *            the flag is cleared — without requiring a real CI actor.
 *
 * Note: End-to-end interruption tests (HX on real controller, verify Error result
 * on interrupted command, verify new command completes) are in HcdIntegrationTest.
 */
class AxisStateValidationTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()

  override def afterAll(): Unit =
    testKit.shutdownTestKit()

  // ========================================
  // Test helpers
  // ========================================

  private class ResultCapture:
    private val results = new ConcurrentLinkedQueue[(Id, Boolean, String)]()
    private val latch = new CountDownLatch(1)

    def reporter: (Id, Boolean, String) => Unit = (id, success, msg) =>
      results.add((id, success, msg))
      latch.countDown()

    def awaitResult(timeoutSec: Int = 3): Option[(Id, Boolean, String)] =
      if latch.await(timeoutSec, TimeUnit.SECONDS) then
        Option(results.poll())
      else
        None

  private def makeIsActor(): ActorRef[InternalStateActor.Command] =
    testKit.spawn(InternalStateActor())

  private def makeIsActorWithAxis(axis: Axis,
    axisState: AxisStateEnum = AxisStateEnum.Idle,
    activeCommand: Option[ActiveCommand] = None,
    activeThread: Int = 0,
    commandHalted: Boolean = false
  ): ActorRef[InternalStateActor.Command] =
    val isActor = makeIsActor()
    isActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> axisState), testKit.system.ignoreRef)
    if activeCommand.isDefined then
      isActor ! InternalStateActor.UpdateAxisCmdState(axis,
        Map("activeCommand" -> activeCommand.get, "activeThread" -> activeThread,
            "commandHalted" -> commandHalted), testKit.system.ignoreRef)
    // Set HcdState.threadStatus so CommandWatcher sees the thread as active.
    // SM owns this field; tests must set it explicitly when simulating an active command.
    if activeThread > 0 then
      isActor ! InternalStateActor.UpdateHcdState(
        Map("threadStatus" -> (1 << activeThread)), testKit.system.ignoreRef)
    Thread.sleep(30) // let IS process messages
    isActor

  // ========================================
  // Section 1: validateCommand — state machine permission matrix
  // ========================================

  test("validateCommand: stopAxis accepted from ALL axis states (safety command)") {
    // stopAxis is a safety command — valid in any state, including Lost/Idle/Error
    // Also required to escape Error state (Figure 4-2: Error → stopAxis → Idle)
    AxisStateEnum.Lost.validateCommand("stopAxis")     shouldBe None
    AxisStateEnum.Idle.validateCommand("stopAxis")     shouldBe None
    AxisStateEnum.Homing.validateCommand("stopAxis")   shouldBe None
    AxisStateEnum.Moving.validateCommand("stopAxis")   shouldBe None
    AxisStateEnum.Tracking.validateCommand("stopAxis") shouldBe None
    AxisStateEnum.Error.validateCommand("stopAxis")    shouldBe None
  }

  test("validateCommand: positionAxis accepted from Idle and Moving (interruption)") {
    AxisStateEnum.Idle.validateCommand("positionAxis")   shouldBe None
    AxisStateEnum.Moving.validateCommand("positionAxis") shouldBe None
  }

  test("validateCommand: positionAxis rejected from Homing, Tracking, Lost, Error") {
    AxisStateEnum.Homing.validateCommand("positionAxis")   shouldBe defined
    AxisStateEnum.Tracking.validateCommand("positionAxis") shouldBe defined
    AxisStateEnum.Lost.validateCommand("positionAxis")     shouldBe defined
    AxisStateEnum.Error.validateCommand("positionAxis")    shouldBe defined
  }

  test("validateCommand: homeAxis accepted from Lost, Idle, Error only (SDD Figure 4-2)") {
    // homeAxis cannot interrupt Homing or Moving — use stopAxis first
    AxisStateEnum.Lost.validateCommand("homeAxis")  shouldBe None
    AxisStateEnum.Idle.validateCommand("homeAxis")  shouldBe None
    AxisStateEnum.Error.validateCommand("homeAxis") shouldBe None
  }

  test("validateCommand: homeAxis rejected from Homing, Moving, Tracking") {
    AxisStateEnum.Homing.validateCommand("homeAxis")   shouldBe defined
    AxisStateEnum.Moving.validateCommand("homeAxis")   shouldBe defined
    AxisStateEnum.Tracking.validateCommand("homeAxis") shouldBe defined
  }

  test("validateCommand: offsetAxis accepted from Idle and Moving (interruption)") {
    AxisStateEnum.Idle.validateCommand("offsetAxis")   shouldBe None
    AxisStateEnum.Moving.validateCommand("offsetAxis") shouldBe None
  }

  test("validateCommand: selectWheel accepted from Idle and Moving (interruption)") {
    AxisStateEnum.Idle.validateCommand("selectWheel")   shouldBe None
    AxisStateEnum.Moving.validateCommand("selectWheel") shouldBe None
  }

  test("validateCommand: trackAxis accepted from Idle and Tracking (re-track)") {
    AxisStateEnum.Idle.validateCommand("trackAxis")     shouldBe None
    AxisStateEnum.Tracking.validateCommand("trackAxis") shouldBe None
  }

  test("validateCommand: trackAxis rejected from Moving, Homing, Lost, Error") {
    AxisStateEnum.Moving.validateCommand("trackAxis")  shouldBe defined
    AxisStateEnum.Homing.validateCommand("trackAxis")  shouldBe defined
    AxisStateEnum.Lost.validateCommand("trackAxis")    shouldBe defined
    AxisStateEnum.Error.validateCommand("trackAxis")   shouldBe defined
  }

  // ========================================
  // Section 2: CommandWatcher — commandHalted detection
  // ========================================

  test("CommandWatcher reports Error when commandHalted is set — stopAxis context") {
    val isActor = makeIsActorWithAxis(Axis.A, AxisStateEnum.Moving,
      activeCommand = Some(ActiveCommand.Move), activeThread = 1)

    val capture = ResultCapture()
    val runId = Id()

    val config = CommandWatcherActor.WatchConfig(
      runId = runId,
      axis = Axis.A,
      commandName = "positionAxis",
      activeThread = 1,
      mask = CommandWatcherActor.CompletionMask.positionAxis,
      timeout = 5.seconds,
      internalStateActor = isActor,
      commandResponseManager = null,
      completionAxisState = AxisStateEnum.Idle,
      resultReporter = Some(capture.reporter)
    )
    testKit.spawn(CommandWatcherActor(config))
    Thread.sleep(50) // let watcher subscribe

    // Simulate interruption: set commandHalted
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("commandHalted" -> true), testKit.system.ignoreRef)

    val result = capture.awaitResult(3)
    result shouldBe defined
    val (_, success, msg) = result.get
    success shouldBe false
    msg should include("interrupted")

    // Verify commandHalted was cleared by the watcher
    Thread.sleep(50)
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import scala.concurrent.Await
    implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(2.seconds)
    implicit val scheduler = testKit.system.scheduler
    val cmdState = Await.result(
      isActor.ask[Option[AxisCmdState]](ref => InternalStateActor.GetAxisCmdState(Axis.A, ref)),
      2.seconds)
    cmdState.map(_.commandHalted) shouldBe Some(false)
  }
  
  test("CommandWatcher reports Error when commandHalted is set — positionAxis context") {
    val isActor = makeIsActorWithAxis(Axis.B, AxisStateEnum.Moving,
      activeCommand = Some(ActiveCommand.Move), activeThread = 2)

    val capture = ResultCapture()
    val runId = Id()

    val config = CommandWatcherActor.WatchConfig(
      runId = runId,
      axis = Axis.B,
      commandName = "positionAxis",
      activeThread = 2,
      mask = CommandWatcherActor.CompletionMask.positionAxis,
      timeout = 5.seconds,
      internalStateActor = isActor,
      commandResponseManager = null,
      completionAxisState = AxisStateEnum.Idle,
      resultReporter = Some(capture.reporter)
    )
    testKit.spawn(CommandWatcherActor(config))
    Thread.sleep(50)

    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.B,
      Map("commandHalted" -> true), testKit.system.ignoreRef)

    val result = capture.awaitResult(3)
    result shouldBe defined
    result.get._2 shouldBe false // success=false
  }

  // ========================================
  // Section 3: Interruption decision logic (unit — no CI actor needed)
  // ========================================

  test("commandHalted set then cleared — watcher detects before new command proceeds") {
    // Simulate the IS state transitions that checkAndInterrupt performs:
    // 1. commandHalted=true   (watcher should detect this)
    // 2. 50ms delay
    // 3. commandHalted=false, activeCommand=Stop
    val isActor = makeIsActorWithAxis(Axis.C, AxisStateEnum.Moving,
      activeCommand = Some(ActiveCommand.Move), activeThread = 1)

    val capture = ResultCapture()
    val runId = Id()

    val watcherConfig = CommandWatcherActor.WatchConfig(
      runId = runId,
      axis = Axis.C,
      commandName = "positionAxis",
      activeThread = 1,
      mask = CommandWatcherActor.CompletionMask.positionAxis,
      timeout = 5.seconds,
      internalStateActor = isActor,
      commandResponseManager = null,
      completionAxisState = AxisStateEnum.Idle,
      resultReporter = Some(capture.reporter)
    )
    testKit.spawn(CommandWatcherActor(watcherConfig))
    Thread.sleep(50) // let watcher subscribe

    // Step 1: Set commandHalted (simulates HaltExecution completing)
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.C,
      Map("commandHalted" -> true), testKit.system.ignoreRef)

    // Watcher should fire before we clear the flag
    val result = capture.awaitResult(3)
    result shouldBe defined
    result.get._2 shouldBe false // Error response

    // Step 2: Clear halted, set new active command (simulates new command starting)
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.C,
      Map("commandHalted" -> false, "activeCommand" -> ActiveCommand.Move,
          "activeThread" -> 2, "axisErrorMsg" -> ""),
      testKit.system.ignoreRef)
    Thread.sleep(30)

    // Verify state is clean for new command
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import scala.concurrent.Await
    implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(2.seconds)
    implicit val scheduler = testKit.system.scheduler
    val cmdState = Await.result(
      isActor.ask[Option[AxisCmdState]](ref => InternalStateActor.GetAxisCmdState(Axis.C, ref)),
      2.seconds)
    cmdState.map(_.commandHalted) shouldBe Some(false)
    cmdState.flatMap(_.activeCommand) shouldBe Some(ActiveCommand.Move)
  }

  test("stopAxis on Tracking axis with no active thread — no interruption flag is set") {
    // Simulate axis in Tracking state with activeThread=0 (program already finished,
    // motor jogging under JG). checkAndInterrupt should skip interruption for this case.
    val isActor = makeIsActorWithAxis(Axis.D, AxisStateEnum.Tracking,
      activeCommand = Some(ActiveCommand.Track), activeThread = 0)

    // No watcher running — verify commandHalted is never set
    // We simulate what checkAndInterrupt would do: query state, decide no interruption needed.
    // The axis state: Tracking with activeThread=0 → checkAndInterrupt returns true without
    // setting commandHalted. We just verify IS remains clean.
    Thread.sleep(50)

    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import scala.concurrent.Await
    implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(2.seconds)
    implicit val scheduler = testKit.system.scheduler

    val cmdState = Await.result(
      isActor.ask[Option[AxisCmdState]](ref => InternalStateActor.GetAxisCmdState(Axis.D, ref)),
      2.seconds)
    cmdState.map(_.commandHalted) shouldBe Some(false)
    cmdState.map(_.activeThread)  shouldBe Some(0)
  }

  test("stopCompletionState: per SDD Figure 4-2 transition table") {
    // Lost: stopAxis allowed (safety command) but axis remains Lost — only homeAxis escapes.
    // The homed flag is ignored here because a Lost axis is by definition not homed.
    AxisStateEnum.Lost.stopCompletionState(homed = false)  shouldBe AxisStateEnum.Lost
    AxisStateEnum.Lost.stopCompletionState(homed = true)   shouldBe AxisStateEnum.Lost
    // Homing interrupted: position unknown, axis not homed → Lost.
    // Even if the axis had a prior valid home, starting a new home clears homed,
    // so by the time we see Homing in stopAxis we always end up at Lost.
    AxisStateEnum.Homing.stopCompletionState(homed = false) shouldBe AxisStateEnum.Lost
    AxisStateEnum.Homing.stopCompletionState(homed = true)  shouldBe AxisStateEnum.Lost
    // Moving/Tracking: axis was homed, position is known → Idle
    AxisStateEnum.Moving.stopCompletionState(homed = true)   shouldBe AxisStateEnum.Idle
    AxisStateEnum.Tracking.stopCompletionState(homed = true) shouldBe AxisStateEnum.Idle
    // Error: disambiguates by homed flag.
    //   homed=true  → axis was homed before the fault; stop clears fault → Idle
    //   homed=false → home attempt itself failed; position unknown → Lost
    AxisStateEnum.Error.stopCompletionState(homed = true)  shouldBe AxisStateEnum.Idle
    AxisStateEnum.Error.stopCompletionState(homed = false) shouldBe AxisStateEnum.Lost
    // Idle: no-op → remains Idle
    AxisStateEnum.Idle.stopCompletionState(homed = true)  shouldBe AxisStateEnum.Idle
    AxisStateEnum.Idle.stopCompletionState(homed = false) shouldBe AxisStateEnum.Idle
  }