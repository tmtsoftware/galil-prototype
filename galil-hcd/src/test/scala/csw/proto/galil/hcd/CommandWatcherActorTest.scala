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

  /**
   * Thread-safe result capture for async watcher notifications.
   */
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

    /** Returns true if NO result arrived within timeout (for negative tests) */
    def awaitNoResult(timeout: FiniteDuration = 500.millis): Boolean =
      !latch.await(timeout.toMillis, TimeUnit.MILLISECONDS)

  /**
   * Spawn a CommandWatcher and return (watcherRef, resultCapture, isActor).
   *
   * @param initialCmdState Optional initial CmdState to set BEFORE spawning the watcher.
   *                        This mirrors what the real CommandHandler does: it pushes activeThread
   *                        to CmdState before creating the watcher, ensuring the watcher's initial
   *                        snapshot reflects the true state. Without this, there is a race between
   *                        the watcher's snapshot request and the test's state updates.
   */
  private def spawnWatcher(
    axis: Axis = Axis.A,
    commandName: String = "positionAxis",
    mask: CompletionMask = CompletionMask.positionAxis,
    timeout: FiniteDuration = 5.seconds,
    initialCmdState: Map[String, Any] = Map.empty
  ): (ActorRef[CommandWatcherActor.Command], ResultCapture, ActorRef[InternalStateActor.Command]) =
    val isActor = testKit.spawn(InternalStateActor(HcdState().initializeAxis(axis)))

    // Pre-set CmdState before spawning watcher (mirrors CommandHandler behavior)
    if initialCmdState.nonEmpty then
      val probe = testKit.createTestProbe[InternalStateActor.UpdateResponse]()
      isActor ! InternalStateActor.UpdateAxisCmdState(axis, initialCmdState, probe.ref)
      probe.receiveMessage() // wait for confirmation to ensure state is set

    val capture = new ResultCapture()
    val runId = Id()
    val config = WatchConfig(
      runId = runId,
      axis = axis,
      commandName = commandName,
      mask = mask,
      timeout = timeout,
      internalStateActor = isActor,
      commandResponseManager = null, // null-safe via reportResult
      resultReporter = Some(capture.reporter)
    )
    val watcher = testKit.spawn(CommandWatcherActor(config))
    (watcher, capture, isActor)

  // ========================================
  // Section 1: CompletionMask Unit Tests
  // ========================================

  test("CompletionMask with all None should match any state") {
    val mask = CompletionMask()
    mask.isSatisfied(AxisCmdState()) should be(true)
    mask.isSatisfied(AxisCmdState(activeThread = 5, moving = true, axisErrorMsg = "err")) should be(true)
  }

  test("CompletionMask.positionAxis should match correct state") {
    val mask = CompletionMask.positionAxis
    // Exact match
    mask.isSatisfied(AxisCmdState(
      activeThread = 0, inPosition = true, axisErrorMsg = "", moving = false
    )) should be(true)
    // Thread still active — no match
    mask.isSatisfied(AxisCmdState(
      activeThread = 1, inPosition = true, axisErrorMsg = "", moving = false
    )) should be(false)
    // Not in position — no match
    mask.isSatisfied(AxisCmdState(
      activeThread = 0, inPosition = false, axisErrorMsg = "", moving = false
    )) should be(false)
    // Still moving — no match
    mask.isSatisfied(AxisCmdState(
      activeThread = 0, inPosition = true, axisErrorMsg = "", moving = true
    )) should be(false)
    // Error present — no match
    mask.isSatisfied(AxisCmdState(
      activeThread = 0, inPosition = true, axisErrorMsg = "motor fault", moving = false
    )) should be(false)
  }

  test("CompletionMask.homeAxis should not check inPosition") {
    val mask = CompletionMask.homeAxis
    // inPosition=false should still match
    mask.isSatisfied(AxisCmdState(
      activeThread = 0, inPosition = false, axisErrorMsg = "", moving = false
    )) should be(true)
    // inPosition=true also matches
    mask.isSatisfied(AxisCmdState(
      activeThread = 0, inPosition = true, axisErrorMsg = "", moving = false
    )) should be(true)
    // Thread active — no match
    mask.isSatisfied(AxisCmdState(
      activeThread = 2, axisErrorMsg = "", moving = false
    )) should be(false)
  }

  test("CompletionMask.stopAxis should only check thread and moving") {
    val mask = CompletionMask.stopAxis
    // Error present but still matches (stop doesn't care about errors)
    mask.isSatisfied(AxisCmdState(
      activeThread = 0, inPosition = false, axisErrorMsg = "previous error", moving = false
    )) should be(true)
    // Still moving — no match
    mask.isSatisfied(AxisCmdState(
      activeThread = 0, moving = true
    )) should be(false)
  }

  test("CompletionMask should require all specified conditions") {
    val mask = CompletionMask(activeThread = Some(0), inPosition = Some(true))
    mask.isSatisfied(AxisCmdState(activeThread = 0, inPosition = false)) should be(false)
    mask.isSatisfied(AxisCmdState(activeThread = 1, inPosition = true)) should be(false)
    mask.isSatisfied(AxisCmdState(activeThread = 0, inPosition = true)) should be(true)
  }

  test("CompletionMask should handle default AxisCmdState") {
    val defaultState = AxisCmdState()
    // positionAxis: NO (inPosition=false)
    CompletionMask.positionAxis.isSatisfied(defaultState) should be(false)
    // homeAxis: YES (doesn't check inPosition)
    CompletionMask.homeAxis.isSatisfied(defaultState) should be(true)
    // stopAxis: YES
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

  test("CommandWatcher should complete when positionAxis mask is satisfied") {
    // Pre-set activeThread before watcher starts (mirrors CommandHandler behavior)
    val (watcher, capture, isActor) = spawnWatcher(
      initialCmdState = Map("activeThread" -> 1, "moving" -> true)
    )

    // Watcher should NOT complete yet (positionAxis needs inPosition=true)
    capture.awaitNoResult(200.millis) should be(true)

    // Simulate: position reached
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("inPosition" -> true), testKit.system.ignoreRef)
    Thread.sleep(50)
    capture.awaitNoResult(200.millis) should be(true) // still moving, thread active

    // Simulate: thread released, motion stopped
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("activeThread" -> 0, "moving" -> false), testKit.system.ignoreRef)

    // Now watcher should complete
    val (_, success, msg) = capture.awaitAndGet()
    success should be(true)
    msg should be("completed")
  }

  test("CommandWatcher should report error on axis error") {
    val (watcher, capture, isActor) = spawnWatcher(
      initialCmdState = Map("activeThread" -> 1, "moving" -> true)
    )

    // Simulate: error occurs
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("axisErrorMsg" -> "limit switch hit"), testKit.system.ignoreRef)

    val (_, success, msg) = capture.awaitAndGet()
    success should be(false)
    msg should include("limit switch hit")
  }

  test("CommandWatcher should report error on commandHalted") {
    val (watcher, capture, isActor) = spawnWatcher(
      axis = Axis.B,
      initialCmdState = Map("activeThread" -> 2, "moving" -> true)
    )

    // Simulate: command interrupted
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.B, Map("commandHalted" -> true), testKit.system.ignoreRef)

    val (_, success, msg) = capture.awaitAndGet()
    success should be(false)
    msg should include("interrupted")

    // Verify: halted flag should be cleared in IS actor
    Thread.sleep(100) // let the clear message propagate
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.B, probe.ref)
    val cmdState = probe.receiveMessage()
    cmdState.get.commandHalted should be(false)
  }

  test("CommandWatcher should report error on timeout") {
    // Use a very short timeout
    val (watcher, capture, isActor) = spawnWatcher(
      timeout = 300.millis,
      initialCmdState = Map("activeThread" -> 1, "moving" -> true)
    )

    // Command running but never completes — wait for timeout
    val (_, success, msg) = capture.awaitAndGet(2.seconds)
    success should be(false)
    msg should include("timed out")
  }

  test("CommandWatcher should not complete until ALL mask conditions met") {
    // Pre-set active thread (mirrors CommandHandler behavior)
    val (watcher, capture, isActor) = spawnWatcher(
      initialCmdState = Map("activeThread" -> 1, "moving" -> true)
    )

    // Watcher should not complete — positionAxis mask not satisfied
    capture.awaitNoResult(200.millis) should be(true)

    // Thread released, motion stopped — but inPosition still false
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("activeThread" -> 0, "moving" -> false), testKit.system.ignoreRef)
    Thread.sleep(50)
    capture.awaitNoResult(200.millis) should be(true) // positionAxis mask needs inPosition=true

    // Now set inPosition to satisfy the last condition
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("inPosition" -> true), testKit.system.ignoreRef)

    val (_, success, _) = capture.awaitAndGet()
    success should be(true)
  }

  test("CommandWatcher with homeAxis mask should complete without inPosition") {
    val (watcher, capture, isActor) = spawnWatcher(
      commandName = "homeAxis",
      mask = CompletionMask.homeAxis,
      initialCmdState = Map("activeThread" -> 1, "moving" -> true)
    )

    // Watcher should NOT complete yet (thread still active)
    capture.awaitNoResult(200.millis) should be(true)

    // Simulate: home complete — thread released, stopped, but inPosition=false
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("activeThread" -> 0, "moving" -> false), testKit.system.ignoreRef)

    val (_, success, _) = capture.awaitAndGet()
    success should be(true) // homeAxis doesn't check inPosition
  }

  test("CommandWatcher with stopAxis mask should ignore errors") {
    val (watcher, capture, isActor) = spawnWatcher(
      commandName = "stopAxis",
      mask = CompletionMask.stopAxis,
      initialCmdState = Map("activeThread" -> 1, "moving" -> true, "axisErrorMsg" -> "previous fault")
    )

    // Watcher should NOT complete yet (still moving)
    capture.awaitNoResult(200.millis) should be(true)

    // Thread released, stopped — should complete despite error
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("activeThread" -> 0, "moving" -> false), testKit.system.ignoreRef)

    val (_, success, _) = capture.awaitAndGet()
    success should be(true) // stopAxis doesn't check errors
  }

  test("CommandWatcher should not complete on stale pre-command state") {
    // The CommandHandler pushes activeThread to CmdState before spawning the watcher.
    // This test verifies the watcher's initial snapshot sees activeThread > 0,
    // preventing premature completion on masks that check activeThread==0.
    val (watcher, capture, isActor) = spawnWatcher(
      commandName = "selectWheel",
      mask = CompletionMask.selectWheel,
      initialCmdState = Map("activeThread" -> 3)
    )

    // Watcher should NOT complete — activeThread=3 doesn't satisfy mask (needs 0)
    capture.awaitNoResult(300.millis) should be(true)

    // Motor starts moving
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("moving" -> true), testKit.system.ignoreRef)
    Thread.sleep(50)
    capture.awaitNoResult(200.millis) should be(true) // still active

    // Complete: thread released, motion stopped
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("activeThread" -> 0, "moving" -> false), testKit.system.ignoreRef)

    val (_, success2, _) = capture.awaitAndGet()
    success2 should be(true)
  }

  // ========================================
  // Section 3: Integration
  // ========================================

  test("Full positionAxis lifecycle with dual-channel notification") {
    val hcdState = HcdState()
      .initializeAxis(Axis.A)
      .updateAxis(Axis.A, Map("demand" -> 50000.0, "inPositionThreshold" -> 10.0))

    val isActor = testKit.spawn(InternalStateActor(hcdState))
    val capture = new ResultCapture()
    val runId = Id()

    val config = WatchConfig(
      runId = runId,
      axis = Axis.A,
      commandName = "positionAxis",
      mask = CompletionMask.positionAxis,
      timeout = 5.seconds,
      internalStateActor = isActor,
      commandResponseManager = null,
      resultReporter = Some(capture.reporter)
    )
    val watcher = testKit.spawn(CommandWatcherActor(config))

    // Phase 1: CommandHandler sets activeThread, moving
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A,
      Map("activeThread" -> 1, "moving" -> true, "activeCommand" -> ActiveCommand.Move),
      testKit.system.ignoreRef
    )
    Thread.sleep(50)
    capture.awaitNoResult(200.millis) should be(true)

    // Phase 2: StatusMonitor updates position (AxisState, not CmdState)
    isActor ! InternalStateActor.UpdateAxisState(
      Axis.A, Map("position" -> 49995.0), testKit.system.ignoreRef)
    Thread.sleep(50)
    // inPosition not yet true (|49995-50000| = 5, > threshold? No, threshold is 10, so 5 < 10 → inPosition=true!)
    // Actually: |49995 - 50000| = 5.0 <= 10.0 → inPosition=true
    // This should trigger mirroring to CmdState!

    // Phase 3: Position closer, then thread released
    isActor ! InternalStateActor.UpdateAxisCmdState(
      Axis.A, Map("activeThread" -> 0, "moving" -> false), testKit.system.ignoreRef)

    // The inPosition was already mirrored in Phase 2
    val (returnedId, success, msg) = capture.awaitAndGet()
    success should be(true)
    returnedId should be(runId)

    // Verify activeCommand was cleared
    Thread.sleep(100)
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(Axis.A, probe.ref)
    val finalState = probe.receiveMessage()
    finalState.get.activeCommand should be(None)
  }