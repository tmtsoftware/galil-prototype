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
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 8-axis thread-pool tests (S86).
 *
 * Exercises the full CommandHandlerActor → CI → InternalStateActor →
 * CommandWatcherActor chain against a POOL-FAITHFUL mock CI actor: unlike
 * LongRunningCommandTest's counter-based mock, PoolCIActor allocates through
 * the REAL ControllerCommandActor.selectThread policy (1-7 ascending, thread 0
 * last resort, S85), honors forceThread (the S84 reuse contract — closing the
 * PROJECT_STATE gap where CI mocks ignored it), reserves threads until
 * ReleaseThread, clears the hardware bit on HX, and rejects an XQ on a busy
 * thread exactly like the real controller (S86 fidelity, mirrored in the
 * simulator).
 *
 * The scenarios pin the S86 sentinel fixes with a fully populated 8-motor
 * controller:
 *   1. 8 concurrent moves allocate threads 1-7 then LEND thread 0 last.
 *   2. A command running on thread 0 completes only after scan attribution
 *      (the former 0-sentinel satisfied the watcher masks from the initial
 *      snapshot, and its 0→0 release edge never notified the watcher).
 *   3. stopAxis interrupts a thread-0 move: HX 0 + Halted mark + reuse of
 *      thread 0 via forceThread (the former getOrElse(0) skipped all three).
 *   4. After all 8 complete, the pool is fully recycled (next allocation is
 *      thread 1 again) and the registry is quiescent.
 */
