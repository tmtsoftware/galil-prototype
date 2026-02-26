package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

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
  
  def apply(initialState: HcdState = HcdState()): Behavior[Command] =
    Behaviors.setup { context =>
      new InternalStateActor(context, initialState)
    }

/**
 * Actor implementation using Pekko Typed.
 */
class InternalStateActor(
  context: ActorContext[InternalStateActor.Command],
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
  
  context.log.info("InternalStateActor started")
  
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
        context.log.debug(s"New state subscriber: $subscriber")
        subscribers = subscribers + subscriber
        subscriptionFilters = subscriptionFilters + (subscriber -> filter)
        Behaviors.same
        
      case Unsubscribe(subscriber) =>
        context.log.debug(s"Unsubscribing state: $subscriber")
        subscribers = subscribers - subscriber
        subscriptionFilters = subscriptionFilters - subscriber
        Behaviors.same
        
      case SubscribeCmdState(axis, subscriber) =>
        context.log.debug(s"New cmd state subscriber for axis $axis: $subscriber")
        cmdSubscribers = cmdSubscribers + (subscriber -> axis)
        Behaviors.same
        
      case UnsubscribeCmdState(subscriber) =>
        context.log.debug(s"Unsubscribing cmd state: $subscriber")
        cmdSubscribers = cmdSubscribers - subscriber
        Behaviors.same

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
        context.log.error("Error updating HCD state", ex)
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
      
      // Get new axis state
      val newAxisState = currentState.getAxis(axis)
      
      // Detect ALL changed fields (including auto-calculated ones like inPosition)
      val allChangedFields = (oldAxisState, newAxisState) match
        case (Some(oldAxis), Some(newAxis)) =>
          var changed = updates.keySet
          if oldAxis.inPosition != newAxis.inPosition then
            changed = changed + "inPosition"
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
        context.log.error(s"Error updating axis $axis state", ex)
        replyTo ! UpdateResponse(success = false, message = ex.getMessage)
        Behaviors.same

  /**
   * Update axis command state and notify command state subscribers.
   * Only notifies subscribers watching the specific axis that changed.
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
      
      replyTo ! UpdateResponse(success = true)
      Behaviors.same
    catch
      case ex: Exception =>
        context.log.error(s"Error updating axis $axis cmd state", ex)
        replyTo ! UpdateResponse(success = false, message = ex.getMessage)
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
    cmdSubscribers.foreach { (subscriber, watchedAxis) =>
      if watchedAxis == axis then
        subscriber ! CmdStateChanged(axis, cmdState, changedFields)
    }