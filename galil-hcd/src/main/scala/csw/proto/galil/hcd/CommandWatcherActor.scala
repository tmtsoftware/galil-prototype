package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import csw.logging.client.scaladsl.LoggerFactory
import csw.command.client.CommandResponseManager
import csw.params.commands.CommandResponse.{Completed, Error}
import csw.params.core.models.Id

import scala.concurrent.duration._

/**
 * Command Watcher Actor - Monitors long-running command completion (SDD 4.6.3).
 *
 * Spawned per long-running command by CommandHandlerActor. Subscribes to the
 * InternalStateActor's CmdStateChanged channel for the target axis, evaluates
 * a completion mask against each update, and reports final status to the CRM.
 *
 * Lifecycle: born when command starts → monitors via IS subscription → self-terminates
 * on completion, error, timeout, or interruption. Standard CSW worker actor pattern.
 *
 * Completion evaluation (SDD 4.8.4):
 *   The mask defines expected field values. When all mask conditions are satisfied
 *   simultaneously, the command is complete. Different commands use different masks:
 *     - positionAxis: activeThread==0, inPosition==true, axisErrorMsg=="", moving==false
 *     - homeAxis:     activeThread==0, axisErrorMsg=="", moving==false (no inPosition check)
 *     - stopAxis:     activeThread==0, moving==false
 *
 * Error conditions:
 *   - axisErrorMsg is non-empty → Error
 *   - commandHalted flag set → Error (command was interrupted, SDD 4.8.1)
 *   - Timeout exceeded → halt motion, set axisState=Error, Error
 */
