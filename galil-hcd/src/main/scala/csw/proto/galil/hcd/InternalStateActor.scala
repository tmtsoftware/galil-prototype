package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.logging.client.scaladsl.LoggerFactory

import java.time.Instant

/**
 * Internal State Actor - Central repository for all HCD operational data.
 * 
 * As described in SDD Section 4.6.6, this actor:
 * - Maintains current values for HCD status, per-axis state, and I/O data
 * - Provides thread-safe access for all other actors
 * - Notifies interested actors when state changes occur
 *
 * Two independent notification channels:
 *   1. StateChanged (Subscribe/Unsubscribe); for AxisState + HCD state changes.
 *      Used by CurrentStatePublisherActor.
 *   2. CmdStateChanged (SubscribeCmdState/UnsubscribeCmdState); for AxisCmdState changes.
 *      Used by CommandWatcher actors. Only fires when command-relevant fields change,
 *      avoiding noise from high-frequency position/velocity updates.
 * 
 * All state updates are atomic and thread-safe through the actor model.
 */
object InternalStateActor:
  
  // ========================================
  // Protocol
  // ========================================
  
  sealed trait Command
  
  /**
   * Update HCD-level state variables.
   * 
   * @param updates Map of field names to new values
   * @param replyTo Actor to send acknowledgment to
   */
  case class UpdateHcdState(
    updates: Map[String, Any], 
    replyTo: ActorRef[UpdateResponse]
  ) extends Command
  
  /**
   * Update operational state for a specific axis.
   * Triggers StateChanged notifications.
   * Also mirrors inPosition to AxisCmdState when it changes.
   * 
   * @param axis The axis to update (A-H)
   * @param updates Map of field names to new values
   * @param replyTo Actor to send acknowledgment to
   */
  case class UpdateAxisState(
    axis: Axis,
    updates: Map[String, Any],
    replyTo: ActorRef[UpdateResponse]
  ) extends Command
  
  /**
   * Update command execution state for a specific axis.
   * Triggers CmdStateChanged notifications (to CommandWatcher subscribers).
   * 
   * @param axis The axis to update (A-H)
   * @param updates Map of field names to new values
   * @param replyTo Actor to send acknowledgment to
   */
  case class UpdateAxisCmdState(
    axis: Axis,
    updates: Map[String, Any],
    replyTo: ActorRef[UpdateResponse]
  ) extends Command
  
  /**
   * Query current HCD state (includes both axis and cmd state).
   */
  case class GetHcdState(replyTo: ActorRef[HcdState]) extends Command
  
  /**
   * Query operational state for a specific axis.
   */
  case class GetAxisState(axis: Axis, replyTo: ActorRef[Option[AxisState]]) extends Command
  
  /**
   * Query command state for a specific axis.
   */
  case class GetAxisCmdState(axis: Axis, replyTo: ActorRef[Option[AxisCmdState]]) extends Command
  
  /**
   * Subscribe to operational state changes (AxisState + HCD state).
   * Used by CurrentStatePublisherActor.
   * 
   * @param subscriber Actor to receive notifications
   * @param filter Optional filter for which changes to receive
   */
  case class Subscribe(
    subscriber: ActorRef[StateChanged],
    filter: Option[SubscriptionFilter] = None
  ) extends Command
  
  /**
   * Unsubscribe from operational state changes.
   */
  case class Unsubscribe(subscriber: ActorRef[StateChanged]) extends Command
  
  /**
   * Subscribe to command state changes for a specific axis.
   * Used by CommandWatcher actors. The subscriber receives CmdStateChanged
   * messages only when the specified axis's AxisCmdState changes.
   * 
   * @param axis The axis to watch
   * @param subscriber Actor to receive notifications
   */
  case class SubscribeCmdState(
    axis: Axis,
    subscriber: ActorRef[CmdStateChanged]
  ) extends Command
  
  /**
   * Unsubscribe from command state changes.
   */
  case class UnsubscribeCmdState(subscriber: ActorRef[CmdStateChanged]) extends Command

  /**
   * Register a thread as executing a command on behalf of an axis.
   * IS will track the thread→axis mapping and automatically clear the axis's
   * activeThread when a ScanObservations reports the thread has stopped.
   * This replaces the former UpdateAxisCmdState(activeThread=N) approach and
   * eliminates any hardcoded axis-index-to-thread-number mapping.
   *
   * Stamps the entry's registeredAt staleness fence and signals CS's
   * polling-rate policy via ThreadRegistryActivity on the empty→non-empty
   * transition (ADR-001 Amendment A).
   *
   * Re-registering the SAME thread for the SAME axis is the normal S84
   * interrupt→follow-on reuse path: the entry transitions Halted → Active
   * with the new command's program.
   */
  case class RegisterThread(thread: Int, axis: Axis) extends Command

  /**
   * Wire ControllerStatusActor reference into IS.
   *
   * Called once from GalilHcd.initialize() after CS is spawned. Without it,
   * IS still functions but cannot signal ThreadRegistryActivity, so CS's
   * polling-rate policy loses its registered-threads term (observation of
   * completions then rides the axis-activity term alone). Sent as a normal
   * message rather than baked into the constructor because IS is spawned at
   * HCD construction (before config is loaded), while CS is created later in
   * initialize().
   */
  case class SetStatusActor(statusActor: ActorRef[ControllerStatusActor.Command]) extends Command

  /**
   * Complete raw observation set from one ControllerStatusActor QR scan
   * (ADR-001; supersedes UpdateThreadStatus). IS — owner of the authoritative
   * thread registry — performs ALL attribution from it, in this order:
   *
   *   1. Error attribution (controller-error evidence, ae[] codes) against the
   *      registry under the freshness invariant: ae[axis]==1 counts as a
   *      program failure ONLY when the thread CURRENTLY registered for that
   *      axis (and not marked halted) was observed-cleared by this same scan.
   *   2. Completion attribution: each non-halted registry entry whose
   *      thread's bit is clear and whose observation is fresh
   *      (observedAt > registeredAt) → activeThread=-1, CmdStateChanged fired,
   *      ReleaseThread forwarded to the CI actor.
   *
   * Errors are processed BEFORE completions so a watcher sees axisErrorMsg
   * before activeThread→-1 and fails the command instead of completing it —
   * the ordering contract that previously spanned two actors (CS pushed
   * axisErrorMsg, then sent UpdateThreadStatus last) is now local sequencing
   * in one handler.
   *
   * @param threadStatusByte bitmask over ALL 8 controller threads; bit N set =
   *        thread N executing. Built by CS from `MG _XQ0..7` (the
   *        authoritative source; QR's own byte can stay stale for seconds
   *        post-CMDERR), falling back to the raw QR byte if the _XQ read
   *        failed (fail-closed: a stale "running" bit only delays attribution
   *        one scan, never fabricates).
   * @param observedAt monotonic timestamp (System.nanoTime, same JVM) stamped
   *        by CS immediately BEFORE the _XQ read. THE STALENESS GATE (ADR-001
   *        Amendment A): a registry entry participates in attribution only if
   *        observedAt > entry.registeredAt. RegisterThread is only sent after
   *        the XQ succeeded, so registeredAt always postdates the program's
   *        actual start; any scan that read _XQ before the program started
   *        therefore carries observedAt < registeredAt and is excluded —
   *        regardless of how long the message sat in a mailbox. This closes
   *        the S85 storm race, where a scan observing a thread's PREVIOUS
   *        incarnation (completed program) was delivered up to ~1.4s late,
   *        after the thread had been released and reallocated, and both
   *        failed and completed the just-started command.
   * @param aeValues   per-axis embedded error flags (MG ae[i]); Map.empty when
   *        the read was suppressed (pre-#Init) or failed benignly.
   * @param errorCode  raw QR errorCode byte (0 = no latched controller error).
   * @param tcText     TC 1 text, eagerly fetched by CS iff errorCode != 0.
   *        Fetching consumes the hardware latch; IS carries this text across
   *        its one-scan attribution deferral instead of relying on the latch.
   */
  case class ScanObservations(
    threadStatusByte: Int,
    observedAt: Long,
    aeValues: Map[Axis, Int],
    errorCode: Int,
    tcText: Option[String]
  ) extends Command

  /**
   * Mark a registry entry Halted: CommandHandlerActor has deliberately stopped
   * this thread (HX in checkAndInterrupt) ahead of a follow-on command.
   *
   * A Halted entry is excluded from BOTH attribution kinds until it exits:
   *   - not completed (the interrupted command's response is owned by the
   *     watcher via the commandHalted pulse, not by scan attribution; and a
   *     later bit-clear must never fire a completion against the follow-on),
   *   - not error-attributed (residual ae[axis]==1 is the entry-time flag of
   *     a program we deliberately killed, not a failure — the S55 hazard).
   *
   * Exits from Halted: RegisterThread with the same thread+axis (S84 reuse —
   * entry returns to Active under the new command) or UnregisterThread
   * (non-reuse — entry removed, reservation released).
   *
   * Replaces CS.NotifyAxisHalted (ADR-001): the halted state used to be
   * encoded implicitly as absence from CS's axis→thread replica; it is now an
   * explicit, logged, testable registry state in the attribution authority.
   * The reply is the same synchronization point the CS ask provided: CH must
   * know the mark is in place before launching the follow-on program.
   */
  case class ThreadHalted(thread: Int, axis: Axis, replyTo: ActorRef[ThreadHaltedAck]) extends Command

  /** Acknowledgement of ThreadHalted (synchronization point only). */
  case class ThreadHaltedAck()

  /**
   * Explicitly remove a thread→axis registry entry after the thread was
   * halted (HX) by CommandHandlerActor's checkAndInterrupt, when the follow-on
   * will NOT reuse the thread.
   *
   * A Halted entry is excluded from scan attribution, so it has no natural
   * exit: this message is that exit — it removes the entry, clears
   * activeThread on the axis, and releases the CI actor's reservation so the
   * thread is allocatable again.
   *
   * No-op (debug log) if the entry is absent or maps to a different axis:
   * a scan completion may legitimately race the halt.
   */
  case class UnregisterThread(thread: Int, axis: Axis) extends Command

  /**
   * Wire ControllerCommandActor reference into IS.
   *
   * Called once from GalilHcd.initialize() after the CI actor is spawned.
   * IS uses it to send ReleaseThread when a registered thread's completion
   * is observed/attributed, returning the thread to the allocation pool, and
   * to send the defensive `ST;MO` (safe all motors) when an unattributable
   * controller error faults the HCD (ADR-001).
   * Same late-binding rationale as SetStatusActor.
   */
  case class SetCommandActor(commandActor: ActorRef[GalilCommandMessage]) extends Command

  /**
   * Internal: result of the "safe all motors" command (ST;MO) sent when the
   * HCD transitions to Faulted due to an unattributable controller error.
   * Fire-and-forget from the attribution logic's perspective; the handler
   * just logs the outcome. Failure does not propagate; the HCD is already
   * Faulted and the operator must intervene via faultReset anyway.
   */
  private[hcd] case class SafeAllMotorsResult(
    result: GalilCommandMessage.SendCommandResult,
    reason: String
  ) extends Command

  /**
   * One thread→axis registry entry.
   *
   * @param axis   the axis whose embedded program runs on this thread
   * @param halted true after CommandHandlerActor deliberately stopped the
   *               thread (HX; see ThreadHalted). Halted entries are excluded
   *               from scan attribution (completions AND errors) and exit via
   *               re-registration (S84 reuse) or UnregisterThread.
   * @param registeredAt monotonic timestamp (System.nanoTime) of registration
   *               processing. The staleness gate: only observations stamped
   *               AFTER this participate in attribution for this entry
   *               (ADR-001 Amendment A). Re-registration (S84 reuse) refreshes
   *               it, so scans from the halt window can never attribute
   *               against the follow-on command.
   */
  case class ThreadEntry(axis: Axis, halted: Boolean = false, registeredAt: Long = 0L)

  /**
   * Query the thread currently registered (and not Halted) for an axis —
   * the REGISTRY-authoritative answer, used by checkAndInterrupt to decide
   * whether an HX is needed (ADR-001 Amendment A).
   *
   * AxisCmdState.activeThread is display state and diverges from the registry
   * when a watcher timeout fires clearActiveCommand (which zeroes it) while
   * the program still runs: trusting it made a post-timeout stopAxis skip the
   * HX and the Halted mark, then allocate a fresh thread while the axis's
   * real thread was still registered and reserved (S85 finding 4).
   *
   * Halted entries answer None: their program is already stopped, so there is
   * nothing to interrupt (and checkAndInterrupt must not HX them again).
   */
  case class GetAxisThread(axis: Axis, replyTo: ActorRef[Option[Int]]) extends Command

  /**
   * Report connection status for one TCP handle.
   *
   * Sent by each connection actor when its TCP handle is established or lost.
   * IS updates the corresponding HcdState connection field and fires StateChanged
   * so subscribers (HMI, CurrentStatePublisher) see the change immediately.
   *
   * @param connection  Which handle: "commandConnection", "statusConnection", "consoleConnection"
   * @param status      New connection status
   */
  case class ReportConnectionStatus(
    connection: String,
    status: ConnectionStatus
  ) extends Command

  /**
   * Transition the HCD into Faulted state with a single atomic update.
   *
   * Consolidates the bookkeeping that must happen on every Faulted entry:
   *   - HcdState: state=Faulted, controllerErrorMsg=reason
   *   - Per-axis AxisState: any axis currently in Homing → Lost (position unknown);
   *     any axis in Moving or Tracking → Error (interrupted mid-motion)
   *   - Per-axis AxisCmdState: clearActiveCommand=true on axes that had an active command
   *
   * Callers:
   *   - InternalStateActor.handleScanObservations when a controller error
   *     cannot be attributed to a single axis (0 or 2+ candidates; ADR-001).
   *   - InternalStateActor.ReportConnectionStatus(Disconnected) handler; self-message
   *     after the connection field is updated.
   *   - GalilHcd.handleFaultReset when recovery fails.
   *
   * Idempotent: if HcdState is already Faulted, the message still re-applies axis
   * state transitions (harmless) and the reason text (which may be updated). This
   * simplifies callers; no need to check current state before sending.
   */
  case class EnterFaulted(reason: String) extends Command

  /**
   * Per-scan report from ControllerStatusActor with the latest `_PV<x>` (free FIFO slots)
   * and `_BT<x>` (segments executed since last BT) readings for axes that ControllerStatusActor
   * believes are currently tracking.
   *
   * IS's role on receipt: for each axis in the report, if `axisState == Tracking` and
   * a `trackingSession` exists, evaluate the preemptive underrun condition:
   *   - If TAI now > `session.lastValidTime` and no fresh `trackAxis` has arrived to
   *     advance the ledger: transition the axis to Error with axisError =
   *     "Tracking stream underrun" and clear the session.
   *
   * Watermark warnings (low free-slot count) are deferred to a future enhancement , 
   * the watermark policy hasn't been pinned down (1 slot? 2? configurable?), and the
   * preemptive TAI check catches every underrun before the FIFO actually empties,
   * so the warning is purely advance notice.
   *
   * If CS believes an axis is tracking but IS no longer does (race with stopAxis
   * arrival), the IS-side handler simply ignores the entry for that axis; the
   * authoritative source for the tracking lifecycle is IS, not the CS cache.
   *
   * @param readings Map[Axis, (freeFifoSlots, segmentsExecuted)]
   * @param observedAt TAI/wall-clock instant the reading was taken (captured by CS
   *   at the start of the read; carried so the IS-side TAI comparison uses the same
   *   timestamp the controller actually responded to, not whenever the message is
   *   eventually processed).
   */
  case class ReportPvtMonitoring(
    readings: Map[Axis, (Int, Int)],
    observedAt: java.time.Instant
  ) extends Command

  // ========================================
  // Responses
  // ========================================
  
  sealed trait Response
  
  case class UpdateResponse(success: Boolean, message: String = "") extends Response
  
  /**
   * Notification sent to operational state subscribers when state changes.
   */
  case class StateChanged(
    hcdState: HcdState,
    changedFields: Set[String],
    changedAxes: Set[Axis]
  ) extends Response
  
  /**
   * Notification sent to command state subscribers when axis cmd state changes.
   * Delivers only the changed axis and its new command state for efficient evaluation.
   */
  case class CmdStateChanged(
    axis: Axis,
    cmdState: AxisCmdState,
    changedFields: Set[String]
  ) extends Response
  
  // ========================================
  // Subscription Filters (for StateChanged)
  // ========================================
  
  /**
   * Filter for operational state subscription - allows selective notification.
   */
  sealed trait SubscriptionFilter:
    def matches(changedFields: Set[String], changedAxes: Set[Axis]): Boolean
  
  /**
   * Notify only when specific axes change.
   */
  case class AxisFilter(axes: Set[Axis]) extends SubscriptionFilter:
    def matches(changedFields: Set[String], changedAxes: Set[Axis]): Boolean =
      changedAxes.intersect(axes).nonEmpty
  
  /**
   * Notify only when specific fields change.
   */
  case class FieldFilter(fields: Set[String]) extends SubscriptionFilter:
    def matches(changedFields: Set[String], changedAxes: Set[Axis]): Boolean =
      changedFields.intersect(fields).nonEmpty
  
  /**
   * Notify when any axis reaches inPosition.
   */
  case object InPositionFilter extends SubscriptionFilter:
    def matches(changedFields: Set[String], changedAxes: Set[Axis]): Boolean =
      changedFields.contains("inPosition")
  
  // ========================================
  // Factory
  // ========================================
  
  def apply(loggerFactory: LoggerFactory, initialState: HcdState = HcdState()): Behavior[Command] =
    Behaviors.setup { context =>
      new InternalStateActor(context, loggerFactory, initialState)
    }

  /**
   * Convenience overloads for unit tests; avoids requiring a LoggerFactory in every test.
   * Uses a no-prefix LoggerFactory that satisfies the type contract but produces minimal output.
   */
  private def testLoggerFactory: LoggerFactory =
    new LoggerFactory(csw.prefix.models.Prefix("CSW.test"))

  def apply(initialState: HcdState): Behavior[Command] =
    apply(testLoggerFactory, initialState)

  def apply(): Behavior[Command] =
    apply(testLoggerFactory, HcdState())

