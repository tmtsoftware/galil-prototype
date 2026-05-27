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
 *   1. StateChanged (Subscribe/Unsubscribe) — for AxisState + HCD state changes.
 *      Used by CurrentStatePublisherActor.
 *   2. CmdStateChanged (SubscribeCmdState/UnsubscribeCmdState) — for AxisCmdState changes.
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
   * activeThread when UpdateThreadStatus reports the thread has stopped.
   * This replaces the former UpdateAxisCmdState(activeThread=N) approach and
   * eliminates any hardcoded axis-index-to-thread-number mapping.
   *
   * IS also forwards a RegisterAxisThread to ControllerStatusActor (if known)
   * so CS can interpret per-axis ae[] reads correctly.
   */
  case class RegisterThread(thread: Int, axis: Axis) extends Command

  /**
   * Wire ControllerStatusActor reference into IS.
   *
   * Called once from GalilHcd.initialize() after CS is spawned. Without it,
   * IS will still function but won't forward thread register/clear events to
   * CS — which means CS's axis→thread map stays empty and its ae[] decision
   * logic cannot attribute program errors to specific axes. Sent as a normal
   * message rather than baked into the constructor because IS is spawned at
   * HCD construction (before config is loaded), while CS is created later in
   * initialize().
   */
  case class SetStatusActor(statusActor: ActorRef[ControllerStatusActor.Command]) extends Command

  /**
   * Report current hardware thread status bitmask (_NO register value).
   * IS compares against registered threads to detect completions:
   * for each registered (thread→axis), if the thread's bit is now clear,
   * IS sets activeThread=0 on the owning axis and fires CmdStateChanged.
   * Sent by StatusMonitor on every poll cycle.
   */
  case class UpdateThreadStatus(threadStatusByte: Int) extends Command

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
   *   - ControllerStatusActor.decideAxisAndControllerErrors when a controller
   *     error cannot be attributed to a single axis (0 or 2+ candidates).
   *   - InternalStateActor.ReportConnectionStatus(Disconnected) handler — self-message
   *     after the connection field is updated.
   *   - GalilHcd.handleFaultReset when recovery fails.
   *
   * Idempotent: if HcdState is already Faulted, the message still re-applies axis
   * state transitions (harmless) and the reason text (which may be updated). This
   * simplifies callers — no need to check current state before sending.
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
   * Watermark warnings (low free-slot count) are deferred to a future enhancement —
   * the watermark policy hasn't been pinned down (1 slot? 2? configurable?), and the
   * preemptive TAI check catches every underrun before the FIFO actually empties,
   * so the warning is purely advance notice.
   *
   * If CS believes an axis is tracking but IS no longer does (race with stopAxis
   * arrival), the IS-side handler simply ignores the entry for that axis — the
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
   * Convenience overloads for unit tests — avoids requiring a LoggerFactory in every test.
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

  // Thread→axis registry: tracks which Galil thread is executing for which axis.
  // Written by RegisterThread (from CH after XQ), cleared when UpdateThreadStatus
  // detects the thread has stopped. No hardcoded axis↔thread mapping.
  private var threadRegistry: Map[Int, Axis] = Map.empty
  private var lastThreadStatusByte: Int = 0

  // ControllerStatusActor reference, wired in via SetStatusActor after CS is
  // spawned. None until then; thread register/clear events are not forwarded
  // until CS is wired. (CS still functions without these — its axis→thread
  // map stays empty, disabling the per-axis program-error attribution path.)
  private var statusActor: Option[ActorRef[ControllerStatusActor.Command]] = None
  
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

      case UpdateThreadStatus(threadStatusByte) =>
        handleUpdateThreadStatus(threadStatusByte)

      case SetStatusActor(sa) =>
        log.info(s"SetStatusActor: wiring CS reference for axis-thread forwarding")
        statusActor = Some(sa)
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
   *   Homing   → Lost    (position unknown — incomplete homing)
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

      // Clear activeCommand if set — regardless of prior axisState.
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
   * recent submitted segment without a fresh `trackAxis` advancing the ledger — the
   * controller will silently stop the motor at the end of that segment (or already
   * has).  We transition the axis to Error and clear the session.
   *
   * "Preemptive" means: we declare the fault as soon as TAI now > lastValidTime,
   * which is at the instant the *last* segment finishes executing.  The controller
   * itself will continue to honor the trailing portion of that segment but won't
   * emit any error — silent underrun.  By declaring Error preemptively the assembly
   * sees the fault immediately rather than after some indeterminate amount of
   * post-FIFO-empty silence.
   *
   * If CS believes an axis is tracking but IS no longer does (e.g. stopAxis just
   * arrived), we ignore that entry — IS is authoritative for the lifecycle.
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
                    "axisState"       -> AxisStateEnum.Error,
                    "axisError"       -> "Tracking stream underrun",
                    "trackingSession" -> None
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
            case None =>
              // axisState=Tracking but no session — invariant violation.  Log and let
              // the next stopAxis / fault clean up.
              log.warn(s"PVT monitor axis $axis: axisState=Tracking but no trackingSession " +
                       s"(invariant violation); ignoring reading")
        // else: axis no longer Tracking by IS's authoritative view — CS's cache is
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
      // update, that wins — the auto-clear only fires when no axisError key was
      // supplied (rare: only fault paths set it, and those simultaneously set
      // axisState=Error so the auto-clear branch doesn't fire anyway).
      val leavingError =
        oldAxisState.exists(_.axisState == AxisStateEnum.Error) &&
        currentState.getAxis(axis).exists(_.axisState != AxisStateEnum.Error)
      if leavingError && !updates.contains("axisError") then
        currentState = currentState.updateAxis(axis, Map("axisError" -> ""))

      // Get new axis state
      val newAxisState = currentState.getAxis(axis)
      
      // Detect ALL changed fields (including auto-calculated ones like inPosition
      // and the auto-cleared axisError above)
      val allChangedFields = (oldAxisState, newAxisState) match
        case (Some(oldAxis), Some(newAxis)) =>
          var changed = updates.keySet
          if oldAxis.inPosition != newAxis.inPosition then
            changed = changed + "inPosition"
          if oldAxis.axisError != newAxis.axisError then
            changed = changed + "axisError"
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
   * the thread→axis mapping for UpdateThreadStatus to resolve completions.
   *
   * Also forwards a RegisterAxisThread to ControllerStatusActor (if wired)
   * so CS can interpret per-axis ae[] reads on its scan cycle.
   */
  private def handleRegisterThread(thread: Int, axis: Axis): Behavior[Command] =
    log.info(s"RegisterThread: thread=$thread → axis=$axis")
    threadRegistry = threadRegistry + (thread -> axis)

    // Forward to CS so it can correlate ae[axis]==1 with thread-just-cleared events.
    statusActor.foreach(_ ! ControllerStatusActor.RegisterAxisThread(axis, thread))

    // Set activeThread on the axis CmdState immediately so the watcher's
    // initial snapshot reflects the running thread. This prevents premature
    // completion on the stale activeThread=0 from the last QR poll.
    val oldCmdState = currentState.getCmdState(axis)
    currentState = currentState.updateCmdState(axis, Map("activeThread" -> thread))
    val newCmdState = currentState.getCmdState(axis)

    // Only notify if value actually changed (it will have, from 0 to thread#)
    val changed = (oldCmdState, newCmdState) match
      case (Some(old), Some(nw)) => old.activeThread != nw.activeThread
      case _ => true
    if changed then
      newCmdState.foreach(cs => notifyCmdSubscribers(axis, cs, Set("activeThread")))

    Behaviors.same

  /**
   * Process hardware thread status bitmask from StatusMonitor QR poll.
   * For each registered (thread→axis): if the thread bit is now clear, the
   * thread has finished — set activeThread=0 on the owning axis, remove from
   * registry, and fire CmdStateChanged so the watcher can evaluate its mask.
   *
   * Also forwards a ClearAxisThread to ControllerStatusActor (if wired) so
   * CS can prune its axis→thread map. Sent after CS has already done its
   * decideAxisAndControllerErrors pass for this scan (CS sends UpdateThreadStatus
   * last in handleQRResponse), so ordering is: CS reads ae+QR → CS evaluates and
   * pushes axisErrorMsg → CS sends UpdateThreadStatus → IS clears activeThread
   * AND tells CS to drop the axis-thread mapping. Watcher sees axisErrorMsg
   * first (mailbox order from IS), then activeThread=0; mask check fails
   * correctly even on the same scan.
   */
  private def handleUpdateThreadStatus(threadStatusByte: Int): Behavior[Command] =
    // Find threads that were registered and are now inactive
    val completed = threadRegistry.filter { (thread, _) =>
      val bit = 1 << thread
      (threadStatusByte & bit) == 0
    }

    completed.foreach { (thread, axis) =>
      log.info(s"Thread $thread completed → axis=$axis activeThread→0")
      threadRegistry = threadRegistry - thread

      // Forward to CS so it drops the axis-thread mapping.
      statusActor.foreach(_ ! ControllerStatusActor.ClearAxisThread(axis))

      val oldCmdState = currentState.getCmdState(axis)
      currentState = currentState.updateCmdState(axis, Map("activeThread" -> 0))
      val newCmdState = currentState.getCmdState(axis)

      val changed = (oldCmdState, newCmdState) match
        case (Some(old), Some(nw)) => old.activeThread != nw.activeThread
        case _ => true
      if changed then
        newCmdState.foreach(cs => notifyCmdSubscribers(axis, cs, Set("activeThread")))
    }

    lastThreadStatusByte = threadStatusByte
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