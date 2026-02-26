package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
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
   * Timeout timer fired — command took too long.
   */
  private case object CommandTimeout extends Command

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

    /**
     * trackAxis: thread released, no error.
     * The #TrackX program sets JG velocity + IP position adjustment, then ENDs.
     * After the program ends, the motor continues jogging (moving=true from QR).
     * The axis remains in Tracking state until stopAxis is issued.
     * Neither inPosition nor moving are checked — the program just needs to
     * start successfully and release its thread without error.
     */
    val trackAxis: CompletionMask = CompletionMask(
      activeThread = Some(0),
      axisErrorMsg = Some("")
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
    resultReporter: Option[(Id, Boolean, String) => Unit] = None
  )

  // ========================================
  // Factory
  // ========================================

  def apply(config: WatchConfig): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.withTimers { timers =>
        ctx.log.info(s"CommandWatcher started: ${config.commandName} on axis ${config.axis}, " +
          s"runId=${config.runId}, timeout=${config.timeout}")

        // Subscribe to CmdStateChanged for the target axis
        val cmdStateAdapter = ctx.messageAdapter[InternalStateActor.CmdStateChanged](CmdStateUpdate(_))
        config.internalStateActor ! InternalStateActor.SubscribeCmdState(config.axis, cmdStateAdapter)

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

        watching(config, cmdStateAdapter, timers)
      }
    }

  /**
   * Active monitoring behavior. Evaluates each CmdStateChanged notification
   * against the completion mask and error conditions.
   */
  private def watching(
    config: WatchConfig,
    cmdStateAdapter: ActorRef[InternalStateActor.CmdStateChanged],
    timers: TimerScheduler[Command]
  ): Behavior[Command] =
    Behaviors.receive { (ctx, msg) =>
      msg match
        case CmdStateUpdate(InternalStateActor.CmdStateChanged(axis, cmdState, changedFields)) =>
          ctx.log.debug(s"CommandWatcher ${config.commandName}/${config.axis}: " +
            s"changed=$changedFields, thread=${cmdState.activeThread}, " +
            s"inPos=${cmdState.inPosition}, moving=${cmdState.moving}, " +
            s"err='${cmdState.axisErrorMsg}', halted=${cmdState.commandHalted}")

          // Check 1: Command interruption (SDD 4.8.1)
          if cmdState.commandHalted then
            ctx.log.info(s"CommandWatcher ${config.commandName}/${config.axis}: " +
              s"command was interrupted (commandHalted=true)")
            // Clear the halted flag in IS actor
            config.internalStateActor ! InternalStateActor.UpdateAxisCmdState(
              config.axis,
              Map("commandHalted" -> false, "clearActiveCommand" -> true),
              ctx.system.ignoreRef
            )
            cleanup(config, cmdStateAdapter, ctx)
            reportResult(config, false,
              s"${config.commandName} on axis ${config.axis} was interrupted", ctx)
            Behaviors.stopped

          // Check 2: Axis error detected
          else if cmdState.axisErrorMsg.nonEmpty &&
                  config.mask.axisErrorMsg.contains("") then
            // Mask expects empty error but we have one — this is a failure
            ctx.log.warn(s"CommandWatcher ${config.commandName}/${config.axis}: " +
              s"axis error: '${cmdState.axisErrorMsg}'")
            // Clear activeCommand in IS actor
            config.internalStateActor ! InternalStateActor.UpdateAxisCmdState(
              config.axis,
              Map("clearActiveCommand" -> true),
              ctx.system.ignoreRef
            )
            cleanup(config, cmdStateAdapter, ctx)
            reportResult(config, false,
              s"${config.commandName} on axis ${config.axis} failed: ${cmdState.axisErrorMsg}", ctx)
            Behaviors.stopped

          // Check 3: Completion mask satisfied
          else if config.mask.isSatisfied(cmdState) then
            ctx.log.info(s"CommandWatcher ${config.commandName}/${config.axis}: " +
              s"completion mask satisfied — command complete")
            // Transition axisState to configured completion state (Idle for most, Tracking for trackAxis)
            config.internalStateActor ! InternalStateActor.UpdateAxisState(
              config.axis,
              Map("axisState" -> config.completionAxisState),
              ctx.system.ignoreRef
            )
            // Clear activeCommand in IS actor
            config.internalStateActor ! InternalStateActor.UpdateAxisCmdState(
              config.axis,
              Map("clearActiveCommand" -> true),
              ctx.system.ignoreRef
            )
            cleanup(config, cmdStateAdapter, ctx)
            reportResult(config, true, "completed", ctx)
            Behaviors.stopped

          else
            // Still waiting — continue monitoring
            Behaviors.same

        case CommandTimeout =>
          ctx.log.warn(s"CommandWatcher ${config.commandName}/${config.axis}: " +
            s"TIMEOUT after ${config.timeout}")
          // TODO: In full implementation, halt thread and stop motor here:
          //   ciActor ! HaltExecution(thread)
          //   ciActor ! SendCommand("ST${axis.char}")
          // For now, just report the error
          config.internalStateActor ! InternalStateActor.UpdateAxisCmdState(
            config.axis,
            Map("clearActiveCommand" -> true),
            ctx.system.ignoreRef
          )
          cleanup(config, cmdStateAdapter, ctx)
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
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]
  ): Unit =
    config.internalStateActor ! InternalStateActor.UnsubscribeCmdState(cmdStateAdapter)
    ctx.log.debug(s"CommandWatcher ${config.commandName}/${config.axis}: cleaned up")

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