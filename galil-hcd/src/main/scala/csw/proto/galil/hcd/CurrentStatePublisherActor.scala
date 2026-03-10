package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import csw.framework.CurrentStatePublisher
import csw.params.core.states.{CurrentState, StateName}
import csw.prefix.models.Prefix
import csw.logging.api.scaladsl.Logger
import csw.logging.client.scaladsl.LoggerFactory
import csw.proto.galil.GalilMotionKeys

import scala.concurrent.duration.*

/**
 * CurrentStatePublisher Actor - Transforms internal state to CSW CurrentState publications.
 * 
 * As described in ICD TIO.CTR.SPE.25.001, this actor:
 * - Subscribes to InternalStateActor for state changes
 * - Publishes CurrentState events at specified rates (1 Hz and 10 Hz)
 * - Transforms StateModel types to CSW Parameter types using generated keys
 * 
 * Publications (per ICD):
 * - CurrentState (1 Hz) - HCD lifecycle state, global errors
 * - CurrentStateAxis[A-H] (10 Hz) - Per-axis position, velocity, state, errors  
 * - InputOutputState (1 Hz) - Digital/analog I/O
 * - CommandStateAxis[A-H] (10 Hz) - Command execution status per axis
 * 
 * All keys are generated from ICD using icd-db tool and defined in GalilMotionKeys.
 */
object CurrentStatePublisherActor:
  
  // ========================================
  // Protocol
  // ========================================
  
  sealed trait Command
  
  /**
   * Internal timer message for 1 Hz publications
   */
  private case object Publish1Hz extends Command
  
  /**
   * Internal timer message for 10 Hz publications
   */
  private case object Publish10Hz extends Command
  
  /**
   * Notification from InternalStateActor that state changed
   */
  private case class StateUpdate(stateChanged: InternalStateActor.StateChanged) extends Command
  
  /**
   * Shutdown the publisher
   */
  case object Shutdown extends Command
  
  // ========================================
  // Factory
  // ========================================
  
  def behavior(
    prefix: Prefix,
    internalStateActor: ActorRef[InternalStateActor.Command],
    currentStatePublisher: CurrentStatePublisher,  // CSW's publisher directly!
    loggerFactory: LoggerFactory
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        new CurrentStatePublisherActor(
          ctx,
          timers,
          prefix,
          internalStateActor,
          currentStatePublisher,
          loggerFactory.getLogger(ctx)
        )
      }
    }

/**
 * Implementation of CurrentStatePublisher Actor
 */