/**
 * Actor implementation using Pekko Typed.
 */
class InternalStateActor(
  context: ActorContext[InternalStateActor.Command],
  loggerFactory: LoggerFactory,
  initialState: HcdState
) extends AbstractBehavior[InternalStateActor.Command](context):
  
  import InternalStateActor._
  
  // Current state (mutable, but only accessed within actor)
  private var currentState: HcdState = initialState
  
  // Operational state subscribers (CSP, etc.)
  private var subscribers: Set[ActorRef[StateChanged]] = Set.empty
  private var subscriptionFilters: Map[ActorRef[StateChanged], Option[SubscriptionFilter]] = Map.empty
  
  // Command state subscribers (CommandWatcher actors)
  // Maps subscriber to the axis they're watching
  private var cmdSubscribers: Map[ActorRef[CmdStateChanged], Axis] = Map.empty

  // Thread→axis registry: tracks which Galil thread is executing for which
  // axis — THE authoritative source for all attribution (ADR-001).
  // Written by RegisterThread (from CH after XQ); an entry is marked Halted
  // by ThreadHalted (from CH after HX) and excluded from attribution while so
  // marked. Exactly three exits, each of which also releases the CI actor's
  // thread reservation:
  //   1. ScanObservations observes a non-halted thread stopped (normal
  //      completion)
  //   2. UnregisterThread from CH after an HX without reuse
  //   3. RegisterThread invariant-violation recovery (should never occur;
  //      logged at ERROR)
  // (A fourth transition — RegisterThread on a Halted entry with the same
  // thread+axis — is the S84 reuse path: Halted → Active, no exit.)
  // No hardcoded axis↔thread mapping.
  private var threadRegistry: Map[Int, ThreadEntry] = Map.empty

  // Last registry-activity value forwarded to CS (ThreadRegistryActivity).
  // CS uses it only for the polling-rate policy; sent on empty↔non-empty
  // transitions to keep the signal edge-triggered and low-rate.
  private var lastNotifiedRegistryActive: Boolean = false

  // Controller-error evidence carried across the one-scan attribution
  // deferral (ADR-001). When a scan brings errorCode != 0 but no axis
  // satisfies the attribution invariant, the TC text is held here for exactly
  // one more scan: errorCode latches the instant a command fails, while
  // _XQ<n> may not report -1 for the dead thread until the next servo cycle.
  // The retry scan always resolves it: clean attribution (one candidate),
  // or HCD-wide fault (still none, or 2+). CS consumed the hardware latch at
  // eager TC fetch, so this field — not the latch — is the retry evidence.
  private var pendingControllerError: Option[String] = None

  // Per-axis last-reported axisErrorMsg (deduplication for steady-state ae[]
  // values like POSERR/LIMSWI/MCTIME that persist across scans). Cleared when
  // the axis leaves Error state (operator recovery via Home/Stop) in
  // handleUpdateAxisState.
  private var lastReportedAxisError: Map[Axis, String] = Map.empty

  // ControllerStatusActor reference, wired in via SetStatusActor after CS is
  // spawned. None until then; thread register/clear events are not forwarded
  // until CS is wired. (CS still functions without these; its axis→thread
  // map stays empty, disabling the per-axis program-error attribution path.)
  private var statusActor: Option[ActorRef[ControllerStatusActor.Command]] = None

  // ControllerCommandActor reference, wired in via SetCommandActor after the
  // CI actor is spawned. None until then; ReleaseThread is not sent until
  // wired. (Only affects the reservation gate in allocateThread; in unit
  // tests without a CI actor the registry still functions normally.)
  private var commandActor: Option[ActorRef[GalilCommandMessage]] = None
  
  private val log = loggerFactory.getLogger(context)
  
  override def onMessage(msg: Command): Behavior[Command] =
    msg match
      case UpdateHcdState(updates, replyTo) =>
        handleUpdateHcdState(updates, replyTo)
        
      case UpdateAxisState(axis, updates, replyTo) =>
        handleUpdateAxisState(axis, updates, replyTo)
        
      case UpdateAxisCmdState(axis, updates, replyTo) =>
        handleUpdateAxisCmdState(axis, updates, replyTo)
        
      case GetHcdState(replyTo) =>
        replyTo ! currentState
        Behaviors.same
        
      case GetAxisState(axis, replyTo) =>
        replyTo ! currentState.getAxis(axis)
        Behaviors.same
        
      case GetAxisCmdState(axis, replyTo) =>
        replyTo ! currentState.getCmdState(axis)
        Behaviors.same
        
      case Subscribe(subscriber, filter) =>
        log.debug(s"New state subscriber: $subscriber")
        subscribers = subscribers + subscriber
        subscriptionFilters = subscriptionFilters + (subscriber -> filter)
        // Send initial snapshot so CSP gets a non-None latestState immediately.
        // Without this, latestState stays None until the first QR-driven StateChanged
        // fires, so Publish1Hz silently no-ops and test subscriptions time out.
        subscriber ! StateChanged(currentState, Set.empty, Set.empty)
        Behaviors.same
        
      case Unsubscribe(subscriber) =>
        log.debug(s"Unsubscribing state: $subscriber")
        subscribers = subscribers - subscriber
        subscriptionFilters = subscriptionFilters - subscriber
        Behaviors.same
        
      case SubscribeCmdState(axis, subscriber) =>
        cmdSubscribers = cmdSubscribers + (subscriber -> axis)
        log.debug(s"SubscribeCmdState: axis=$axis total=${cmdSubscribers.size}")
        Behaviors.same
        
      case UnsubscribeCmdState(subscriber) =>
        log.debug(s"Unsubscribing cmd state: $subscriber")
        cmdSubscribers = cmdSubscribers - subscriber
        Behaviors.same

      case RegisterThread(thread, axis) =>
        handleRegisterThread(thread, axis)

      case ThreadHalted(thread, axis, replyTo) =>
        handleThreadHalted(thread, axis, replyTo)

      case UnregisterThread(thread, axis) =>
        handleUnregisterThread(thread, axis)

      case GetAxisThread(axis, replyTo) =>
        replyTo ! threadRegistry.collectFirst {
          case (t, e) if e.axis == axis && !e.halted => t
        }
        Behaviors.same

      case ScanObservations(threadStatusByte, observedAt, aeValues, errorCode, tcText) =>
        handleScanObservations(threadStatusByte, observedAt, aeValues, errorCode, tcText)

      case SafeAllMotorsResult(result, reason) =>
        if result.error.isDefined then
          log.warn(s"SafeAllMotors after fault ('$reason') — FAILED: ${result.error.get}. " +
            s"Motors may still be energized; controller may be unreachable.")
        else
          log.info(s"SafeAllMotors after fault ('$reason') — OK. All motion stopped, drives disabled.")
        Behaviors.same

      case SetStatusActor(sa) =>
        log.info(s"SetStatusActor: wiring CS reference for registry-activity signalling")
        statusActor = Some(sa)
        // Bring the newly-wired CS up to date if the registry is already
        // non-empty (unlikely at init-time wiring, but cheap and removes the
        // ordering assumption).
        if threadRegistry.nonEmpty then
          lastNotifiedRegistryActive = true
          sa ! ControllerStatusActor.ThreadRegistryActivity(true)
        Behaviors.same

      case SetCommandActor(ca) =>
        log.info(s"SetCommandActor: wiring CI reference for thread-reservation release")
        commandActor = Some(ca)
        Behaviors.same

      case ReportConnectionStatus(connection, status) =>
        // Update the specific connection field. If a command or status
        // connection is lost, also transition to Faulted via EnterFaulted
        // (which additionally handles per-axis state transitions).
        // Console connection loss does not fault the HCD (console is excluded
        // from isOperational).
        handleUpdateHcdState(Map(connection -> status), context.system.ignoreRef)
        if status == ConnectionStatus.Disconnected && connection != "consoleConnection" then
          val lostName = connection match
            case "commandConnection" => "Command"
            case "statusConnection"  => "Status"
            case other               => other
          context.self ! EnterFaulted(s"Connection lost: $lostName TCP connection disconnected")
        Behaviors.same

      case EnterFaulted(reason) =>
        handleEnterFaulted(reason)
        Behaviors.same

      case ReportPvtMonitoring(readings, observedAt) =>
        handleReportPvtMonitoring(readings, observedAt)
        Behaviors.same

  /**
   * Transition the HCD into Faulted state, applying per-axis state transitions
   * for any axes that were in motion-related states.
   *
   * State transitions per SDD Figure 4-2 (parallel to stopCompletionState):
   *   Homing   → Lost    (position unknown; incomplete homing)
   *   Moving   → Error   (interrupted mid-motion; was homed, so position known)
   *   Tracking → Error   (interrupted mid-track)
   *   Idle, Lost, Error → unchanged
   *
   * Also clears activeCommand on any axis that had one, so HMI and subscribers
   * see that no command is in flight. The CommandWatcher's HcdStateUpdate path
   * handles CRM notification separately (Error to the command caller).
   */
  private def handleEnterFaulted(reason: String): Unit =
    log.warn(s"EnterFaulted: $reason")

    // HCD-level update.  Also clear initializingReason 
    val hcdUpdates: Map[String, Any] = Map(
      "state"              -> HcdStateEnum.Faulted,
      "controllerErrorMsg" -> reason,
      "initializingReason" -> ""
    )
    handleUpdateHcdState(hcdUpdates, context.system.ignoreRef)

    // Per-axis transitions: iterate configured axes with an active motion state.
    currentState.axes.foreach { (axis, axisState) =>
      val newStateOpt: Option[AxisStateEnum] = axisState.axisState match
        case AxisStateEnum.Homing   => Some(AxisStateEnum.Lost)
        case AxisStateEnum.Moving   => Some(AxisStateEnum.Error)
        case AxisStateEnum.Tracking => Some(AxisStateEnum.Error)
        case _                      => None

      newStateOpt.foreach { newState =>
        log.info(s"EnterFaulted: axis $axis ${axisState.axisState} → $newState")
        // When the axis is leaving Tracking, also clear its tracking session ledger.
        // The invariant is `axisState == Tracking ⇔ trackingSession.isDefined`; any
        // path that breaks Tracking must clear the session in the same update.
        val axisUpdates: Map[String, Any] =
          if axisState.axisState == AxisStateEnum.Tracking then
            Map("axisState" -> newState, "trackingSession" -> None)
          else
            Map("axisState" -> newState)
        handleUpdateAxisState(axis, axisUpdates, context.system.ignoreRef)
      }

      // Clear activeCommand if set; regardless of prior axisState.
      currentState.getCmdState(axis).foreach { cmdState =>
        if cmdState.activeCommand.isDefined then
          handleUpdateAxisCmdState(axis,
            Map("clearActiveCommand" -> true),
            context.system.ignoreRef
          )
      }
    }

  /**
   * Evaluate per-axis PVT monitoring readings forwarded from ControllerStatusActor
   * and apply preemptive underrun detection.
   *
   * For each axis in the report we check: is the axis still in `axisState = Tracking`
   * AND does it have an active `trackingSession` AND is `observedAt` strictly later
   * than the session's `lastValidTime`?  If so, the FIFO has executed past the most
   * recent submitted segment without a fresh `trackAxis` advancing the ledger; the
   * controller will silently stop the motor at the end of that segment (or already
   * has).  We transition the axis to Error and clear the session.
   *
   * "Preemptive" means: we declare the fault as soon as TAI now > lastValidTime,
   * which is at the instant the *last* segment finishes executing.  The controller
   * itself will continue to honor the trailing portion of that segment but won't
   * emit any error; silent underrun.  By declaring Error preemptively the assembly
   * sees the fault immediately rather than after some indeterminate amount of
   * post-FIFO-empty silence.
   *
   * If CS believes an axis is tracking but IS no longer does (e.g. stopAxis just
   * arrived), we ignore that entry; IS is authoritative for the lifecycle.
   *
   * The free-FIFO-slots (`_PV`) and segments-executed (`_BT`) readings are not
   * acted on here in S64; they are retained in the message shape for future
   * watermark warnings and for diagnostic logging.
   */
  private def handleReportPvtMonitoring(
    readings: Map[Axis, (Int, Int)],
    observedAt: java.time.Instant
  ): Unit =
    readings.foreach { case (axis, (freeSlots, segmentsExecuted)) =>
      currentState.axes.get(axis).foreach { axisState =>
        if axisState.axisState == AxisStateEnum.Tracking then
          // Suppress underrun detection when a stop is in flight.  There is a
          // ~10-20ms race window between #StopX physically halting the motor
          // and the IS state update that transitions axisState from Tracking
          // to Idle: a CS poll inside this window sees Tracking + Some(session)
          // + observedAt past lastValidTime, which looks like an underrun but
          // is actually the expected end-of-session FIFO drainage.  Gating on
          // activeCommand catches it: stopAxis is already in flight (HCD set
          // activeCommand=Stop at dispatch), so the FIFO running out is OK.
          val stopInFlight = currentState.getCmdState(axis).exists(_.activeCommand.contains(ActiveCommand.Stop))
          if stopInFlight then
            log.debug(s"PVT monitor axis $axis: stop in flight; suppressing underrun check " +
                     s"(_PV=$freeSlots, _BT=$segmentsExecuted)")
          else
            axisState.trackingSession match
              case Some(session) =>
                if observedAt.isAfter(session.lastValidTime) then
                  val lateMicros =
                    java.time.Duration.between(session.lastValidTime, observedAt).toNanos / 1_000L
                  log.warn(s"PVT underrun detected on axis $axis: TAI now $observedAt is " +
                           s"${lateMicros}µs past lastValidTime ${session.lastValidTime} " +
                           s"(_PV=$freeSlots free slots, _BT=$segmentsExecuted executed, " +
                           s"${session.segmentsSubmitted} submitted)")
                  handleUpdateAxisState(axis,
                    Map(
                      "axisState"          -> AxisStateEnum.Error,
                      "axisError"          -> "Tracking stream underrun",
                      "trackingSession"    -> None,
                      "pvFreeSlots"        -> freeSlots,
                      "btSegmentsExecuted" -> segmentsExecuted
                    ),
                    context.system.ignoreRef
                  )
                  // Clear activeCommand so HMI/subscribers see no command in flight.
                  handleUpdateAxisCmdState(axis,
                    Map("clearActiveCommand" -> true),
                    context.system.ignoreRef
                  )
                else
                  log.debug(s"PVT monitor axis $axis: _PV=$freeSlots, _BT=$segmentsExecuted, " +
                           s"${session.segmentsSubmitted} submitted, lastValidTime=${session.lastValidTime}")
                  // Store the readings so the HMI tracking telemetry panel reflects
                  // live buffer state.  Only updates if values changed (handleUpdateAxisState
                  // change detection prevents redundant subscriber notifications).
                  if axisState.pvFreeSlots != freeSlots || axisState.btSegmentsExecuted != segmentsExecuted then
                    handleUpdateAxisState(axis,
                      Map(
                        "pvFreeSlots"        -> freeSlots,
                        "btSegmentsExecuted" -> segmentsExecuted
                      ),
                      context.system.ignoreRef
                    )
              case None =>
                // axisState=Tracking but no session; invariant violation.  Log and let
                // the next stopAxis / fault clean up.
                log.warn(s"PVT monitor axis $axis: axisState=Tracking but no trackingSession " +
                         s"(invariant violation); ignoring reading")
        // else: axis no longer Tracking by IS's authoritative view; CS's cache is
        // stale by one StateChanged delivery; ignore.
      }
    }

  /**
   * Update HCD-level state and notify operational state subscribers.
   */
  private def handleUpdateHcdState(
    updates: Map[String, Any],
    replyTo: ActorRef[UpdateResponse]
  ): Behavior[Command] =
    try
      currentState = currentState.update(updates)
      
      // Notify operational state subscribers
      notifyStateSubscribers(updates.keySet, Set.empty)
      
      replyTo ! UpdateResponse(success = true)
      Behaviors.same
    catch
      case ex: Exception =>
        log.error("Error updating HCD state" + s": ${ex.getMessage}")
        replyTo ! UpdateResponse(success = false, message = ex.getMessage)
        Behaviors.same

  /**
   * Update axis operational state and notify operational state subscribers.
   * Also mirrors inPosition changes to AxisCmdState.
   */
  private def handleUpdateAxisState(
    axis: Axis,
    updates: Map[String, Any],
    replyTo: ActorRef[UpdateResponse]
  ): Behavior[Command] =
    try
      // Get old axis state to detect auto-calculated changes
      val oldAxisState = currentState.getAxis(axis)
      
      // Apply updates
      currentState = currentState.updateAxis(axis, updates)

      // Invariant: axisError is meaningful only while axisState == Error.  Whenever
      // a transition leaves Error (operator recovery via stopAxis/homeAxis, or any
      // future automatic recovery), clear the axisError text so subscribers (HMI
      // banner, future EventService publication) don't see stale Error-state text
      // alongside the new non-Error axisState.
      //
      // Applied here (rather than asking every handler to remember "and clear
      // axisError") so the invariant is declarative: "axisState != Error ⇒
      // axisError == ''".  If a caller has explicitly set axisError in the same
      // update, that wins; the auto-clear only fires when no axisError key was
      // supplied (rare: only fault paths set it, and those simultaneously set
      // axisState=Error so the auto-clear branch doesn't fire anyway).
      val leavingError =
        oldAxisState.exists(_.axisState == AxisStateEnum.Error) &&
        currentState.getAxis(axis).exists(_.axisState != AxisStateEnum.Error)
      if leavingError && !updates.contains("axisError") then
        currentState = currentState.updateAxis(axis, Map("axisError" -> ""))

      // Clear the ae[] error dedup cache when the axis leaves Error state.
      // The cache must not persist past an operator recovery; without this,
      // the next occurrence of the same error on the same axis would not be
      // reported because the cached message still matches. (Moved here from
      // CS's StateChanged subscription with the attribution logic; ADR-001.)
      if leavingError && lastReportedAxisError.getOrElse(axis, "").nonEmpty then
        log.debug(s"Clearing lastReportedAxisError($axis): axis left Error state")
        lastReportedAxisError = lastReportedAxisError - axis

      // Invariant: trackingSession is meaningful only while axisState == Tracking.
      // The session ledger (lastTargetCounts, lastValidTime, btFiredAt) is the
      // anchor for computing the NEXT PVA segment's ΔP and T; if axisState leaves
      // Tracking (via stopAxis, fault, underrun, spontaneous motion, embedded
      // program error like #POSERR/#LIMSWI/#MCTIME, XQ communication failure, or
      // faultReset Init re-initialization), the ledger MUST be cleared.
      //
      // Historically this was enforced at each call site (stopAxis success path,
      // EnterFaulted, underrun detector).  But other transition paths
      // (CS.reportAxisError, CHA.setErrorState, IS.applySpontaneousMotion,
      // GalilHcd.applyAxisConfig) did not clear it.  Worse, EnterFaulted itself
      // only clears trackingSession when the prior axisState was Tracking; so a
      // sequence like "underrun fires → Error (clears session)" works, but
      // "embedded error fires → Error (does NOT clear session)" followed by a
      // later fault leaves a stale Some(_) indefinitely because EnterFaulted's
      // Tracking branch never fires.  The next trackAxis on that axis would then
      // SEED from the stale ledger, producing wild ΔP/T values that the controller
      // rejects with 'Number out of range', faulting the HCD.
      //
      // Make the invariant declarative: "axisState != Tracking ⇒
      // trackingSession == None".  If a caller has explicitly set trackingSession
      // in the same update (only handleTrackAxis does so, and only with axisState
      // = Tracking), that wins; the auto-clear only fires on transitions OUT.
      val leavingTracking =
        oldAxisState.exists(_.axisState == AxisStateEnum.Tracking) &&
        currentState.getAxis(axis).exists(_.axisState != AxisStateEnum.Tracking)
      if leavingTracking && !updates.contains("trackingSession") then
        currentState = currentState.updateAxis(axis, Map("trackingSession" -> None))
      // Also reset PVT monitoring readings to defaults so the HMI telemetry
      // panel shows clean values on the next session rather than the last-known
      // mid-session readings.  pvFreeSlots = 255 (empty buffer), btSegmentsExecuted = 0
      // (BT resets counter on next session start).
      if leavingTracking then
        if !updates.contains("pvFreeSlots") then
          currentState = currentState.updateAxis(axis, Map("pvFreeSlots" -> 255))
        if !updates.contains("btSegmentsExecuted") then
          currentState = currentState.updateAxis(axis, Map("btSegmentsExecuted" -> 0))

      // Get new axis state
      val newAxisState = currentState.getAxis(axis)
      
      // Detect ALL changed fields (including auto-calculated ones like inPosition
      // and the auto-cleared axisError / trackingSession above)
      val allChangedFields = (oldAxisState, newAxisState) match
        case (Some(oldAxis), Some(newAxis)) =>
          var changed = updates.keySet
          if oldAxis.inPosition != newAxis.inPosition then
            changed = changed + "inPosition"
          if oldAxis.axisError != newAxis.axisError then
            changed = changed + "axisError"
          if oldAxis.trackingSession != newAxis.trackingSession then
            changed = changed + "trackingSession"
          if oldAxis.pvFreeSlots != newAxis.pvFreeSlots then
            changed = changed + "pvFreeSlots"
          if oldAxis.btSegmentsExecuted != newAxis.btSegmentsExecuted then
            changed = changed + "btSegmentsExecuted"
          changed
        case _ =>
          updates.keySet
      
      // Notify operational state subscribers
      notifyStateSubscribers(allChangedFields, Set(axis))
      
      // Mirror inPosition to AxisCmdState if it changed.
      // AxisState.inPosition is auto-calculated from position/demand/threshold.
      // AxisCmdState.inPosition must track it so CommandWatchers see the change.
      if allChangedFields.contains("inPosition") then
        newAxisState.foreach { axState =>
          val oldCmdState = currentState.getCmdState(axis)
          currentState = currentState.updateCmdState(axis, Map("inPosition" -> axState.inPosition))
          val newCmdState = currentState.getCmdState(axis)
          
          // Only notify cmd subscribers if the value actually changed
          val cmdChanged = (oldCmdState, newCmdState) match
            case (Some(old), Some(nw)) => old.inPosition != nw.inPosition
            case _ => true
          if cmdChanged then
            newCmdState.foreach { cs =>
              notifyCmdSubscribers(axis, cs, Set("inPosition"))
            }
        }
      
      replyTo ! UpdateResponse(success = true)
      Behaviors.same
    catch
      case ex: Exception =>
        log.error(s"Error updating axis $axis state" + s": ${ex.getMessage}")
        replyTo ! UpdateResponse(success = false, message = ex.getMessage)
        Behaviors.same

  /**
   * Update axis command state and notify command state subscribers.
   * Only notifies subscribers watching the specific axis that changed.
   *
   * Spontaneous-motion detection: when an UpdateAxisCmdState carries moving=true
   * arriving while the axis is in a state where motion is unexpected (Idle/Lost)
   * and the HCD is Ready, transition the axis to Error with axisErrorMsg
   * "Spontaneous Motion". Suppressed when the
   * HCD is not Ready (axes may legitimately move during init's BZ commutation or
   * recovery sequences).
   */
  private def handleUpdateAxisCmdState(
    axis: Axis,
    updates: Map[String, Any],
    replyTo: ActorRef[UpdateResponse]
  ): Behavior[Command] =
    try
      // Get old cmd state to detect actual changes
      val oldCmdState = currentState.getCmdState(axis)
      
      // Apply updates
      currentState = currentState.updateCmdState(axis, updates)
      
      // Get new cmd state
      val newCmdState = currentState.getCmdState(axis)
      
      // Determine which fields actually changed (not just what was in the update map)
      val actuallyChanged = (oldCmdState, newCmdState) match
        case (Some(oldCmd), Some(newCmd)) =>
          updates.keySet.filter { field =>
            field match
              case "activeThread" => oldCmd.activeThread != newCmd.activeThread
              case "axisErrorMsg" => oldCmd.axisErrorMsg != newCmd.axisErrorMsg
              case "inPosition" => oldCmd.inPosition != newCmd.inPosition
              case "moving" => oldCmd.moving != newCmd.moving
              case "activeCommand" => oldCmd.activeCommand != newCmd.activeCommand
              case "clearActiveCommand" => oldCmd.activeCommand != newCmd.activeCommand
              case "commandHalted" => oldCmd.commandHalted != newCmd.commandHalted
              case "stopCode" => oldCmd.stopCode != newCmd.stopCode
              case _ => true  // Unknown field, assume changed
          }
        case _ =>
          updates.keySet  // No old state, all fields are new
      
      // Only notify if something actually changed
      if actuallyChanged.nonEmpty then
        newCmdState.foreach { cmdState =>
          notifyCmdSubscribers(axis, cmdState, actuallyChanged)
        }

      // Spontaneous-motion check. 
      val movingIsTrue = newCmdState.exists(_.moving)
      if movingIsTrue && currentState.state == HcdStateEnum.Ready then
        currentState.getAxis(axis).foreach { axState =>
          if axState.axisState == AxisStateEnum.Idle
             || axState.axisState == AxisStateEnum.Lost then
            log.warn(s"Spontaneous motion detected on axis $axis " +
              s"(axisState=${axState.axisState}, moving=true) — transitioning to Error")
            applySpontaneousMotion(axis)
        }

      replyTo ! UpdateResponse(success = true)
      Behaviors.same
    catch
      case ex: Exception =>
        log.error(s"Error updating axis $axis cmd state" + s": ${ex.getMessage}")
        replyTo ! UpdateResponse(success = false, message = ex.getMessage)
        Behaviors.same

  /**
   * Apply the Spontaneous-Motion error transition: set axisErrorMsg in
   * AxisCmdState and axisState=Error in AxisState, notifying subscribers on
   * both channels. 
   */
  private def applySpontaneousMotion(axis: Axis): Unit =
    val msg = "Spontaneous Motion"

    // AxisCmdState.axisErrorMsg
    currentState = currentState.updateCmdState(axis, Map("axisErrorMsg" -> msg))
    currentState.getCmdState(axis).foreach { cs =>
      notifyCmdSubscribers(axis, cs, Set("axisErrorMsg"))
    }

    // AxisState.axisState → Error
    currentState = currentState.updateAxis(axis, Map("axisState" -> AxisStateEnum.Error))
    notifyStateSubscribers(Set("axisState"), Set(axis))

  /**
   * Register a thread as executing a command on behalf of an axis.
   * Sets activeThread on the axis CmdState to the thread number and stores
   * the thread→axis registry entry for ScanObservations to resolve
   * attribution (completions and errors).
   *
   * Stamps registeredAt (the staleness fence for scan attribution) and
   * signals ThreadRegistryActivity to CS on the empty→non-empty transition.
   */
  private def handleRegisterThread(thread: Int, axis: Axis): Behavior[Command] =
    log.info(s"RegisterThread: thread=$thread → axis=$axis")

    threadRegistry.get(thread) match
      // Invariant check: the CI actor's allocation gate (unobservedThreads)
      // guarantees a thread is never reallocated before its previous completion
      // was observed and attributed — so an existing entry for this thread under
      // a DIFFERENT axis means the prevention invariant broke somewhere.
      // Recover (the prior program has necessarily finished on the controller;
      // allocation only reuses hardware-free threads) but log loudly: silent
      // clobbering here is exactly the S82 stuck-Homing bug.
      case Some(entry) if entry.axis != axis =>
        log.error(s"RegisterThread: INVARIANT VIOLATION — thread=$thread reallocated " +
          s"${entry.axis}→$axis before its completion was observed. Synthesizing missed " +
          s"completion for ${entry.axis}. Investigate: the CI reservation gate should " +
          s"have prevented this reallocation.")
        completeRegisteredThread(thread, entry.axis)
      // S84 interrupt→follow-on reuse: same thread, same axis, previously
      // marked Halted by checkAndInterrupt (which retained the reservation and
      // handed the thread back via forceThread). Normal path — the entry
      // returns to Active under the new command's program below.
      case Some(entry) if entry.halted =>
        log.debug(s"RegisterThread: thread=$thread reused for axis=$axis (Halted → Active)")
      case _ => ()

    // Stale reverse mapping: this axis may still be registered under an OLDER
    // thread whose completion was never observed (e.g. an HX whose
    // UnregisterThread was lost). One command per axis is enforced upstream,
    // so any other entry for this axis is stale. Drop it and release the
    // reservation, WITHOUT synthesizing a completion — activeThread is about
    // to be overwritten by this registration, and a later bit-clear on the
    // stale thread must not fire a completion against the new command.
    threadRegistry.collect { case (t, e) if e.axis == axis && t != thread => t }.foreach { staleThread =>
      log.warn(s"RegisterThread: dropping stale registry entry thread=$staleThread → axis=$axis " +
        s"(superseded by thread=$thread; releasing reservation)")
      threadRegistry = threadRegistry - staleThread
      commandActor.foreach(_ ! GalilCommandMessage.ReleaseThread(staleThread))
    }

    // registeredAt is the staleness fence (ADR-001 Amendment A): stamped at
    // registration processing, it necessarily postdates the program's XQ
    // (RegisterThread is only sent after the XQ succeeded), so any scan whose
    // _XQ read predates the program start carries an older observedAt and is
    // excluded from attributing this entry. Re-registration (S84 reuse)
    // refreshes the fence for the follow-on command.
    threadRegistry = threadRegistry + (thread -> ThreadEntry(axis, halted = false, registeredAt = System.nanoTime()))
    notifyRegistryActivity()

    // Set activeThread on the axis CmdState immediately so the watcher's
    // initial snapshot reflects the running thread. This prevents premature
    // completion on the stale released-sentinel (-1) from the last QR poll.
    val oldCmdState = currentState.getCmdState(axis)
    currentState = currentState.updateCmdState(axis, Map("activeThread" -> thread))
    val newCmdState = currentState.getCmdState(axis)

    // Only notify if value actually changed (it will have, from -1 to thread#)
    val changed = (oldCmdState, newCmdState) match
      case (Some(old), Some(nw)) => old.activeThread != nw.activeThread
      case _ => true
    if changed then
      newCmdState.foreach(cs => notifyCmdSubscribers(axis, cs, Set("activeThread")))

    Behaviors.same

  /**
   * Process the complete observation set from one ControllerStatusActor QR
   * scan: error attribution first, then completion attribution (ADR-001).
   *
   * ORDERING CONTRACT (previously spanning two actors, now local): errors are
   * reported before completions so a watcher's CmdStateChanged carries
   * axisErrorMsg before it sees activeThread→-1, failing the command instead
   * of completing it. reportAxisError and completeRegisteredThread both
   * notify subscribers synchronously, so statement order here IS delivery
   * order at the watcher.
   *
   * THE ATTRIBUTION INVARIANT (both kinds): a registry entry participates
   * only if it is not Halted AND its thread's bit is clear in
   * threadStatusByte AND the observation is FRESHER than the registration
   * (observedAt > registeredAt; ADR-001 Amendment A). The freshness clause is
   * what makes delivery latency harmless: a scan that read _XQ before this
   * entry's program started — however late it arrives — can never attribute
   * against it. The S82 completion race, the S85 stop_storm misattribution,
   * and the S85 storm's reallocation/reuse races are all instances of judging
   * an axis by an observation of a thread's PREVIOUS incarnation.
   */
  private def handleScanObservations(
    threadStatusByte: Int,
    observedAt: Long,
    aeValues: Map[Axis, Int],
    errorCode: Int,
    tcText: Option[String]
  ): Behavior[Command] =
    // Non-halted registry entries whose thread this scan observed as cleared,
    // with the observation made after the entry was registered.
    val clearedEntries: Map[Int, Axis] = threadRegistry.collect {
      case (t, e) if !e.halted && observedAt > e.registeredAt &&
          (threadStatusByte & (1 << t)) == 0 => t -> e.axis
    }
    val clearedAxes: Set[Axis] = clearedEntries.values.toSet

    // ---- Error attribution (must precede completions; see contract above) ----

    // Step 1: controller-error evidence — fresh this scan (errorCode != 0,
    // TC text eagerly fetched by CS) or held from last scan's deferral.
    // NOTE: on the deferral's retry scan errorCode is EXPECTED to be 0 (the
    // eager TC fetch consumed the hardware latch); pendingControllerError is
    // the evidence, and it always resolves on that retry scan — attributed,
    // or faulted. It can never dangle.
    val evidence: Option[String] =
      if errorCode != 0 then Some(tcText.getOrElse(s"$errorCode (description unavailable)"))
      else pendingControllerError
    val hadErrorEvidence = evidence.isDefined

    if currentState.state == HcdStateEnum.Faulted then
      // Already Faulted (attribution fault, connection loss, or recovery in
      // progress): suppress controller-error attribution — mirrors the old
      // CS-side controllerFaulted gate. A running embedded error handler can
      // re-latch CMDERR every few seconds while faulted; each is consumed by
      // CS's eager TC fetch and dropped here.
      evidence.foreach(text => log.debug(s"Controller-error evidence while Faulted — dropped: '$text'"))
      pendingControllerError = None
    else
      evidence.foreach { text =>
        // Candidate axes: current thread observed-cleared this scan AND the
        // embedded entry-flag ae[axis]==1 (program died before clearing it).
        val candidates = clearedAxes.filter(ax => aeValues.getOrElse(ax, 0) == 1)

        if candidates.size == 1 then
          // Clean per-axis attribution.
          val axis = candidates.head
          val msg  = s"Embedded program error: $text"
          log.warn(s"Axis $axis: program failed → $msg")
          reportAxisError(axis, msg)
          pendingControllerError = None
        else if candidates.isEmpty && pendingControllerError.isEmpty then
          // First scan with unattributable error: defer exactly one scan.
          // errorCode latches the instant a command fails, while _XQ<n> may
          // not report -1 for the dead thread until the next servo cycle;
          // attributing eagerly would miss the axis whose thread clears on
          // the very next scan. The TC text (latch already consumed by CS)
          // is held as the retry evidence.
          pendingControllerError = Some(text)
          log.debug(s"errorCode evidence '$text' but no axis program just completed " +
            s"(threadRegistry=$threadRegistry, observedAt=$observedAt, " +
            s"clearedAxes=$clearedAxes, aeValues=$aeValues) — deferring one scan to let _XQ<n> settle")
        else
          // Either (a) retry scan still without a candidate, or (b) 2+
          // candidates (multi-axis ambiguity). Both warrant HCD-wide fault.
          val reason = if candidates.isEmpty then
            "no axis program just completed (after one-scan defer)"
          else
            s"multiple axes just completed (${candidates.mkString(",")})"
          log.error(s"Controller Error: $text ($reason) — faulting HCD")
          pendingControllerError = None
          val faultMsg = s"Controller Error: $text"
          handleEnterFaulted(faultMsg)
          // Connection is alive (the scan that brought this evidence just
          // completed). Safe all motors: ST stops any motion, MO disables the
          // drives. Fire-and-forget via the CI actor; we're already Faulted,
          // the result is informational.
          safeAllMotors(faultMsg)
      }

    // Step 2: independent ae[] codes (POSERR/LIMSWI/MCTIME) on configured axes.
    // Reported regardless of errorCode or fault state; these embedded handlers
    // set ae[] without generating a controller error code. Skip ae==1 here:
    // that's program-flow (handled above / below when the thread clears) or
    // in-flight (ignored until the thread clears).
    aeValues.foreach { case (axis, ae) =>
      if ae >= 2 && ae <= 4 then
        val msg = aeDescription(ae)
        if lastReportedAxisError.getOrElse(axis, "") != msg then
          log.warn(s"Axis $axis: ae=$ae → $msg")
          reportAxisError(axis, msg)
    }

    // Step 3: edge case; program ended with ae[i]==1 but no controller error
    // evidence (neither fresh nor pending). Means the embedded program exited
    // without clearing ae[i] and without any TC error. Should not happen with
    // the current embedded design; log warn and treat as a per-axis error to
    // fail safe. Gated by the same attribution invariant as step 1 — only the
    // axis's CURRENT, observed-cleared, non-halted thread counts (the S85
    // stop_storm fix: a residual ae==1 from a just-launched program evaluated
    // against a stale thread no longer reaches this path).
    // Skip any axis with an error already reported (step 1 may have attributed
    // the same failure one scan earlier with a richer message).
    val unexplainedAxes = clearedAxes.filter { axis =>
      aeValues.getOrElse(axis, 0) == 1 && !hadErrorEvidence &&
        lastReportedAxisError.getOrElse(axis, "").isEmpty
    }
    unexplainedAxes.foreach { axis =>
      val msg = "Embedded program ended unexpectedly without controller error"
      // Forensic snapshot: this WARN historically appeared in the wake of
      // thread-attribution anomalies (S82, S85); the raw attribution inputs
      // make any recurrence diagnosable from logs alone.
      log.warn(s"Axis $axis: $msg (ae=1, no errorCode) " +
        s"[threadRegistry=$threadRegistry, observedAt=$observedAt, " +
        s"aeValues=$aeValues, clearedAxes=$clearedAxes]")
      reportAxisError(axis, msg)
    }

    // ---- Completion attribution (after errors; see contract above) ----
    clearedEntries.foreach { (thread, axis) =>
      log.info(s"Thread $thread completed → axis=$axis activeThread→-1")
      completeRegisteredThread(thread, axis)
    }

    Behaviors.same

  /**
   * Mark a registry entry Halted (see ThreadHalted): deliberately stopped by
   * CommandHandlerActor's checkAndInterrupt, excluded from scan attribution
   * until re-registered (S84 reuse) or unregistered. Ack unconditionally —
   * the reply is the synchronization point CH awaits before launching the
   * follow-on program.
   */
  private def handleThreadHalted(thread: Int, axis: Axis, replyTo: ActorRef[ThreadHaltedAck]): Behavior[Command] =
    threadRegistry.get(thread) match
      case Some(entry) if entry.axis == axis =>
        log.info(s"ThreadHalted: thread=$thread axis=$axis — marking Halted (excluded from attribution)")
        threadRegistry = threadRegistry + (thread -> entry.copy(halted = true))
      case Some(entry) =>
        log.warn(s"ThreadHalted: thread=$thread registered to ${entry.axis}, not $axis — ignoring")
      case None =>
        log.debug(s"ThreadHalted: thread=$thread not registered (completion already attributed); ignoring")
    replyTo ! ThreadHaltedAck()
    Behaviors.same

  /**
   * Send a per-axis error: sets axisErrorMsg (CmdState channel, so the
   * watcher fails the command) and axisState=Error (operational channel).
   * Called from handleScanObservations, always BEFORE any completion
   * attribution in the same scan — the direct synchronous calls preserve
   * notification order at the watcher.
   */
  private def reportAxisError(axis: Axis, msg: String): Unit =
    handleUpdateAxisCmdState(axis, Map("axisErrorMsg" -> msg), context.system.ignoreRef)
    handleUpdateAxisState(axis, Map("axisState" -> AxisStateEnum.Error), context.system.ignoreRef)
    lastReportedAxisError = lastReportedAxisError + (axis -> msg)

  /**
   * Map an embedded ae[] code to a descriptive axis error message.
   * Codes set by #POSERR (2), #LIMSWI (3), #MCTIME (4). Code 1 (program
   * failed / in flight) is handled separately because it requires the
   * thread-cleared correlation and TC text for context.
   */
  private def aeDescription(ae: Int): String = ae match
    case 2 => "Position error exceeded limit"
    case 3 => "Limit switch hit"
    case 4 => "Motion timeout"
    case _ => s"Embedded error code $ae"

  /**
   * Safe all motors on the controller by sending a compound ST;MO command via
   * the CI actor (command connection).
   *
   * Called from handleScanObservations when an unattributable controller
   * error forces the HCD into Faulted state — and ONLY from there: the other
   * Faulted-entry paths (connection loss, faultReset recovery failure)
   * involve a dead connection where sending would only IOException.
   * Defensive: if embedded code is corrupted or in an unknown state, we want
   * motors stopped (ST, all axes) and drives disabled (MO, all axes) rather
   * than left in whatever state they happened to be in.
   */
  private def safeAllMotors(reason: String): Unit =
    commandActor match
      case Some(ca) =>
        val adapter = context.messageAdapter[GalilCommandMessage.SendCommandResult](
          result => SafeAllMotorsResult(result, reason)
        )
        log.info(s"SafeAllMotors: sending 'ST;MO' (reason: $reason)")
        ca ! GalilCommandMessage.SendCommand("ST;MO", adapter)
      case None =>
        log.warn(s"SafeAllMotors: no CI actor wired — cannot send ST;MO ($reason)")

  /**
   * Attribute a registered thread's completion: remove the registry entry,
   * release the CI actor's reservation, clear activeThread on the owning
   * axis, and notify command-state subscribers.
   *
   * Shared by handleScanObservations (observed completion), the
   * RegisterThread invariant-violation recovery, and handleUnregisterThread
   * (explicit exit after HX without reuse).
   */
  private def completeRegisteredThread(thread: Int, axis: Axis): Unit =
    threadRegistry = threadRegistry - thread
    notifyRegistryActivity()

    // Return the thread to the CI actor's allocation pool.
    commandActor.foreach(_ ! GalilCommandMessage.ReleaseThread(thread))

    val oldCmdState = currentState.getCmdState(axis)
    currentState = currentState.updateCmdState(axis, Map("activeThread" -> -1))
    val newCmdState = currentState.getCmdState(axis)

    val changed = (oldCmdState, newCmdState) match
      case (Some(old), Some(nw)) => old.activeThread != nw.activeThread
      case _ => true
    if changed then
      newCmdState.foreach(cs => notifyCmdSubscribers(axis, cs, Set("activeThread")))

  /**
   * Signal CS on registry empty↔non-empty transitions (ThreadRegistryActivity;
   * ADR-001 Amendment A). Feeds only CS's polling-rate policy: action rate
   * while any thread is registered, so completion observation — and thus
   * reservation release — is bounded by one action-rate scan. Edge-triggered
   * so the signal stays low-rate.
   */
  private def notifyRegistryActivity(): Unit =
    val active = threadRegistry.nonEmpty
    if active != lastNotifiedRegistryActive then
      lastNotifiedRegistryActive = active
      statusActor.foreach(_ ! ControllerStatusActor.ThreadRegistryActivity(active))

  /**
   * Explicit registry exit for a thread halted via HX (see UnregisterThread).
   * Idempotent: if the entry is already gone (a scan completion raced the
   * halt) or maps to a different axis (a new command already registered the
   * thread), this is a benign no-op.
   */
  private def handleUnregisterThread(thread: Int, axis: Axis): Behavior[Command] =
    threadRegistry.get(thread) match
      case Some(entry) if entry.axis == axis =>
        log.info(s"UnregisterThread: thread=$thread axis=$axis (halted=${entry.halted}) — removing registry entry")
        completeRegisteredThread(thread, axis)
      case Some(entry) =>
        log.debug(s"UnregisterThread: thread=$thread now registered to ${entry.axis} (not $axis); ignoring")
      case None =>
        log.debug(s"UnregisterThread: thread=$thread not registered (completion already observed); ignoring")
    Behaviors.same

  /**
   * Notify operational state subscribers that match the filter.
   */
  private def notifyStateSubscribers(changedFields: Set[String], changedAxes: Set[Axis]): Unit =
    subscribers.foreach { subscriber =>
      val filter = subscriptionFilters.getOrElse(subscriber, None)
      
      val shouldNotify = filter match
        case None => true  // No filter = notify always
        case Some(f) => f.matches(changedFields, changedAxes)
      
      if shouldNotify then
        subscriber ! StateChanged(currentState, changedFields, changedAxes)
    }

  /**
   * Notify command state subscribers watching the specified axis.
   */
  private def notifyCmdSubscribers(axis: Axis, cmdState: AxisCmdState, changedFields: Set[String]): Unit =
    log.debug(s"CmdState $axis changed=$changedFields thread=${cmdState.activeThread} moving=${cmdState.moving}")
    cmdSubscribers.foreach { (subscriber, watchedAxis) =>
      if watchedAxis == axis then
        subscriber ! CmdStateChanged(axis, cmdState, changedFields)
    }