class EightAxisThreadingTest extends AnyFunSuite with Matchers with BeforeAndAfterAll:

  private val testKit = ActorTestKit()
  private val hcdPrefix = Prefix("APS.ICS.GalilMotionTest")

  override def afterAll(): Unit =
    testKit.shutdownTestKit()

  // ========================================
  // Pool-faithful mock CI actor
  // ========================================

  object PoolCIActor:
    val commandLog = new ConcurrentLinkedQueue[String]()
    private val lock = new Object
    // Simulated hardware thread bitmask (MG _NO analog): bit set while a
    // "program" occupies the thread. Set on XQ, cleared on HX and on
    // ReleaseThread (in this mock a program "runs" until its completion is
    // attributed — the long-program regime; sub-scan completion is covered by
    // the live-simulator integration test).
    private var busyBits: Int = 0
    // Reservation set — the unobservedThreads analog (S82): reserved from XQ
    // until IS attributes the completion and sends ReleaseThread.
    private var unobserved: Set[Int] = Set.empty

    /** Occupy threads externally (e.g. 0xFE = threads 1-7 busy) to force the thread-0 lend. */
    def presetBusy(bits: Int): Unit = lock.synchronized { busyBits |= bits }
    def pool: (Int, Set[Int]) = lock.synchronized { (busyBits, unobserved) }
    def clear(): Unit = lock.synchronized {
      busyBits = 0; unobserved = Set.empty; commandLog.clear()
    }
    def commands: List[String] =
      import scala.jdk.CollectionConverters._
      commandLog.asScala.toList

    def behavior(): Behavior[GalilCommandMessage] =
      Behaviors.receiveMessage {
        case GalilCommandMessage.SendCommand(cmdString, replyTo) =>
          commandLog.add(cmdString)
          replyTo ! GalilCommandMessage.SendCommandResult(":")
          Behaviors.same

        case GalilCommandMessage.ExecuteProgram(label, replyTo, preCommands, forceThread) =>
          preCommands.foreach(cmd => commandLog.add(cmd))
          lock.synchronized {
            forceThread.orElse(ControllerCommandActor.selectThread(busyBits, unobserved)) match
              case None =>
                replyTo ! GalilCommandMessage.ExecuteProgramResult(
                  thread = -1, threadWasActive = false,
                  error = Some(s"No threads available to execute #$label"))
              case Some(thread) if (busyBits & (1 << thread)) != 0 =>
                // Only reachable via forceThread onto a busy thread (allocation
                // never selects a busy bit): the real controller rejects it.
                commandLog.add(s"XQ #$label,$thread [REJECTED busy]")
                replyTo ! GalilCommandMessage.ExecuteProgramResult(
                  thread = thread, threadWasActive = false,
                  error = Some(s"XQ #$label,$thread rejected: ?"))
              case Some(thread) =>
                busyBits |= (1 << thread)
                unobserved += thread
                commandLog.add(s"XQ #$label,$thread")
                replyTo ! GalilCommandMessage.ExecuteProgramResult(
                  thread = thread, threadWasActive = true, error = None)
          }
          Behaviors.same

        case GalilCommandMessage.HaltExecution(thread, axis, replyTo) =>
          // HX kills the program: hardware bit clears immediately. The
          // reservation is NOT released here — that is CH/IS's decision
          // (retained for reuse, or released via UnregisterThread→ReleaseThread).
          // CONTRACT (mirrors the real ControllerCommandActor, S86): every
          // thread INCLUDING 0 is halted — the real handler's former
          // `thread >= 1` guard was the B1 sentinel defect. If the real
          // handler ever re-grows a thread special case, this mock must not
          // mask it (the HcdIntegrationTest 8-axis test covers the real actor).
          lock.synchronized { busyBits &= ~(1 << thread) }
          commandLog.add(s"HX $thread")
          replyTo ! GalilCommandMessage.HaltExecutionResult(success = true, error = None)
          Behaviors.same

        case GalilCommandMessage.ReleaseThread(thread) =>
          lock.synchronized {
            busyBits &= ~(1 << thread)
            unobserved -= thread
          }
          Behaviors.same

        case _ =>
          Behaviors.same
      }

  // ========================================
  // Test infrastructure
  // ========================================

  private def createTestActors(
    axes: Seq[Axis]
  ): (ActorRef[CommandHandlerActor.Command], ActorRef[InternalStateActor.Command], ActorRef[GalilCommandMessage]) =
    PoolCIActor.clear()

    var state = HcdState()
    axes.foreach(a => state = state.initializeAxis(a))

    val isActor = testKit.spawn(InternalStateActor(state))
    val ciActor = testKit.spawn(PoolCIActor.behavior())
    // Wire IS → CI so completion attribution releases the mock's reservations
    // (the real GalilHcd.initialize() wiring).
    isActor ! InternalStateActor.SetCommandActor(ciActor)
    val loggerFactory = new LoggerFactory(hcdPrefix)
    val smStub = testKit.spawn(Behaviors.receiveMessage[ControllerStatusActor.Command] { _ => Behaviors.same })
    val handler = testKit.spawn(
      CommandHandlerActor.behavior(ciActor, isActor, null, loggerFactory, smStub)
    )
    (handler, isActor, ciActor)

  private def makeSetup(commandName: String, axis: String, target: Option[Double] = None): Setup =
    var setup = Setup(hcdPrefix, CommandName(commandName), None)
    val axisKey = ChoiceKey.make("axis", "A", "B", "C", "D", "E", "F", "G", "H")
    setup = setup.add(axisKey.set(axis))
    target.foreach(t => setup = setup.add(PositionAxisCommand.targetKey.set(t.toFloat)))
    setup

  private def setAxisIdle(isActor: ActorRef[InternalStateActor.Command], axis: Axis): Unit =
    isActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Idle, "inPositionThreshold" -> 10.0),
      testKit.system.ignoreRef)

  private def cmdStateOf(isActor: ActorRef[InternalStateActor.Command], axis: Axis): AxisCmdState =
    val probe = testKit.createTestProbe[Option[AxisCmdState]]()
    isActor ! InternalStateActor.GetAxisCmdState(axis, probe.ref)
    probe.receiveMessage().get

  /** Mailbox flush: a completed ask guarantees prior sends were processed. */
  private def barrier(isActor: ActorRef[InternalStateActor.Command]): Unit =
    val probe = testKit.createTestProbe[HcdState]()
    isActor ! InternalStateActor.GetHcdState(probe.ref)
    probe.receiveMessage()
    ()

  /** A timestamp guaranteed fresher than every registration already sent. */
  private def freshObservedAt(isActor: ActorRef[InternalStateActor.Command]): Long =
    barrier(isActor)
    System.nanoTime()

  // ========================================
  // Scenario 1+4: full-pool allocation, thread-0 lend, recycle
  // ========================================

  test("8 concurrent moves allocate threads 1-7 ascending, lend thread 0 last, and recycle the pool") {
    val axes = Axis.values.toSeq // A..H
    val (handler, isActor, _) = createTestActors(axes)
    axes.foreach(setAxisIdle(isActor, _))
    Thread.sleep(100)

    // Submit 8 moves; CH processes them in mailbox order, so allocation order
    // is deterministic: A→1, B→2, ... G→7, H→0 (last resort).
    axes.zipWithIndex.foreach { (axis, i) =>
      handler ! CommandHandlerActor.HandleCommand(
        makeSetup("positionAxis", axis.char.toString, Some(50000.0 + i)), Id(), None)
    }
    Thread.sleep(800) // 8 sequential ExecuteProgram round trips

    val xqs = PoolCIActor.commands.filter(_.startsWith("XQ #Move"))
    xqs should have size 8
    ('A' to 'G').zipWithIndex.foreach { (c, i) =>
      xqs should contain(s"XQ #Move$c,${i + 1}")
    }
    xqs.last shouldBe "XQ #MoveH,0" // the lend, and it is allocated LAST
    PoolCIActor.pool._2 shouldBe Set(0, 1, 2, 3, 4, 5, 6, 7)

    // Every axis shows its registered thread; H displays thread 0 (a real
    // value, NOT the released sentinel — S86).
    cmdStateOf(isActor, Axis.H).activeThread shouldBe 0

    // Complete all 8 in one fresh scan (every thread observed cleared).
    axes.zipWithIndex.foreach { (axis, i) =>
      isActor ! InternalStateActor.UpdateAxisState(axis,
        Map("position" -> (50000.0 + i)), testKit.system.ignoreRef)
      isActor ! InternalStateActor.UpdateAxisCmdState(axis,
        Map("moving" -> false), testKit.system.ignoreRef)
    }
    isActor ! InternalStateActor.ScanObservations(
      0x00, freshObservedAt(isActor), axes.map(_ -> 0).toMap, 0, None)
    Thread.sleep(300)

    // Registry quiescent: every activeThread at the -1 sentinel, pool empty.
    axes.foreach { axis =>
      cmdStateOf(isActor, axis).activeThread shouldBe -1
    }
    PoolCIActor.pool shouldBe ((0, Set.empty[Int]))

    // Pool recycled: the next allocation is thread 1 again.
    setAxisIdle(isActor, Axis.A)
    Thread.sleep(50)
    handler ! CommandHandlerActor.HandleCommand(
      makeSetup("positionAxis", "A", Some(60000.0)), Id(), None)
    Thread.sleep(300)
    PoolCIActor.commands.count(_ == "XQ #MoveA,1") shouldBe 2
  }

  // ========================================
  // Scenario 2: thread-0 completion requires scan attribution (S86 regression)
  // ========================================

  test("a command on thread 0 completes only after scan attribution (S86 sentinel regression)") {
    val (handler, isActor, _) = createTestActors(Seq(Axis.A))
    setAxisIdle(isActor, Axis.A)
    Thread.sleep(100)

    // Threads 1-7 hardware-busy → the move lands on thread 0.
    PoolCIActor.presetBusy(0xFE)
    handler ! CommandHandlerActor.HandleCommand(
      makeSetup("positionAxis", "A", Some(50000.0)), Id(), None)
    Thread.sleep(300)
    PoolCIActor.commands should contain("XQ #MoveA,0")
    cmdStateOf(isActor, Axis.A).activeThread shouldBe 0

    // Axis conditions satisfied (in position, not moving) but NO scan has
    // attributed the completion yet. Under the former 0-sentinel the watcher
    // mask (activeThread==0) was already satisfied here and the command
    // completed with zero scan confirmation.
    isActor ! InternalStateActor.UpdateAxisState(Axis.A,
      Map("position" -> 50000.0), testKit.system.ignoreRef)
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("moving" -> false), testKit.system.ignoreRef)
    Thread.sleep(300)
    val midFlight = cmdStateOf(isActor, Axis.A)
    midFlight.activeCommand should not be None // still running: no attribution yet
    midFlight.activeThread shouldBe 0

    // The fresh scan observes bit 0 cleared → attribution completes the
    // command: activeThread 0 → -1 (a REAL transition now, so the watcher is
    // notified — the former 0→0 edge was silent, S86 defect 3).
    isActor ! InternalStateActor.ScanObservations(
      0xFE, freshObservedAt(isActor), Map(Axis.A -> 0), 0, None)
    Thread.sleep(300)
    val done = cmdStateOf(isActor, Axis.A)
    done.activeCommand shouldBe None
    done.activeThread shouldBe -1
    PoolCIActor.pool._2 shouldBe Set.empty // reservation released
  }

  // ========================================
  // Scenario 3: interrupting a thread-0 program (S86 regression)
  // ========================================

  test("stopAxis interrupts a thread-0 move via HX 0 and reuses thread 0 (S86 interrupt regression)") {
    val (handler, isActor, _) = createTestActors(Seq(Axis.A))
    setAxisIdle(isActor, Axis.A)
    Thread.sleep(100)

    // Move lands on thread 0 (threads 1-7 hardware-busy).
    PoolCIActor.presetBusy(0xFE)
    handler ! CommandHandlerActor.HandleCommand(
      makeSetup("positionAxis", "A", Some(50000.0)), Id(), None)
    Thread.sleep(300)
    PoolCIActor.commands should contain("XQ #MoveA,0")

    // stopAxis while the thread-0 program runs. Under the former
    // getOrElse(0)/>0 collapse, checkAndInterrupt skipped the HX and the
    // Halted mark and tried to ALLOCATE a fresh thread (none available here:
    // 1-7 busy, 0 busy+reserved) — the stop hard-failed while a healthy
    // program kept running. With the Option preserved, the sequence is
    // HX 0 → ThreadHalted → reuse via forceThread=Some(0).
    handler ! CommandHandlerActor.HandleCommand(makeSetup("stopAxis", "A"), Id(), None)
    Thread.sleep(500)

    val cmds = PoolCIActor.commands
    cmds should contain("HX 0")
    cmds should contain("XQ #StopA,0")
    cmds.indexOf("HX 0") should be < cmds.indexOf("XQ #StopA,0")
    // Reuse retained the reservation — no release/reallocate race window.
    PoolCIActor.pool._2 shouldBe Set(0)

    // The stop completes via normal attribution on the same thread.
    isActor ! InternalStateActor.UpdateAxisCmdState(Axis.A,
      Map("moving" -> false), testKit.system.ignoreRef)
    isActor ! InternalStateActor.ScanObservations(
      0xFE, freshObservedAt(isActor), Map(Axis.A -> 0), 0, None)
    Thread.sleep(300)
    cmdStateOf(isActor, Axis.A).activeThread shouldBe -1
    PoolCIActor.pool._2 shouldBe Set.empty
  }

  // ========================================
  // forceThread contract at the CI seam (closes the S84 open item)
  // ========================================

  test("ExecuteProgram honors forceThread and rejects a forceThread onto a busy thread") {
    val (_, _, ciActor) = createTestActors(Seq(Axis.A))
    val probe = testKit.createTestProbe[GalilCommandMessage.ExecuteProgramResult]()

    // forceThread onto a free thread: honored verbatim (no allocation).
    ciActor ! GalilCommandMessage.ExecuteProgram("StopA", probe.ref, None, forceThread = Some(5))
    val r1 = probe.receiveMessage()
    r1.thread shouldBe 5
    r1.error shouldBe None

    // forceThread onto the SAME (now busy) thread: rejected like the real
    // controller ("?"), never silently retargeted.
    ciActor ! GalilCommandMessage.ExecuteProgram("MoveA", probe.ref, None, forceThread = Some(5))
    val r2 = probe.receiveMessage()
    r2.error should not be None
    r2.error.get should include("rejected")

    // forceThread = Some(0) is honored (S86: Option.orElse must not treat 0 as absent).
    ciActor ! GalilCommandMessage.ExecuteProgram("StopB", probe.ref, None, forceThread = Some(0))
    val r3 = probe.receiveMessage()
    r3.thread shouldBe 0
    r3.error shouldBe None
  }

  // ========================================
  // Command-timeout cleanup (S89)
  // ========================================

  /** Current AxisState for assertions. */
  private def axisStateOf(isActor: ActorRef[InternalStateActor.Command], axis: Axis): AxisState =
    val probe = testKit.createTestProbe[Option[AxisState]]()
    isActor ! InternalStateActor.GetAxisState(axis, probe.ref)
    probe.receiveMessage().get

  /** The thread IS currently has registered for an axis, if any. */
  private def registeredThread(isActor: ActorRef[InternalStateActor.Command], axis: Axis): Option[Int] =
    val probe = testKit.createTestProbe[Option[Int]]()
    isActor ! InternalStateActor.GetAxisThread(axis, probe.ref)
    probe.receiveMessage()

  test("a timed-out command halts its thread, stops the motor, releases the reservation and settles the axis") {
    val (handler, isActor, _) = createTestActors(Seq(Axis.A))
    setAxisIdle(isActor, Axis.A)
    Thread.sleep(100)

    handler ! CommandHandlerActor.HandleCommand(
      makeSetup("positionAxis", "A", Some(50000.0)), Id(), None)
    Thread.sleep(300)

    val thread = registeredThread(isActor, Axis.A).getOrElse(
      fail("no thread registered for axis A after positionAxis"))
    PoolCIActor.pool._2 should contain(thread)
    axisStateOf(isActor, Axis.A).axisState shouldBe AxisStateEnum.Moving

    // The watcher would normally send this after its timeout fires.
    handler ! CommandHandlerActor.CommandTimedOut(Axis.A, thread, "positionAxis", Id())
    Thread.sleep(400)

    // Thread halted and the motor explicitly stopped: nothing follows a timeout
    // that would stop it, unlike an interruption whose follow-on program may.
    PoolCIActor.commands should contain(s"HX $thread")
    PoolCIActor.commands should contain("ST A")

    // Reservation released — before S89 a timeout leaked it for the life of the HCD.
    PoolCIActor.pool._2 should not contain thread
    registeredThread(isActor, Axis.A) shouldBe None
    cmdStateOf(isActor, Axis.A).activeThread shouldBe -1

    // A timeout is a failure of the command, not evidence of an axis fault, so the
    // axis takes the same post-stop state a stopAxis would: Moving → Idle.
    axisStateOf(isActor, Axis.A).axisState shouldBe AxisStateEnum.Idle
  }

  test("a stale timeout for a thread the axis no longer owns leaves the axis untouched") {
    val (handler, isActor, _) = createTestActors(Seq(Axis.A))
    setAxisIdle(isActor, Axis.A)
    Thread.sleep(100)

    handler ! CommandHandlerActor.HandleCommand(
      makeSetup("positionAxis", "A", Some(50000.0)), Id(), None)
    Thread.sleep(300)

    val thread = registeredThread(isActor, Axis.A).getOrElse(
      fail("no thread registered for axis A after positionAxis"))
    val commandsBefore = PoolCIActor.commands

    // A timeout arriving for a DIFFERENT thread means a later command has taken the
    // axis over (or the thread was already released): its motion must not be disturbed.
    val staleThread = if thread == 7 then 6 else thread + 1
    handler ! CommandHandlerActor.CommandTimedOut(Axis.A, staleThread, "positionAxis", Id())
    Thread.sleep(300)

    PoolCIActor.commands shouldBe commandsBefore          // no HX, no ST
    registeredThread(isActor, Axis.A) shouldBe Some(thread)
    cmdStateOf(isActor, Axis.A).activeThread shouldBe thread
    axisStateOf(isActor, Axis.A).axisState shouldBe AxisStateEnum.Moving
  }
