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
 * Tests for CommandWatcherActor.
 *
 * Section 1: CompletionMask evaluation (pure unit tests, no actors)
 * Section 2: Actor behavior — successful completion, error detection, interruption, timeout
 * Section 3: Integration — full command lifecycle with real IS actor
 *
 * Thread completion is signalled via HcdState.threadStatus (updated by SM from QR _NO).
 * Axis-specific conditions (inPosition, moving, error) come from AxisCmdState via CmdStateChanged.
 *
 * Testing approach: CRM is null (handled gracefully by reportResult).
 * Test verification uses resultReporter callback and IS actor probes.
 */
class CommandWatcherActorTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()

  override def afterAll(): Unit =
    testKit.shutdownTestKit()

  import CommandWatcherActor._

  // ========================================
  // Test helpers
  // ========================================

  private class ResultCapture:
    private val results = new ConcurrentLinkedQueue[(Id, Boolean, String)]()
    private val latch = new CountDownLatch(1)

    def reporter: (Id, Boolean, String) => Unit = (id, success, msg) =>
      results.add((id, success, msg))
      latch.countDown()

    def awaitAndGet(timeout: FiniteDuration = 3.seconds): (Id, Boolean, String) =
      val ok = latch.await(timeout.toMillis, TimeUnit.MILLISECONDS)
      ok should be(true)
      val result = results.poll()
      result should not be (null)
      result

    def awaitNoResult(timeout: FiniteDuration = 500.millis): Boolean =
      !latch.await(timeout.toMillis, TimeUnit.MILLISECONDS)

  /**
   * Spawn a CommandWatcher with a real IS actor.
   *
   * @param activeThread    Thread number to watch (WatchConfig.activeThread)
   * @param initialCmdState Pre-set AxisCmdState before spawning (mirrors CH behavior)
   * @param initialThreadStatus Initial HcdState.threadStatus bitmask (simulate running thread)
   */
  private def spawnWatcher(
    axis: Axis = Axis.A,
    commandName: String = "positionAxis",
    activeThread: Int = 1,
    mask: CompletionMask = CompletionMask.positionAxis,
    timeout: FiniteDuration = 5.seconds,
    initialCmdState: Map[String, Any] = Map.empty,
    initialThreadStatus: Int = 0
  ): (ActorRef[CommandWatcherActor.Command], ResultCapture, ActorRef[InternalStateActor.Command]) =
    val isActor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(axis)))
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()

    // Set thread status (simulates SM reporting thread as active)
    if initialThreadStatus != 0 then
      isActor ! InternalStateActor.UpdateHcdState(Map("threadStatus" -> initialThreadStatus), probe.ref)
      probe.receiveMessage()

    // Pre-set AxisCmdState before spawning watcher (mirrors CH pushing activeThread at program start)
    if initialCmdState.nonEmpty then
      isActor ! InternalStateActor.UpdateAxisCmdState(axis, initialCmdState, probe.ref)
      probe.receiveMessage()

    val capture = new ResultCapture()
    val runId = Id()
    val config = WatchConfig(
      runId = runId,
      axis = axis,
      commandName = commandName,
      activeThread = activeThread,
      mask = mask,
      timeout = timeout,
      internalStateActor = isActor,
      commandResponseManager = null,
      resultReporter = Some(capture.reporter)
    )
    val watcher = testKit.spawn(CommandWatcherActor(config))
    (watcher, capture, isActor)

  /** Simulate SM reporting a thread as released (bit cleared in threadStatus). */
  private def releaseThread(isActor: ActorRef[InternalStateActor.Command], thread: Int): Unit =
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    isActor ! InternalStateActor.UpdateHcdState(Map("threadStatus" -> 0), probe.ref)
    probe.receiveMessage()

  // ========================================
  // Section 1: CompletionMask Unit Tests
  // ========================================

  test("CompletionMask with all None should match any AxisCmdState") {
    val mask = CompletionMask()
    mask.isSatisfied(AxisCmdState()) should be(true)
    mask.isSatisfied(AxisCmdState(moving = true, axisErrorMsg = "err")) should be(true)
  }

  test("CompletionMask.positionAxis: requires inPosition, no error, not moving") {
    val mask = CompletionMask.positionAxis
    // All conditions met
    mask.isSatisfied(AxisCmdState(inPosition = true, axisErrorMsg = "", moving = false)) should be(true)
    // Not in position
    mask.isSatisfied(AxisCmdState(inPosition = false, axisErrorMsg = "", moving = false)) should be(false)
    // Still moving
    mask.isSatisfied(AxisCmdState(inPosition = true, axisErrorMsg = "", moving = true)) should be(false)
    // Error present
    mask.isSatisfied(AxisCmdState(inPosition = true, axisErrorMsg = "motor fault", moving = false)) should be(false)
  }

  test("CompletionMask.homeAxis: does not check inPosition") {
    val mask = CompletionMask.homeAxis
    // inPosition=false still matches
    mask.isSatisfied(AxisCmdState(inPosition = false, axisErrorMsg = "", moving = false)) should be(true)
    mask.isSatisfied(AxisCmdState(inPosition = true, axisErrorMsg = "", moving = false)) should be(true)
    // Still moving — no match
    mask.isSatisfied(AxisCmdState(inPosition = false, axisErrorMsg = "", moving = true)) should be(false)
  }

  test("CompletionMask.stopAxis: only checks moving, ignores errors") {
    val mask = CompletionMask.stopAxis
    // Error present but still matches (stop doesn't care about errors)
    mask.isSatisfied(AxisCmdState(axisErrorMsg = "previous error", moving = false)) should be(true)
    // Still moving — no match
    mask.isSatisfied(AxisCmdState(moving = true)) should be(false)
  }

  test("CompletionMask: all specified conditions must be true") {
    val mask = CompletionMask(inPosition = Some(true), moving = Some(false))
    mask.isSatisfied(AxisCmdState(inPosition = false, moving = false)) should be(false)
    mask.isSatisfied(AxisCmdState(inPosition = true, moving = true)) should be(false)
    mask.isSatisfied(AxisCmdState(inPosition = true, moving = false)) should be(true)
  }

  test("CompletionMask: default AxisCmdState behavior") {
    val defaultState = AxisCmdState()
    // positionAxis: NO (inPosition defaults to false)
    CompletionMask.positionAxis.isSatisfied(defaultState) should be(false)
    // homeAxis: YES (doesn't check inPosition)
    CompletionMask.homeAxis.isSatisfied(defaultState) should be(true)
    // stopAxis: YES (not moving by default)
    CompletionMask.stopAxis.isSatisfied(defaultState) should be(true)
  }

  test("Error detection logic should respect mask expectations") {
    // positionAxis mask expects axisErrorMsg="" → error detection fires
    val posMask = CompletionMask.positionAxis
    val errState = AxisCmdState(axisErrorMsg = "limit switch")
    (errState.axisErrorMsg.nonEmpty && posMask.axisErrorMsg.contains("")) should be(true)

    // stopAxis mask doesn't check axisErrorMsg → error detection should NOT fire
    val stopMask = CompletionMask.stopAxis
    (errState.axisErrorMsg.nonEmpty && stopMask.axisErrorMsg.contains("")) should be(false)
  }

  // ========================================
  // Section 2: Actor Behavior Tests
  // ========================================

  test("CommandWatcher completes when thread released AND axis conditions met") {
    // Thread 1 active at start — watcher should not complete until it clears
    val threadBit = 1 << 1  // thread 1
    val (_, capture, isActor) = spawnWatcher(
      activeThread = 1,
      initialCmdState = Map("moving" -> true),
      initialThreadStatus = threadBit
    )
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()

    capture.awaitNoResult(200.millis) should be(true)

    // Axis reaches position — but thread still active, so no completion
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("inPosition" -> true), testKit.system.ignoreRef)
    Thread.sleep(50)
    capture.awaitNoResult(200.millis) should be(true)

    // Thread released (SM clears bit) — still not complete (moving still true)
    isActor ! InternalStateActor.UpdateHcdState(Map("threadStatus" -> 0), probe.ref)
    probe.receiveMessage()
    Thread.sleep(50)
    capture.awaitNoResult(200.millis) should be(true)

    // Motion stops — all conditions met
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("moving" -> false), testKit.system.ignoreRef)

    val (_, success, msg) = capture.awaitAndGet()
    success should be(true)
    msg should be("completed")
  }

  test("CommandWatcher completes immediately if thread already released at spawn") {
    // activeThread=1, but threadStatus=0 at spawn (fast completion case)
    val (_, capture, isActor) = spawnWatcher(
      activeThread = 1,
      initialCmdState = Map("inPosition" -> true, "moving" -> false),
      initialThreadStatus = 0  // thread already released
    )

    val (_, success, _) = capture.awaitAndGet()
    success should be(true)
  }

  test("CommandWatcher reports error on axis error") {
    val threadBit = 1 << 1
    val (_, capture, isActor) = spawnWatcher(
      activeThread = 1,
      initialCmdState = Map("moving" -> true),
      initialThreadStatus = threadBit
    )

    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("axisErrorMsg" -> "limit switch hit"), testKit.system.ignoreRef)

    val (_, success, msg) = capture.awaitAndGet()
    success should be(false)
    msg should include("limit switch hit")
  }

  test("CommandWatcher reports error on commandHalted") {
    val threadBit = 1 << 2
    val (_, capture, isActor) = spawnWatcher(
      axis = Axis.B,
      activeThread = 2,
      initialCmdState = Map("moving" -> true),
      initialThreadStatus = threadBit
    )

    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.B,
      Map("commandHalted" -> true), testKit.system.ignoreRef)

    val (_, success, msg) = capture.awaitAndGet()
    success should be(false)
    msg should include("interrupted")

    // Verify: halted flag cleared by watcher
    Thread.sleep(100)
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.B, probe.ref)
    probe.receiveMessage().get.commandHalted should be(false)
  }

  test("CommandWatcher reports error on timeout") {
    val threadBit = 1 << 1
    val (_, capture, isActor) = spawnWatcher(
      activeThread = 1,
      timeout = 300.millis,
      initialCmdState = Map("moving" -> true),
      initialThreadStatus = threadBit
    )

    val (_, success, msg) = capture.awaitAndGet(2.seconds)
    success should be(false)
    msg should include("timed out")
  }

  test("CommandWatcher with homeAxis mask completes without inPosition check") {
    val threadBit = 1 << 1
    val (_, capture, isActor) = spawnWatcher(
      commandName = "homeAxis",
      activeThread = 1,
      mask = CompletionMask.homeAxis,
      initialCmdState = Map("moving" -> true),
      initialThreadStatus = threadBit
    )
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()

    capture.awaitNoResult(200.millis) should be(true)

    // Thread released, stopped — should complete even with inPosition=false
    isActor ! InternalStateActor.UpdateHcdState(Map("threadStatus" -> 0), probe.ref)
    probe.receiveMessage()
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("moving" -> false), testKit.system.ignoreRef)

    val (_, success, _) = capture.awaitAndGet()
    success should be(true)
  }

  test("CommandWatcher with stopAxis mask ignores errors") {
    val threadBit = 1 << 1
    val (_, capture, isActor) = spawnWatcher(
      commandName = "stopAxis",
      activeThread = 1,
      mask = CompletionMask.stopAxis,
      initialCmdState = Map("moving" -> true, "axisErrorMsg" -> "previous fault"),
      initialThreadStatus = threadBit
    )
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()

    capture.awaitNoResult(200.millis) should be(true)

    // Thread released, stopped — should complete despite prior error
    isActor ! InternalStateActor.UpdateHcdState(Map("threadStatus" -> 0), probe.ref)
    probe.receiveMessage()
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("moving" -> false), testKit.system.ignoreRef)

    val (_, success, _) = capture.awaitAndGet()
    success should be(true)
  }

  // ========================================
  // Section 3: Integration
  // ========================================

  test("Full positionAxis lifecycle: thread release triggers re-evaluation of axis conditions") {
    val hcdState = HcdState()
      .initializeAxis(Axis.A)
      .updateAxis(Axis.A, Map("demand" -> 50000.0, "inPositionThreshold" -> 10.0))

    val isActor = testKit.spawn(InternalStateActor(hcdState))
    val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
    val capture = new ResultCapture()
    val runId = Id()

    // Start with thread 1 active
    val threadBit = 1 << 1
    isActor ! InternalStateActor.UpdateHcdState(Map("threadStatus" -> threadBit), probe.ref)
    probe.receiveMessage()

    val config = WatchConfig(
      runId = runId,
      axis = Axis.A,
      commandName = "positionAxis",
      activeThread = 1,
      mask = CompletionMask.positionAxis,
      timeout = 5.seconds,
      internalStateActor = isActor,
      commandResponseManager = null,
      resultReporter = Some(capture.reporter)
    )
    testKit.spawn(CommandWatcherActor(config))

    // CH sets activeThread in CmdState (mirrors real behavior)
    isActor ! InternalStateActor.RegisterThread(1, Axis.A)
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("activeThread" -> 1, "moving" -> true, "activeCommand" -> ActiveCommand.Move),
      testKit.system.ignoreRef)
    Thread.sleep(50)
    capture.awaitNoResult(200.millis) should be(true)

    // Position approaches demand → IS mirrors inPosition=true to CmdState
    isActor ! InternalStateActor.UpdateAxisState(Axis.A, Map("position" -> 49995.0), testKit.system.ignoreRef)
    Thread.sleep(50)
    capture.awaitNoResult(200.millis) should be(true) // thread still active

    // SM reports thread released and motion stopped
    isActor ! InternalStateActor.UpdateThreadStatus(0)
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A, Map("moving" -> false), testKit.system.ignoreRef)

    val (returnedId, success, _) = capture.awaitAndGet()
    success should be(true)
    returnedId should be(runId)

    // Verify clearActiveCommand cleared both activeCommand and activeThread
    Thread.sleep(100)
    val stateProbe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, stateProbe.ref)
    val finalState = stateProbe.receiveMessage().get
    finalState.activeCommand should be(None)
    finalState.activeThread should be(0)
  }