class CurrentStatePublisherActor(
  ctx: ActorContext[CurrentStatePublisherActor.Command],
  timers: TimerScheduler[CurrentStatePublisherActor.Command],
  prefix: Prefix,
  internalStateActor: ActorRef[InternalStateActor.Command],
  currentStatePublisher: CurrentStatePublisher,  // CSW's publisher!
  logger: Logger
) extends AbstractBehavior[CurrentStatePublisherActor.Command](ctx):
  
  import CurrentStatePublisherActor.*
  import GalilMotionKeys.`ICS.HCD.GalilMotion`.*  // Import generated keys
  
  // Latest state snapshot
  private var latestState: Option[HcdState] = None

  // Last-published snapshots for change detection.
  // CurrentStateAxis[A-H] is always published at rate (position/velocity stream).
  // CurrentState and InputOutputState are published only on change.
  private var lastPublishedHcdState: Option[HcdState] = None
  
  // Subscribe to state changes
  private val stateUpdateAdapter = ctx.messageAdapter[InternalStateActor.StateChanged](StateUpdate(_))
  internalStateActor ! InternalStateActor.Subscribe(stateUpdateAdapter, None)
  
  // Start timers for periodic publication
  timers.startTimerWithFixedDelay(Publish1Hz, 1.second)
  timers.startTimerWithFixedDelay(Publish10Hz, 100.milliseconds)
  
  logger.info("CurrentStatePublisherActor started - using generated ICD keys")
  
  // ========================================
  // Message Handling
  // ========================================
  
  override def onMessage(msg: Command): Behavior[Command] = msg match
    case StateUpdate(InternalStateActor.StateChanged(newState, changedFields, changedAxes)) =>
      latestState = Some(newState)
      
      // Publish immediately when axisState changes so that every state transition
      // (e.g. Idle→Homing→Idle) is captured in CSP regardless of poll timer phase.
      // Without this, fast-completing commands (~100ms) can skip transient states
      // because the 10Hz timer hasn't fired between the state changes.
      if changedFields.contains("axisState") then
        changedAxes.foreach { axis =>
          newState.getAxis(axis).foreach { axisState =>
            publishAxisState(axis, axisState)
          }
        }
      
      this
      
    case Publish1Hz =>
      // CurrentState published unconditionally at 1Hz so late subscribers always
      // receive a message within 1 second. Change-detection caused starvation:
      // after initialization state stabilizes, hcdChanged stays false and
      // a subscriber that arrives after the first publish never receives anything.
      // InputOutputState keeps change-detection (no tests block on it).
      latestState.foreach { state =>
        publishCurrentState(state)

        val prev = lastPublishedHcdState
        val ioChanged = prev.isEmpty ||
          prev.get.digitalInputs  != state.digitalInputs  ||
          prev.get.digitalOutputs != state.digitalOutputs ||
          prev.get.analogInputs   != state.analogInputs
        if ioChanged then publishInputOutputState(state)

        lastPublishedHcdState = Some(state)
      }
      this

    case Publish10Hz =>
      latestState.foreach { state =>
        // CurrentStateAxis[A-H] always published at 10 Hz — position/velocity is
        // a continuous stream that Assemblies need at rate, not just on change.
        publishAllAxisStates(state)

        // CommandStateAxis[A-H] published unconditionally at 10Hz alongside
        // position/velocity. Change-detection causes late-subscriber starvation:
        // after a command completes, cmd state stabilizes and stops changing,
        // so any subscriber that arrives after the last change never receives
        // anything. Consistent with the unconditional position stream.
        publishAllCommandStates(state)
      }
      this
      
    case Shutdown =>
      logger.info("CurrentStatePublisherActor shutting down")
      Behaviors.stopped
  
  // ========================================
  // Publication Methods (Using Generated Keys)
  // ========================================
  
  /**
   * Publish CurrentState - HCD lifecycle state (1 Hz)
   * Uses keys from CurrentStateCurrentState
   */
  private def publishCurrentState(hcdState: HcdState): Unit =
    // Map internal enum to ICD choice string
    val stateValue = hcdState.state match
      case HcdStateEnum.Ready => "Ready"
      case HcdStateEnum.Faulted => "Faulted"
    
    val cs = CurrentState(
      CurrentStateCurrentState.eventKey.source,
      StateName(CurrentStateCurrentState.eventKey.eventName.name),
      Set(
        CurrentStateCurrentState.stateKey.set(stateValue),
        CurrentStateCurrentState.controllerIdKey.set(hcdState.controllerId),
        CurrentStateCurrentState.controllerErrorMsgKey.set(hcdState.controllerErrorMsg)
      )
    )
    
    currentStatePublisher.publish(cs)  // Use CSW directly!
  
  /**
   * Publish InputOutputState - Digital/analog I/O (1 Hz)
   * Uses keys from InputOutputStateCurrentState
   * 
   * Note: ICD defines digitalInputs/Outputs as Boolean arrays,
   * but analogInputs as FloatArrayKey (not individual Float keys)
   */
  private def publishInputOutputState(hcdState: HcdState): Unit =
    val cs = CurrentState(
      InputOutputStateCurrentState.eventKey.source,
      StateName(InputOutputStateCurrentState.eventKey.eventName.name),
      Set(
        InputOutputStateCurrentState.digitalInputsKey.setAll(hcdState.digitalInputs),
        InputOutputStateCurrentState.digitalOutputsKey.setAll(hcdState.digitalOutputs),
        // Convert Double array to Float array per ICD
        InputOutputStateCurrentState.analogInputsKey.set(hcdState.analogInputs.map(_.toFloat))
      )
    )
    
    currentStatePublisher.publish(cs)
  
  /**
   * Publish CurrentStateAxis[A-H] for all active axes (10 Hz)
   */
  private def publishAllAxisStates(hcdState: HcdState): Unit =
    Axis.values.foreach { axis =>
      hcdState.getAxis(axis).foreach { axisState =>
        publishAxisState(axis, axisState)
      }
    }
  
  /**
   * Publish CurrentStateAxis for a single axis (10 Hz)
   * Uses keys from CurrentStateAxis[A-H]CurrentState
   * 
   * Note: ICD defines position/velocity as Float (encoder counts), we convert from Double
   */
  private def publishAxisState(axis: Axis, axisState: AxisState): Unit =
    // Get keys and eventKey based on axis letter
    // Note: We need to extract both eventKey and parameter keys to avoid union type issues
    val (eventKey, posKey, velKey, stateKey, inPosKey, errKey) = axis match
      case Axis.A => (
        CurrentStateAxisACurrentState.eventKey,
        CurrentStateAxisACurrentState.positionKey,
        CurrentStateAxisACurrentState.velocityKey,
        CurrentStateAxisACurrentState.axisStateKey,
        CurrentStateAxisACurrentState.inPositionKey,
        CurrentStateAxisACurrentState.axisErrorMsgKey
      )
      case Axis.B => (
        CurrentStateAxisBCurrentState.eventKey,
        CurrentStateAxisBCurrentState.positionKey,
        CurrentStateAxisBCurrentState.velocityKey,
        CurrentStateAxisBCurrentState.axisStateKey,
        CurrentStateAxisBCurrentState.inPositionKey,
        CurrentStateAxisBCurrentState.axisErrorMsgKey
      )
      case Axis.C => (
        CurrentStateAxisCCurrentState.eventKey,
        CurrentStateAxisCCurrentState.positionKey,
        CurrentStateAxisCCurrentState.velocityKey,
        CurrentStateAxisCCurrentState.axisStateKey,
        CurrentStateAxisCCurrentState.inPositionKey,
        CurrentStateAxisCCurrentState.axisErrorMsgKey
      )
      case Axis.D => (
        CurrentStateAxisDCurrentState.eventKey,
        CurrentStateAxisDCurrentState.positionKey,
        CurrentStateAxisDCurrentState.velocityKey,
        CurrentStateAxisDCurrentState.axisStateKey,
        CurrentStateAxisDCurrentState.inPositionKey,
        CurrentStateAxisDCurrentState.axisErrorMsgKey
      )
      case Axis.E => (
        CurrentStateAxisECurrentState.eventKey,
        CurrentStateAxisECurrentState.positionKey,
        CurrentStateAxisECurrentState.velocityKey,
        CurrentStateAxisECurrentState.axisStateKey,
        CurrentStateAxisECurrentState.inPositionKey,
        CurrentStateAxisECurrentState.axisErrorMsgKey
      )
      case Axis.F => (
        CurrentStateAxisFCurrentState.eventKey,
        CurrentStateAxisFCurrentState.positionKey,
        CurrentStateAxisFCurrentState.velocityKey,
        CurrentStateAxisFCurrentState.axisStateKey,
        CurrentStateAxisFCurrentState.inPositionKey,
        CurrentStateAxisFCurrentState.axisErrorMsgKey
      )
      case Axis.G => (
        CurrentStateAxisGCurrentState.eventKey,
        CurrentStateAxisGCurrentState.positionKey,
        CurrentStateAxisGCurrentState.velocityKey,
        CurrentStateAxisGCurrentState.axisStateKey,
        CurrentStateAxisGCurrentState.inPositionKey,
        CurrentStateAxisGCurrentState.axisErrorMsgKey
      )
      case Axis.H => (
        CurrentStateAxisHCurrentState.eventKey,
        CurrentStateAxisHCurrentState.positionKey,
        CurrentStateAxisHCurrentState.velocityKey,
        CurrentStateAxisHCurrentState.axisStateKey,
        CurrentStateAxisHCurrentState.inPositionKey,
        CurrentStateAxisHCurrentState.axisErrorMsgKey
      )
    
    // Map internal enum to ICD choice string
    val stateValue = axisState.axisState match
      case AxisStateEnum.Lost => "lost"
      case AxisStateEnum.Homing => "homing"
      case AxisStateEnum.Idle => "idle"
      case AxisStateEnum.Moving => "moving"
      case AxisStateEnum.Tracking => "tracking"
      case AxisStateEnum.Error => "error"
    
    val cs = CurrentState(
      eventKey.source,
      StateName(eventKey.eventName.name),
      Set(
        // Convert Double to Float per ICD
        posKey.set(axisState.position.toFloat),
        velKey.set(axisState.velocity.toFloat),
        stateKey.set(stateValue),
        inPosKey.set(axisState.inPosition),
        errKey.set(axisState.axisError)
      )
    )
    
    currentStatePublisher.publish(cs)
  
  /**
   * Publish CommandStateAxis[A-H] unconditionally for all active axes.
   * Called at 10Hz alongside position/velocity stream.
   */
  private def publishAllCommandStates(hcdState: HcdState): Unit =
    Axis.values.foreach { axis =>
      hcdState.getCmdState(axis).foreach { cmdState =>
        publishCommandState(axis, cmdState)
      }
    }

  /**
   * Publish CommandStateAxis for a single axis (10 Hz)
   * Uses keys from CommandStateAxis[A-H]CurrentState
   * Now reads directly from AxisCmdState fields.
   */
  private def publishCommandState(axis: Axis, cmdState: AxisCmdState): Unit =
    // Get keys and eventKey based on axis letter
    // Note: We need to extract both eventKey and parameter keys to avoid union type issues
    val (eventKey, threadKey, errKey, inPosKey, movKey) = axis match
      case Axis.A => (
        CommandStateAxisACurrentState.eventKey,
        CommandStateAxisACurrentState.activeThreadKey,
        CommandStateAxisACurrentState.axisErrorMsgKey,
        CommandStateAxisACurrentState.inPositionKey,
        CommandStateAxisACurrentState.movingKey
      )
      case Axis.B => (
        CommandStateAxisBCurrentState.eventKey,
        CommandStateAxisBCurrentState.activeThreadKey,
        CommandStateAxisBCurrentState.axisErrorMsgKey,
        CommandStateAxisBCurrentState.inPositionKey,
        CommandStateAxisBCurrentState.movingKey
      )
      case Axis.C => (
        CommandStateAxisCCurrentState.eventKey,
        CommandStateAxisCCurrentState.activeThreadKey,
        CommandStateAxisCCurrentState.axisErrorMsgKey,
        CommandStateAxisCCurrentState.inPositionKey,
        CommandStateAxisCCurrentState.movingKey
      )
      case Axis.D => (
        CommandStateAxisDCurrentState.eventKey,
        CommandStateAxisDCurrentState.activeThreadKey,
        CommandStateAxisDCurrentState.axisErrorMsgKey,
        CommandStateAxisDCurrentState.inPositionKey,
        CommandStateAxisDCurrentState.movingKey
      )
      case Axis.E => (
        CommandStateAxisECurrentState.eventKey,
        CommandStateAxisECurrentState.activeThreadKey,
        CommandStateAxisECurrentState.axisErrorMsgKey,
        CommandStateAxisECurrentState.inPositionKey,
        CommandStateAxisECurrentState.movingKey
      )
      case Axis.F => (
        CommandStateAxisFCurrentState.eventKey,
        CommandStateAxisFCurrentState.activeThreadKey,
        CommandStateAxisFCurrentState.axisErrorMsgKey,
        CommandStateAxisFCurrentState.inPositionKey,
        CommandStateAxisFCurrentState.movingKey
      )
      case Axis.G => (
        CommandStateAxisGCurrentState.eventKey,
        CommandStateAxisGCurrentState.activeThreadKey,
        CommandStateAxisGCurrentState.axisErrorMsgKey,
        CommandStateAxisGCurrentState.inPositionKey,
        CommandStateAxisGCurrentState.movingKey
      )
      case Axis.H => (
        CommandStateAxisHCurrentState.eventKey,
        CommandStateAxisHCurrentState.activeThreadKey,
        CommandStateAxisHCurrentState.axisErrorMsgKey,
        CommandStateAxisHCurrentState.inPositionKey,
        CommandStateAxisHCurrentState.movingKey
      )
    
    val cs = CurrentState(
      eventKey.source,
      StateName(eventKey.eventName.name),
      Set(
        threadKey.set(cmdState.activeThread),
        errKey.set(cmdState.axisErrorMsg),
        inPosKey.set(cmdState.inPosition),
        movKey.set(cmdState.moving)
      )
    )
    
    currentStatePublisher.publish(cs)