object CommandWatcherActor:

  // ========================================
  // Protocol
  // ========================================

  sealed trait Command

  /**
   * CmdStateChanged notification from InternalStateActor (via adapter).
   */
  private case class CmdStateUpdate(notification: InternalStateActor.CmdStateChanged) extends Command

  /**
   * HCD StateChanged notification from InternalStateActor (via adapter).
   * Used to detect controller Faulted state during active command execution.
   */
  private case class HcdStateUpdate(notification: InternalStateActor.StateChanged) extends Command

  /**
   * Timeout timer fired — command took too long.
   */
  private case object CommandTimeout extends Command

  /**
   * Result sent to parent (CommandHandlerActor) before the watcher stops.
   * This ensures completion/failure is logged even when the actor stops
   * before the CSW logging framework flushes the log message.
   */
  case class WatcherResult(
    commandName: String,
    axis: Axis,
    runId: Id,
    success: Boolean,
    message: String
  )

  // ========================================
  // Completion Mask
  // ========================================

  /**
   * Defines the expected AxisCmdState values for command completion.
   * Each field is optional — None means "don't check this field".
   * All specified conditions must be true simultaneously for completion.
   */
  case class CompletionMask(
    activeThread: Option[Int] = None,
    inPosition: Option[Boolean] = None,
    axisErrorMsg: Option[String] = None,
    moving: Option[Boolean] = None
  ):
    /**
     * Evaluate whether the given AxisCmdState satisfies all mask conditions.
     */
    def isSatisfied(state: AxisCmdState): Boolean =
      activeThread.forall(_ == state.activeThread) &&
      inPosition.forall(_ == state.inPosition) &&
      axisErrorMsg.forall(_ == state.axisErrorMsg) &&
      moving.forall(_ == state.moving)

  /**
   * Standard completion masks for common commands (SDD 4.8.4).
   */
  object CompletionMask:
    /** positionAxis: thread released, in position, no error, not moving */
    val positionAxis: CompletionMask = CompletionMask(
      activeThread = Some(0),
      inPosition = Some(true),
      axisErrorMsg = Some(""),
      moving = Some(false)
    )

    /** homeAxis: thread released, no error, not moving (inPosition not checked — home sets position) */
    val homeAxis: CompletionMask = CompletionMask(
      activeThread = Some(0),
      axisErrorMsg = Some(""),
      moving = Some(false)
    )

    /** stopAxis: thread released, not moving (errors/inPosition don't matter) */
    val stopAxis: CompletionMask = CompletionMask(
      activeThread = Some(0),
      moving = Some(false)
    )

    /**
     * selectWheel: thread released, no error, not moving.
     * Like homeAxis, inPosition is NOT checked because the HCD doesn't know the
     * angular target — the #SelectX embedded program uses an internal lookup table
     * to map position numbers to angular positions.
     */
    val selectWheel: CompletionMask = CompletionMask(
      activeThread = Some(0),
      axisErrorMsg = Some(""),
      moving = Some(false)
    )

  // ========================================
  // Configuration for a watched command
  // ========================================

  /**
   * Everything the watcher needs to monitor a command.
   *
   * @param runId CSW command run ID for CRM reporting
   * @param axis The axis being commanded
   * @param commandName Name of the command (for logging)
   * @param mask Completion mask defining success criteria
   * @param timeout How long to wait before declaring timeout
   * @param internalStateActor IS actor to subscribe to
   * @param commandResponseManager CRM for final status reporting
   * @param completionAxisState AxisState to transition to on success (default: Idle; trackAxis uses Tracking)
   * @param onSuccessAxisUpdates Additional AxisState field updates to apply together with
   *   axisState on success. Merged into the same UpdateAxisState message so the transition
   *   is atomic from an IS subscriber's perspective. Example: homeAxis passes Map("homed" -> true)
   *   so a successful home sets axisState=Idle and homed=true in one update.
   * @param resultReporter Optional callback for test observability; receives (runId, isSuccess, message)
   */
  case class WatchConfig(
    runId: Id,
    axis: Axis,
    commandName: String,
    mask: CompletionMask,
    timeout: FiniteDuration,
    internalStateActor: ActorRef[InternalStateActor.Command],
    commandResponseManager: CommandResponseManager,
    completionAxisState: AxisStateEnum = AxisStateEnum.Idle,
    onSuccessAxisUpdates: Map[String, Any] = Map.empty,
    activeThread: Int = 0,
    resultReporter: Option[(Id, Boolean, String) => Unit] = None,
    loggerFactory: LoggerFactory = null
  )

  // ========================================
  // Factory
  // ========================================

  def apply(config: WatchConfig): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        val log = Option(config.loggerFactory)
          .map(_.getLogger(ctx))
          .getOrElse(new LoggerFactory(csw.prefix.models.Prefix("CSW.test")).getLogger(ctx))
        log.info(s"Watch: ${config.commandName}/${config.axis} timeout=${config.timeout}")

        // Subscribe to CmdStateChanged for the target axis
        val cmdStateAdapter = ctx.messageAdapter[InternalStateActor.CmdStateChanged](CmdStateUpdate(_))
        config.internalStateActor ! InternalStateActor.SubscribeCmdState(config.axis, cmdStateAdapter)

        // Subscribe to StateChanged (HCD-level) to detect controller Faulted state.
        // If the controller errors during execution, ControllerStatusActor will set
        // Faulted+controllerErrorMsg via QR errorCode detection, and we fail the command.
        val hcdStateAdapter = ctx.messageAdapter[InternalStateActor.StateChanged](HcdStateUpdate(_))
        config.internalStateActor ! InternalStateActor.Subscribe(hcdStateAdapter, None)

        // Request initial snapshot — handles the race where the command completes
        // before the watcher subscribes (fast commands like homeAxis on steppers).
        // The snapshot reply arrives as a message through initialStateAdapter.
        val initialStateAdapter = ctx.messageAdapter[Option[AxisCmdState]] {
          case Some(cmdState) =>
            CmdStateUpdate(InternalStateActor.CmdStateChanged(config.axis, cmdState, Set("initial")))
          case None =>
            // Axis not found — will be handled by timeout
            CmdStateUpdate(InternalStateActor.CmdStateChanged(config.axis, AxisCmdState(), Set("initial")))
        }
        config.internalStateActor ! InternalStateActor.GetAxisCmdState(config.axis, initialStateAdapter)

        // Start timeout timer
        timers.startSingleTimer(CommandTimeout, config.timeout)

        watching(config, cmdStateAdapter, hcdStateAdapter, timers, log)
      }
    }

  /**
   * Active monitoring behavior. Evaluates each CmdStateChanged notification
   * against the completion mask and error conditions.
   *
   * The CommandHandler pushes activeThread to CmdState before spawning the watcher,
   * so the initial snapshot will have activeThread > 0 for normal commands. This
   * prevents premature completion on stale pre-command state.
   */
  private def watching(
    config: WatchConfig,
    cmdStateAdapter: ActorRef[InternalStateActor.CmdStateChanged],
    hcdStateAdapter: ActorRef[InternalStateActor.StateChanged],
    timers: TimerScheduler[Command],
    log: csw.logging.api.scaladsl.Logger
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case HcdStateUpdate(InternalStateActor.StateChanged(hcdState, _, _)) =>
          if hcdState.state == HcdStateEnum.Faulted then
            // Controller reported an error via QR errorCode — ControllerStatusActor
            // has already called TC 1 and set controllerErrorMsg. Fail the command.
            val errorMsg = s"${config.commandName} on axis ${config.axis} failed: ${hcdState.controllerErrorMsg}"
            log.warn(s"Watch ${config.commandName}/${config.axis}: CONTROLLER FAULT — ${hcdState.controllerErrorMsg}")
            config.internalStateActor ! InternalStateActor.UpdateAxisCmdState(
              config.axis,
              Map("clearActiveCommand" -> true),
              ctx.system.ignoreRef
            )
            cleanup(config, cmdStateAdapter, hcdStateAdapter, ctx, log)
            reportResult(config, false, errorMsg, ctx)
            Behaviors.stopped
          else
            Behaviors.same

        case CmdStateUpdate(InternalStateActor.CmdStateChanged(axis, cmdState, changedFields)) =>
          log.debug(s"CommandWatcher ${config.commandName}/${config.axis}: " +
            s"changed=$changedFields, thread=${cmdState.activeThread}, " +
            s"inPos=${cmdState.inPosition}, moving=${cmdState.moving}, " +
            s"err='${cmdState.axisErrorMsg}', halted=${cmdState.commandHalted}")

          // Check 1: Command interruption (SDD 4.8.1)
          if cmdState.commandHalted then
            log.info(s"Watch ${config.commandName}/${config.axis}: INTERRUPTED")
            // Clear the halted flag in IS actor
            config.internalStateActor ! InternalStateActor.UpdateAxisCmdState(
              config.axis,
              Map("commandHalted" -> false, "clearActiveCommand" -> true),
              ctx.system.ignoreRef
            )
            cleanup(config, cmdStateAdapter, hcdStateAdapter, ctx, log)
            reportResult(config, false,
              s"${config.commandName} on axis ${config.axis} was interrupted", ctx)
            Behaviors.stopped

          // Check 2: Axis error detected
          else if cmdState.axisErrorMsg.nonEmpty &&
                  config.mask.axisErrorMsg.contains("") then
            // Mask expects empty error but we have one — this is a failure
            log.warn(s"Watch ${config.commandName}/${config.axis}: FAILED — '${cmdState.axisErrorMsg}' " +
              s"(inPos=${cmdState.inPosition} moving=${cmdState.moving})")
            // Clear activeCommand in IS actor
            config.internalStateActor ! InternalStateActor.UpdateAxisCmdState(
              config.axis,
              Map("clearActiveCommand" -> true),
              ctx.system.ignoreRef
            )
            cleanup(config, cmdStateAdapter, hcdStateAdapter, ctx, log)
            reportResult(config, false,
              s"${config.commandName} on axis ${config.axis} failed: ${cmdState.axisErrorMsg}", ctx)
            Behaviors.stopped

          // Check 3: Completion mask satisfied
          else if config.mask.isSatisfied(cmdState) then
            log.info(s"Watch ${config.commandName}/${config.axis}: COMPLETE " +
              s"(thread=${cmdState.activeThread} inPos=${cmdState.inPosition} moving=${cmdState.moving})")
            // Transition axisState to configured completion state (Idle for most, Tracking for trackAxis),
            // plus any command-specific axis updates (e.g. homeAxis sets homed=true).
            // Merge into one message so subscribers see an atomic transition.
            config.internalStateActor ! InternalStateActor.UpdateAxisState(
              config.axis,
              Map("axisState" -> config.completionAxisState) ++ config.onSuccessAxisUpdates,
              ctx.system.ignoreRef
            )
            // Clear activeCommand in IS actor
            config.internalStateActor ! InternalStateActor.UpdateAxisCmdState(
              config.axis,
              Map("clearActiveCommand" -> true),
              ctx.system.ignoreRef
            )
            cleanup(config, cmdStateAdapter, hcdStateAdapter, ctx, log)
            reportResult(config, true, "completed", ctx)
            Behaviors.stopped

          else
            // Still waiting — continue monitoring
            Behaviors.same

        case CommandTimeout =>
          log.warn(s"Watch ${config.commandName}/${config.axis}: TIMEOUT after ${config.timeout}")
          config.internalStateActor ! InternalStateActor.UpdateAxisCmdState(
            config.axis,
            Map("clearActiveCommand" -> true),
            ctx.system.ignoreRef
          )
          cleanup(config, cmdStateAdapter, hcdStateAdapter, ctx, log)
          reportResult(config, false,
            s"${config.commandName} on axis ${config.axis} timed out after ${config.timeout}", ctx)
          Behaviors.stopped
    }

  /**
   * Clean up resources: unsubscribe from IS actor, cancel timers.
   */
  private def cleanup(
    config: WatchConfig,
    cmdStateAdapter: ActorRef[InternalStateActor.CmdStateChanged],
    hcdStateAdapter: ActorRef[InternalStateActor.StateChanged],
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    log: csw.logging.api.scaladsl.Logger
  ): Unit =
    config.internalStateActor ! InternalStateActor.UnsubscribeCmdState(cmdStateAdapter)
    config.internalStateActor ! InternalStateActor.Unsubscribe(hcdStateAdapter)
    log.debug(s"Watch ${config.commandName}/${config.axis}: cleaned up")

  /**
   * Report result to both CRM and optional resultReporter.
   * Handles null CRM gracefully (can occur in unit tests).
   */
  private def reportResult(
    config: WatchConfig,
    success: Boolean,
    message: String,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]
  ): Unit =
    // Report to CRM (primary)
    if config.commandResponseManager != null then
      if success then
        config.commandResponseManager.updateCommand(Completed(config.runId))
      else
        config.commandResponseManager.updateCommand(Error(config.runId, message))
    // Report to test callback (secondary)
    config.resultReporter.foreach(_(config.runId, success, message))