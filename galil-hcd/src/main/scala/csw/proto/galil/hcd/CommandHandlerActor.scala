package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.{AskPattern, Behaviors}
import org.apache.pekko.util.Timeout
import csw.command.client.CommandResponseManager
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.commands.CommandResponse.{Completed, Error}
import csw.params.commands.Setup
import csw.params.core.models.{Id, ObsId}
import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

/**
 * Command Handler Actor - Primary entry point for incoming commands from Assemblies.
 *
 * As described in SDD Section 4.6.1:
 * - Validates incoming commands and determines command type (immediate vs long-running)
 * - For immediate commands: processes synchronously and returns final response
 * - For long-running commands: sends Started, delegates monitoring to CommandWatcher (future)
 * - Updates Internal State Actor with state changes from command execution
 *
 * Currently implements immediate commands only:
 *   configAxis, configRotatingAxis, configLinearAxis, setBit, setAO
 */
object CommandHandlerActor {

  // ========================================
  // Protocol
  // ========================================

  sealed trait Command

  /**
   * Handle a submitted command from onSubmit.
   * The CommandHandler will classify it, execute it, and update the CRM.
   */
  case class HandleCommand(
    setup: Setup,
    runId: Id,
    maybeObsId: Option[ObsId]
  ) extends Command

  // Internal message for receiving InternalState update responses
  private case class StateUpdateResult(response: InternalStateActor.UpdateResponse) extends Command

  // ========================================
  // Immediate command classification
  // ========================================

  private val immediateCommands = Set(
    "configAxis", "configRotatingAxis", "configLinearAxis",
    "setBit", "setAO"
  )

  private val longRunningCommands = Set(
    "positionAxis", "homeAxis", "stopAxis", "offsetAxis",
    "selectWheel", "trackAxis"
  )

  def isImmediate(commandName: String): Boolean = immediateCommands.contains(commandName)
  def isLongRunning(commandName: String): Boolean = longRunningCommands.contains(commandName)

  // ========================================
  // Factory
  // ========================================

  def behavior(
    controllerInterfaceActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    commandResponseManager: CommandResponseManager,
    loggerFactory: LoggerFactory,
    statusMonitor: ActorRef[StatusMonitor.Command]
  ): Behavior[Command] =
    Behaviors.setup { ctx =>
      val log = loggerFactory.getLogger(ctx)
      log.info("CommandHandlerActor started")

      // Adapter for InternalState update responses
      val stateUpdateAdapter = ctx.messageAdapter[InternalStateActor.UpdateResponse](StateUpdateResult.apply)

      // Timeout and scheduler for ask pattern (passed explicitly to avoid Scala 3 implicit ambiguity)
      val askTimeout: Timeout = Timeout(5.seconds)
      val askScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

      Behaviors.receiveMessage {
        case HandleCommand(setup, runId, maybeObsId) =>
          val commandName = setup.commandName.name

          if (isImmediate(commandName)) {
            try {
              log.info(s"Handling immediate command: $commandName")
              commandName match {
                case "configAxis" =>
                  handleConfigAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    stateUpdateAdapter, commandResponseManager, log, askTimeout, askScheduler)
                case "configRotatingAxis" =>
                  handleConfigRotatingAxis(setup, runId, internalStateActor,
                    stateUpdateAdapter, commandResponseManager, log)
                case "configLinearAxis" =>
                  handleConfigLinearAxis(setup, runId, internalStateActor,
                    stateUpdateAdapter, commandResponseManager, log)
                case "setBit" =>
                  handleSetBit(setup, runId, controllerInterfaceActor, commandResponseManager, log,
                    askTimeout, askScheduler)
                case "setAO" =>
                  handleSetAO(setup, runId, controllerInterfaceActor, commandResponseManager, log,
                    askTimeout, askScheduler)
                case other =>
                  commandResponseManager.updateCommand(Error(runId, s"Unknown immediate command: $other"))
              }
            } catch {
              case ex: Exception =>
                log.error(s"Immediate command $commandName failed: ${ex.getMessage}")
                commandResponseManager.updateCommand(Error(runId, s"$commandName failed: ${ex.getMessage}"))
            }
          } else {
            // Long-running commands
            try {
              log.info(s"Handling long-running command: $commandName")
              commandName match {
                case "positionAxis" =>
                  handlePositionAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor)
                case "homeAxis" =>
                  handleHomeAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor)
                case "stopAxis" =>
                  handleStopAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor)
                case "offsetAxis" =>
                  handleOffsetAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor)
                case "selectWheel" =>
                  handleSelectWheel(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor)
                case "trackAxis" =>
                  handleTrackAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor)
                case other =>
                  commandResponseManager.updateCommand(
                    Error(runId, s"Long-running command '$other' not yet implemented"))
              }
            } catch {
              case ex: Exception =>
                log.error(s"Long-running command $commandName failed during setup: ${ex.getMessage}")
                commandResponseManager.updateCommand(Error(runId, s"$commandName failed: ${ex.getMessage}"))
            }
          }

          Behaviors.same

        case StateUpdateResult(response) =>
          // Log state update failures but don't fail the command (already completed)
          if (!response.success) {
            log.warn(s"InternalState update failed: ${response.message}")
          }
          Behaviors.same
      }
    }

  // ========================================
  // configAxis — SDD 4.8.2
  // ========================================

  /**
   * Configures motion parameters for a single axis.
   *
   * Builds a compound Galil command string from the optional parameters present
   * in the Setup, sends it to the controller, then updates InternalState.
   *
   * Example for axis A with velocity=50000 and acceleration=100000:
   *   "speed[0]=50000;accel[0]=100000"
   */
  private def handleConfigAxis(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    stateUpdateAdapter: ActorRef[InternalStateActor.UpdateResponse],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Unit = {
    val axisChoice = setup(ConfigAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val idx = axis.index

    // Build compound command from present (optional) parameters
    // Each maps ICD param -> embedded array[axisIndex]=value
    val commands = scala.collection.mutable.ListBuffer[String]()
    val stateUpdates = scala.collection.mutable.Map[String, Any]()

    setup.get(ConfigAxisCommand.velocityKey).foreach { param =>
      commands += s"speed[$idx]=${param.head}"
      stateUpdates("maxSpeed") = param.head.toDouble
    }
    setup.get(ConfigAxisCommand.accelerationKey).foreach { param =>
      commands += s"accel[$idx]=${param.head}"
      stateUpdates("acceleration") = param.head.toDouble
    }
    setup.get(ConfigAxisCommand.decelerationKey).foreach { param =>
      commands += s"decel[$idx]=${param.head}"
      stateUpdates("deceleration") = param.head.toDouble
    }
    setup.get(ConfigAxisCommand.indexOffsetKey).foreach { param =>
      commands += s"hoff[$idx]=${param.head}"
      stateUpdates("indexOffset") = param.head.toDouble
    }
    setup.get(ConfigAxisCommand.indexSpeedKey).foreach { param =>
      commands += s"hspd[$idx]=${param.head}"
      stateUpdates("indexSpeed") = param.head.toDouble
    }
    setup.get(ConfigAxisCommand.inPositionThresholdKey).foreach { param =>
      stateUpdates("inPositionThreshold") = param.head.toDouble
    }

    // Send compound command to controller if there are any Galil commands
    // GalilIo.send() handles splitting compound responses correctly, even when
    // multiple ":" arrive in a single TCP packet (e.g. for assignment commands).
    if (commands.nonEmpty) {
      val cmdString = commands.mkString(";")
      log.info(s"configAxis $axis: sending $cmdString")
      sendToController(ciActor, cmdString, log, askTimeout, askScheduler) match {
        case Success(_) =>
          log.info(s"configAxis $axis: controller updated")
        case Failure(ex) =>
          crm.updateCommand(Error(runId, s"configAxis $axis failed: ${ex.getMessage}"))
          return
      }
    }

    // Update InternalState with all configured parameters
    // (controller params are also tracked in IS for timeout calculation and state visibility)
    if (stateUpdates.nonEmpty) {
      internalStateActor ! InternalStateActor.UpdateAxisState(axis, stateUpdates.toMap, stateUpdateAdapter)
    }

    log.info(s"configAxis $axis: completed (${commands.size} controller params, ${stateUpdates.size} state params)")
    crm.updateCommand(Completed(runId))
  }

  // ========================================
  // configRotatingAxis — InternalState only
  // ========================================

  private def handleConfigRotatingAxis(
    setup: Setup,
    runId: Id,
    internalStateActor: ActorRef[InternalStateActor.Command],
    stateUpdateAdapter: ActorRef[InternalStateActor.UpdateResponse],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger
  ): Unit = {
    val axisChoice = setup(ConfigRotatingAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val algorithmChoice = setup(ConfigRotatingAxisCommand.algorithmKey).head

    val algorithm = algorithmChoice.name match {
      case "forward"  => RotatingAlgorithm.Forward
      case "reverse"  => RotatingAlgorithm.Reverse
      case "shortest" => RotatingAlgorithm.Shortest
      case other => throw new IllegalArgumentException(s"Unknown algorithm: $other")
    }

    val updates = Map[String, Any](
      "mechanismType" -> MechanismType.Rotating,
      "algorithm" -> algorithm
    )

    internalStateActor ! InternalStateActor.UpdateAxisState(axis, updates, stateUpdateAdapter)
    log.info(s"configRotatingAxis $axis: algorithm=$algorithm")
    crm.updateCommand(Completed(runId))
  }

  // ========================================
  // configLinearAxis — InternalState only
  // ========================================

  private def handleConfigLinearAxis(
    setup: Setup,
    runId: Id,
    internalStateActor: ActorRef[InternalStateActor.Command],
    stateUpdateAdapter: ActorRef[InternalStateActor.UpdateResponse],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger
  ): Unit = {
    val axisChoice = setup(ConfigLinearAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val upperLimit = setup(ConfigLinearAxisCommand.upperLimitKey).head.toDouble
    val lowerLimit = setup(ConfigLinearAxisCommand.lowerLimitKey).head.toDouble

    val updates = Map[String, Any](
      "mechanismType" -> MechanismType.Linear,
      "upperLimit" -> upperLimit,
      "lowerLimit" -> lowerLimit
    )

    internalStateActor ! InternalStateActor.UpdateAxisState(axis, updates, stateUpdateAdapter)
    log.info(s"configLinearAxis $axis: upper=$upperLimit, lower=$lowerLimit")
    crm.updateCommand(Completed(runId))
  }

  // ========================================
  // setBit — SB or CB based on value
  // ========================================

  /**
   * Sets or clears a digital output bit.
   * ICD defines: address (int), value (int: 0 or 1)
   * Galil: SB address (set) or CB address (clear)
   */
  private def handleSetBit(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Unit = {
    val address = setup(SetBitCommand.addressKey).head
    val value = setup(SetBitCommand.valueKey).head

    val cmdString = if (value != 0) s"SB $address" else s"CB $address"
    log.info(s"setBit: $cmdString")

    sendToController(ciActor, cmdString, log, askTimeout, askScheduler) match {
      case Success(_) =>
        crm.updateCommand(Completed(runId))
      case Failure(ex) =>
        crm.updateCommand(Error(runId, s"setBit failed: ${ex.getMessage}"))
    }
  }

  // ========================================
  // setAO — AO command
  // ========================================

  /**
   * Sets an analog output channel value.
   * ICD defines: address (int), value (float)
   * Galil: AO address,value
   */
  private def handleSetAO(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Unit = {
    val address = setup(SetAOCommand.addressKey).head
    val value = setup(SetAOCommand.valueKey).head

    val cmdString = s"AO $address,$value"
    log.info(s"setAO: $cmdString")

    sendToController(ciActor, cmdString, log, askTimeout, askScheduler) match {
      case Success(_) =>
        crm.updateCommand(Completed(runId))
      case Failure(ex) =>
        crm.updateCommand(Error(runId, s"setAO failed: ${ex.getMessage}"))
    }
  }

  // ========================================
  // Helpers
  // ========================================

  /**
   * Send a command string to the controller via CI actor and wait for response.
   * Uses the ask pattern with SendCommand message.
   */
  private def sendToController(
    ciActor: ActorRef[GalilCommandMessage],
    cmdString: String,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Try[String] = {
    Try {
      val future = AskPattern.Askable(ciActor).ask[GalilCommandMessage.SendCommandResult](
        ref => GalilCommandMessage.SendCommand(cmdString, ref)
      )(askTimeout, askScheduler)
      val result = Await.result(future, askTimeout.duration)
      result.error match {
        case Some(errMsg) => throw new RuntimeException(errMsg)
        case None => result.response
      }
    }
  }

  // ========================================
  // Long-running command defaults
  // ========================================

  /** Default timeout for motion commands when motor config is unavailable */
  private val defaultMotionTimeout = 30.seconds

  /** Minimum timeout floor — even very short moves get this much time */
  private val minimumMotionTimeout = 3.seconds

  /** Safety multiplier applied to estimated move time to account for real-world variation */
  private val timeoutSafetyFactor = 2.0

  /**
   * Estimate the time for a trapezoidal motion profile.
   *
   * Galil motion follows a trapezoidal velocity profile:
   *   - Accelerate from 0 to maxSpeed at the configured acceleration rate
   *   - Cruise at maxSpeed
   *   - Decelerate from maxSpeed to 0 at the configured deceleration rate
   *
   * For short moves that can't reach maxSpeed (triangular profile),
   * the peak velocity is lower and the move is entirely accel+decel.
   *
   * @param distance     Absolute distance to travel (counts)
   * @param maxSpeed     Maximum speed (counts/sec)
   * @param acceleration Acceleration rate (counts/sec²)
   * @param deceleration Deceleration rate (counts/sec²)
   * @param motionDelay  Post-motion settling delay (ms), 0 if none
   * @return Estimated move time in seconds
   */
  private def estimateMoveTime(
    distance: Double,
    maxSpeed: Double,
    acceleration: Double,
    deceleration: Double,
    motionDelay: Double = 0.0
  ): Double = {
    if (distance <= 0 || maxSpeed <= 0 || acceleration <= 0 || deceleration <= 0) return 0.0

    // Time and distance to accelerate to max speed
    val tAccel = maxSpeed / acceleration
    val dAccel = 0.5 * acceleration * tAccel * tAccel

    // Time and distance to decelerate from max speed
    val tDecel = maxSpeed / deceleration
    val dDecel = 0.5 * deceleration * tDecel * tDecel

    val moveTime = if (dAccel + dDecel <= distance) {
      // Trapezoidal profile — reaches max speed
      val dCruise = distance - dAccel - dDecel
      val tCruise = dCruise / maxSpeed
      tAccel + tCruise + tDecel
    } else {
      // Triangular profile — doesn't reach max speed
      // Peak velocity: v_peak where d_accel + d_decel = distance
      //   0.5 * v² / accel + 0.5 * v² / decel = distance
      //   v² * (1/(2*accel) + 1/(2*decel)) = distance
      val vPeakSq = distance / (0.5 / acceleration + 0.5 / deceleration)
      val vPeak = Math.sqrt(vPeakSq)
      vPeak / acceleration + vPeak / deceleration
    }

    moveTime + (motionDelay / 1000.0)  // motionDelay is in ms
  }

  /**
   * Compute a motion timeout from axis state, applying safety factor and floor.
   *
   * If motor config is available in the AxisState, computes a physics-based
   * timeout from the trapezoidal motion profile. Otherwise falls back to
   * the default timeout.
   *
   * @param distance  Absolute distance to travel (counts)
   * @param axisState Current axis state with motor configuration
   * @param log       Logger for diagnostics
   * @return Timeout duration with safety margin
   */
  private def computeMoveTimeout(
    distance: Double,
    axisState: AxisState,
    log: csw.logging.api.scaladsl.Logger
  ): FiniteDuration = {
    (axisState.maxSpeed, axisState.acceleration, axisState.deceleration) match {
      case (Some(speed), Some(accel), Some(decel)) =>
        val delay = axisState.motionDelay.getOrElse(0.0)
        val estimatedSec = estimateMoveTime(distance, speed, accel, decel, delay)
        val timeoutSec = estimatedSec * timeoutSafetyFactor
        val timeout = Math.max(timeoutSec, minimumMotionTimeout.toSeconds.toDouble).seconds
        log.debug(s"Computed timeout: distance=$distance, estimated=${estimatedSec}s, " +
          s"timeout=${timeout} (speed=$speed, accel=$accel, decel=$decel, delay=$delay)")
        timeout
      case _ =>
        log.debug(s"Motor config unavailable, using default timeout: $defaultMotionTimeout")
        defaultMotionTimeout
    }
  }

  // ========================================
  // Execute embedded program with thread-start confirmation
  // ========================================

  /**
   * Executes an embedded program via the CI actor's ExecuteProgram protocol,
   * confirms thread start via MG _NO, and either spawns a CommandWatcher
   * or completes immediately if the program finished before we could observe it.
   *
   * This is the standard pattern for all long-running commands that invoke
   * embedded programs (homeAxis, positionAxis, offsetAxis, selectWheel, trackAxis).
   *
   * Flow:
   *   1. Pre-escalate StatusMonitor to action polling rate
   *   2. Ask CI actor to ExecuteProgram (sends XQ, then MG _NO atomically)
   *   3. If XQ rejected → set Error state, report Error to CRM
   *   4. If thread confirmed active → spawn CommandWatcher normally
   *   5. If thread already finished → command completed faster than MG _NO round-trip.
   *      Evaluate completion mask directly. If satisfied → Completed. If not → Error.
   *
   * @param label         Embedded program label without # (e.g. "MoveA", "HomeB")
   * @param axis          The axis being commanded
   * @param thread        Thread to execute on (typically axis.index + 1)
   * @param commandName   Command name for logging and error messages
   * @param runId         CSW command run ID
   * @param mask          CompletionMask for the CommandWatcher
   * @param completionAxisState  AxisState to transition to on completion (Idle or Tracking)
   * @param ciActor       ControllerInterfaceActor reference
   * @param internalStateActor  InternalStateActor reference
   * @param statusMonitor StatusMonitor reference (for rate escalation)
   * @param crm           CommandResponseManager for CRM updates
   * @param log           Logger
   * @param askTimeout    Timeout for ask pattern
   * @param askScheduler  Scheduler for ask pattern
   * @param ctx           Actor context (for spawning watcher)
   * @param timeout       Watcher timeout duration
   */
  private def executeProgramAndWatch(
    label: String,
    axis: Axis,
    commandName: String,
    runId: Id,
    mask: CommandWatcherActor.CompletionMask,
    completionAxisState: AxisStateEnum,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    statusMonitor: ActorRef[StatusMonitor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    timeout: FiniteDuration = defaultMotionTimeout
  ): Unit = {
    // Step 1: Pre-escalate polling rate so StatusMonitor is at action rate
    // before the program starts. This ensures IS updates flow quickly.
    statusMonitor ! StatusMonitor.SetPollingRate(10.0)

    // Step 2: Execute program via CI actor (thread allocated from pool, XQ + MG _NO atomically)
    val result = Try {
      val future = AskPattern.Askable(ciActor).ask[GalilCommandMessage.ExecuteProgramResult](
        ref => GalilCommandMessage.ExecuteProgram(label, ref)
      )(askTimeout, askScheduler)
      Await.result(future, askTimeout.duration)
    }

    result match {
      case Failure(ex) =>
        log.error(s"$commandName $axis: ExecuteProgram communication error: ${ex.getMessage}")
        setErrorState(axis, commandName, s"communication error: ${ex.getMessage}",
          internalStateActor, crm, runId, ctx)

      case Success(execResult) if execResult.error.isDefined =>
        log.error(s"$commandName $axis: ${execResult.error.get}")
        setErrorState(axis, commandName, s"failed to execute: ${execResult.error.get}",
          internalStateActor, crm, runId, ctx)

      case Success(execResult) if execResult.threadWasActive =>
        // Thread confirmed active — normal path. Spawn watcher.
        val thread = execResult.thread
        log.info(s"$commandName $axis: thread $thread confirmed active, spawning watcher")
        spawnWatcher(axis, commandName, runId, mask, internalStateActor, crm, log, ctx,
          timeout, completionAxisState)

      case Success(execResult) =>
        // Thread already finished — command completed faster than MG _NO.
        // CI actor already released the thread back to the pool.
        // Still spawn a watcher: the thread condition is already met, but
        // other mask conditions (e.g. moving==false for stopAxis) may still
        // be pending. The watcher's initial snapshot will evaluate the full
        // mask and complete immediately if all conditions are met, or wait
        // for the remaining conditions otherwise.
        val thread = execResult.thread
        log.info(s"$commandName $axis: thread $thread already finished (fast completion), spawning watcher for remaining conditions")

        // Check for immediate error from the embedded program first
        val cmdStateFuture = AskPattern.Askable(internalStateActor).ask[Option[AxisCmdState]](
          ref => InternalStateActor.GetAxisCmdState(axis, ref)
        )(askTimeout, askScheduler)

        Try(Await.result(cmdStateFuture, askTimeout.duration)) match {
          case Success(Some(cmdState)) if cmdState.axisErrorMsg.nonEmpty =>
            // Error from the embedded program — no point spawning watcher
            log.warn(s"$commandName $axis: fast completion with error: ${cmdState.axisErrorMsg}")
            setErrorState(axis, commandName, cmdState.axisErrorMsg,
              internalStateActor, crm, runId, ctx)

          case _ =>
            // No error — spawn watcher to wait for full completion mask.
            // Thread already released, so no thread to release on watcher completion.
            spawnWatcher(axis, commandName, runId, mask, internalStateActor, crm, log, ctx,
              timeout, completionAxisState)
        }
    }
  }

  /**
   * Helper: set axis to Error state, clear active command, report Error to CRM.
   */
  private def setErrorState(
    axis: Axis,
    commandName: String,
    errorMsg: String,
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    runId: Id,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command]
  ): Unit = {
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Error),
      ctx.system.ignoreRef)
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("clearActiveCommand" -> true),
      ctx.system.ignoreRef)
    crm.updateCommand(Error(runId, s"$commandName $axis: $errorMsg"))
  }

  // ========================================
  // positionAxis — SDD 4.8.4, ICD 2.2.1.7
  // ========================================

  /**
   * Moves the specified axis to an absolute target position.
   *
   * Sequence:
   *   1. Set demand in embedded variable: dmd[idx]=target
   *   2. Update AxisState.demand (for inPosition calculation)
   *   3. Update AxisCmdState: set activeCommand, clear axisErrorMsg
   *   4. Set AxisState to Moving
   *   5. Execute embedded program: XQ #MoveX,thread
   *   6. Spawn CommandWatcher with positionAxis mask
   */
  private def handlePositionAxis(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    statusMonitor: ActorRef[StatusMonitor.Command]
  ): Unit = {
    val axisChoice = setup(PositionAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val target = setup(PositionAxisCommand.targetKey).head.toDouble
    val idx = axis.index

    log.info(s"positionAxis $axis: target=$target")

    // Query current axis state for position check and timeout calculation
    val maybeAxisState = Try {
      val future = AskPattern.Askable(internalStateActor).ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )(askTimeout, askScheduler)
      Await.result(future, askTimeout.duration)
    }.getOrElse(None)

    // Check if axis is already at the requested position
    maybeAxisState match {
      case Some(axisState) =>
        val distance = Math.abs(axisState.position - target)
        if distance <= axisState.inPositionThreshold then
          log.info(s"positionAxis $axis: already at target $target (pos=${axisState.position}, " +
            s"distance=$distance <= threshold=${axisState.inPositionThreshold})")
          internalStateActor ! InternalStateActor.UpdateAxisState(axis,
            Map("demand" -> target),
            ctx.system.ignoreRef)
          crm.updateCommand(Completed(runId))
          return
      case None =>
    }

    // Compute timeout from motor config and move distance
    val moveTimeout = maybeAxisState match {
      case Some(axisState) =>
        val distance = Math.abs(axisState.position - target)
        computeMoveTimeout(distance, axisState, log)
      case None => defaultMotionTimeout
    }

    // 1. Set demand in embedded variable
    sendToController(ciActor, s"dmd[$idx]=$target", log, askTimeout, askScheduler) match {
      case Failure(ex) =>
        crm.updateCommand(Error(runId, s"positionAxis $axis: failed to set demand: ${ex.getMessage}"))
        return
      case _ =>
    }

    // 2. Update AxisState: demand (for inPosition calc) + transition to Moving
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("demand" -> target, "axisState" -> AxisStateEnum.Moving),
      ctx.system.ignoreRef)

    // 3. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Move, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 4. Execute embedded program with computed timeout
    executeProgramAndWatch(
      label = s"Move${axis.char}",
      axis = axis,
      commandName = "positionAxis",
      runId = runId,
      mask = CommandWatcherActor.CompletionMask.positionAxis,
      completionAxisState = AxisStateEnum.Idle,
      ciActor = ciActor,
      internalStateActor = internalStateActor,
      statusMonitor = statusMonitor,
      crm = crm,
      log = log,
      askTimeout = askTimeout,
      askScheduler = askScheduler,
      ctx = ctx,
      timeout = moveTimeout
    )
  }

  // ========================================
  // homeAxis — SDD 4.8.3, ICD 2.2.1.6
  // ========================================

  /**
   * Initiates the homing sequence for the specified axis.
   *
   * Sequence:
   *   1. Update AxisCmdState: set activeCommand, clear axisErrorMsg
   *   2. Set AxisState to Homing
   *   3. Execute embedded program with thread confirmation + watcher
   */
  private def handleHomeAxis(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    statusMonitor: ActorRef[StatusMonitor.Command]
  ): Unit = {
    val axisChoice = setup(HomeAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)

    log.info(s"homeAxis $axis")

    // 1. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Home, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 2. Transition to Homing
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Homing),
      ctx.system.ignoreRef)

    // 3. Execute embedded program with thread confirmation + watcher
    executeProgramAndWatch(
      label = s"Home${axis.char}",
      axis = axis,
      commandName = "homeAxis",
      runId = runId,
      mask = CommandWatcherActor.CompletionMask.homeAxis,
      completionAxisState = AxisStateEnum.Idle,
      ciActor = ciActor,
      internalStateActor = internalStateActor,
      statusMonitor = statusMonitor,
      crm = crm,
      log = log,
      askTimeout = askTimeout,
      askScheduler = askScheduler,
      ctx = ctx
    )
  }

  // ========================================
  // stopAxis — SDD 4.8.5, ICD 2.2.1.11
  // ========================================

  /**
   * Stops any active motion on the specified axis.
   *
   * Sequence:
   *   1. Execute embedded stop program: XQ #StopX,thread
   *      (The embedded #StopX sends ST command which decels and stops)
   *   2. If there's an active CommandWatcher, it will detect the thread
   *      release and motion stop via its own mask evaluation
   *   3. Spawn a new CommandWatcher with stopAxis mask for THIS stop command
   *
   * Note: The previous command's watcher (if any) will detect commandHalted
   * or thread release and self-terminate with an error. The stop command's
   * own watcher monitors the stop completion independently.
   */
  private def handleStopAxis(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    statusMonitor: ActorRef[StatusMonitor.Command]
  ): Unit = {
    val axisChoice = setup(StopAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)

    log.info(s"stopAxis $axis")

    // Query current axisState to determine completion state (SDD Figure 4-2):
    //   Homing interrupted → Lost (axis not homed)
    //   Moving/Tracking interrupted → Idle (position is known)
    val completionState = Try {
      val future = AskPattern.Askable(internalStateActor).ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )(askTimeout, askScheduler)
      Await.result(future, askTimeout.duration) match {
        case Some(as) =>
          val target = as.axisState.stopCompletionState
          log.info(s"stopAxis $axis: current state=${as.axisState}, completion→$target")
          target
        case None => AxisStateEnum.Idle
      }
    }.getOrElse(AxisStateEnum.Idle)

    // Signal any existing watcher that the command was halted
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("commandHalted" -> true),
      ctx.system.ignoreRef)

    // Brief delay for the halted notification to propagate
    Thread.sleep(10)

    // Clear halted flag and set new active command
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("commandHalted" -> false, "activeCommand" -> ActiveCommand.Stop, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // Execute embedded stop program with thread confirmation + watcher
    executeProgramAndWatch(
      label = s"Stop${axis.char}",
      axis = axis,
      commandName = "stopAxis",
      runId = runId,
      mask = CommandWatcherActor.CompletionMask.stopAxis,
      completionAxisState = completionState,
      ciActor = ciActor,
      internalStateActor = internalStateActor,
      statusMonitor = statusMonitor,
      crm = crm,
      log = log,
      askTimeout = askTimeout,
      askScheduler = askScheduler,
      ctx = ctx,
      timeout = 5.seconds
    )
  }

  // ========================================
  // offsetAxis — ICD 2.2.1.8
  // ========================================

  /**
   * Moves the specified axis by a relative distance.
   * Implemented as: compute absolute target = current position + distance,
   * then delegate to the same embedded #MoveX program as positionAxis.
   */
  private def handleOffsetAxis(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    statusMonitor: ActorRef[StatusMonitor.Command]
  ): Unit = {
    val axisChoice = setup(OffsetAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val distance = setup(OffsetAxisCommand.distanceKey).head.toDouble
    val idx = axis.index

    // Read current position from InternalState
    implicit val timeout: Timeout = askTimeout
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = askScheduler

    val posFuture = AskPattern.Askable(internalStateActor).ask[Option[AxisState]](
      ref => InternalStateActor.GetAxisState(axis, ref)
    )
    val currentState = Await.result(posFuture, askTimeout.duration)

    val currentPosition = currentState match {
      case Some(state) => state.position
      case None =>
        crm.updateCommand(Error(runId, s"offsetAxis $axis: axis not initialized"))
        return
    }

    val target = currentPosition + distance
    log.info(s"offsetAxis $axis: distance=$distance, current=$currentPosition, target=$target")

    // Zero-distance offset — already at target, complete immediately
    if Math.abs(distance) <= currentState.get.inPositionThreshold then
      log.info(s"offsetAxis $axis: zero distance, already at target (distance=$distance)")
      internalStateActor ! InternalStateActor.UpdateAxisState(axis,
        Map("demand" -> target),
        ctx.system.ignoreRef)
      crm.updateCommand(Completed(runId))
      return

    // Set demand in embedded variable
    sendToController(ciActor, s"dmd[$idx]=$target", log, askTimeout, askScheduler) match {
      case Failure(ex) =>
        crm.updateCommand(Error(runId, s"offsetAxis $axis: failed to set demand: ${ex.getMessage}"))
        return
      case _ =>
    }

    // Update state
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("demand" -> target, "axisState" -> AxisStateEnum.Moving),
      ctx.system.ignoreRef)
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Move, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // Compute timeout from motor config
    val moveTimeout = computeMoveTimeout(Math.abs(distance), currentState.get, log)

    // Execute move with thread confirmation + watcher (same mask as positionAxis)
    executeProgramAndWatch(
      label = s"Move${axis.char}",
      axis = axis,
      commandName = "offsetAxis",
      runId = runId,
      mask = CommandWatcherActor.CompletionMask.positionAxis,
      completionAxisState = AxisStateEnum.Idle,
      ciActor = ciActor,
      internalStateActor = internalStateActor,
      statusMonitor = statusMonitor,
      crm = crm,
      log = log,
      askTimeout = askTimeout,
      askScheduler = askScheduler,
      ctx = ctx,
      timeout = moveTimeout
    )
  }

  // ========================================
  // selectWheel — SDD 3.3.2.5, ICD 2.2.1.9
  // ========================================

  /**
   * Positions a rotating mechanism (e.g. filter wheel) based on a discrete selection.
   *
   * Sequence:
   *   1. Set demand in embedded variable: dmd[idx]=position
   *   2. Update AxisCmdState: set activeCommand=Select, clear axisErrorMsg
   *   3. Set AxisState to Moving
   *   4. Execute embedded program: XQ #SelectX,thread
   *   5. Spawn CommandWatcher with selectWheel mask (same as positionAxis)
   *
   * The embedded #SelectX program uses a lookup table to map position numbers
   * to angular positions and applies the configured rotational direction.
   * The HCD treats it identically to a position move from a completion standpoint.
   */
  private def handleSelectWheel(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    statusMonitor: ActorRef[StatusMonitor.Command]
  ): Unit = {
    val axisChoice = setup(SelectWheelCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val position = setup(SelectWheelCommand.positionKey).head
    val idx = axis.index

    log.info(s"selectWheel $axis: position=$position")

    // 1. Set position demand in embedded variable
    sendToController(ciActor, s"dmd[$idx]=$position", log, askTimeout, askScheduler) match {
      case Failure(ex) =>
        crm.updateCommand(Error(runId, s"selectWheel $axis: failed to set demand: ${ex.getMessage}"))
        return
      case _ =>
    }

    // 2. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Select, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 3. Transition to Moving
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Moving),
      ctx.system.ignoreRef)

    // 4. Execute embedded program with thread confirmation + watcher
    executeProgramAndWatch(
      label = s"Select${axis.char}",
      axis = axis,
      commandName = "selectWheel",
      runId = runId,
      mask = CommandWatcherActor.CompletionMask.selectWheel,
      completionAxisState = AxisStateEnum.Idle,
      ciActor = ciActor,
      internalStateActor = internalStateActor,
      statusMonitor = statusMonitor,
      crm = crm,
      log = log,
      askTimeout = askTimeout,
      askScheduler = askScheduler,
      ctx = ctx
    )
  }

  // ========================================
  // trackAxis — SDD 3.3.2.3, ICD 2.2.1.10
  // ========================================

  /**
   * Sets the specified axis into tracking mode with continuous target updates.
   *
   * The motor is expected to be near target1 (position) and will rotate at
   * target2 (velocity). The first trackAxis call starts tracking; subsequent
   * calls adjust position and/or rate. Updates typically arrive at 0.1-1 Hz.
   * The axis remains in Tracking state until stopAxis is issued.
   *
   * Sequence:
   *   1. Set tracking targets: Xtarget[0]=target1, and Xtarget[1]=target2 if provided
   *      If target2 (velocity) is omitted, we do NOT send it — the embedded program
   *      keeps the previously set rate. Sending 0 would stop rotation.
   *   2. Update AxisCmdState: set activeCommand=Track, clear axisErrorMsg
   *   3. Update AxisState: demand=target1, transition to Tracking
   *   4. Execute embedded program: XQ #TrackX,thread
   *   5. Spawn CommandWatcher with trackAxis mask
   *
   * The #TrackX program sets JG velocity and uses IP for position correction,
   * then ENDs. The motor continues jogging after the program finishes.
   * The CommandWatcher detects program completion (thread released) and reports
   * Completed, but does NOT transition the axis back to Idle — it stays Tracking.
   *
   * To update tracking targets while tracking, send another trackAxis command.
   * To stop tracking, send stopAxis.
   */
  private def handleTrackAxis(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    statusMonitor: ActorRef[StatusMonitor.Command]
  ): Unit = {
    val axisChoice = setup(TrackAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val target1 = setup(TrackAxisCommand.target1Key).head.toDouble
    val maybeTarget2 = setup.get(TrackAxisCommand.target2Key).map(_.head.toDouble)

    log.info(s"trackAxis $axis: target1=$target1 (position), target2=${maybeTarget2.getOrElse("(not sent)")}")

    // 1. Set tracking targets in embedded variables
    // Per SDD Table 3-1: #TrackX uses Xtarget[0] (position) and Xtarget[1] (velocity)
    // Only send target2 if explicitly provided — omitting it preserves the previous rate.
    // Sending 0 would stop the motor, which is not the intent of omitting the parameter.
    val targetCmd = maybeTarget2 match {
      case Some(t2) => s"${axis.char}target[0]=$target1;${axis.char}target[1]=$t2"
      case None     => s"${axis.char}target[0]=$target1"
    }
    sendToController(ciActor, targetCmd, log, askTimeout, askScheduler) match {
      case Failure(ex) =>
        crm.updateCommand(Error(runId, s"trackAxis $axis: failed to set targets: ${ex.getMessage}"))
        return
      case _ =>
    }

    // 2. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Track, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 3. Update AxisState: set demand and transition to Tracking
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("demand" -> target1, "axisState" -> AxisStateEnum.Tracking),
      ctx.system.ignoreRef)

    // 4. Execute embedded tracking program with thread confirmation + watcher
    //    On completion, axis stays in Tracking (not Idle) — motor continues jogging
    executeProgramAndWatch(
      label = s"Track${axis.char}",
      axis = axis,
      commandName = "trackAxis",
      runId = runId,
      mask = CommandWatcherActor.CompletionMask.trackAxis,
      completionAxisState = AxisStateEnum.Tracking,
      ciActor = ciActor,
      internalStateActor = internalStateActor,
      statusMonitor = statusMonitor,
      crm = crm,
      log = log,
      askTimeout = askTimeout,
      askScheduler = askScheduler,
      ctx = ctx
    )
  }

  // ========================================
  // CommandWatcher spawn helper
  // ========================================

  /**
   * Spawn a CommandWatcherActor as a child of the CommandHandlerActor.
   * The watcher monitors CmdStateChanged notifications and reports to CRM.
   */
  private def spawnWatcher(
    axis: Axis,
    commandName: String,
    runId: Id,
    mask: CommandWatcherActor.CompletionMask,
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    timeout: FiniteDuration = defaultMotionTimeout,
    completionAxisState: AxisStateEnum = AxisStateEnum.Idle
  ): Unit = {
    val config = CommandWatcherActor.WatchConfig(
      runId = runId,
      axis = axis,
      commandName = commandName,
      mask = mask,
      timeout = timeout,
      internalStateActor = internalStateActor,
      commandResponseManager = crm,
      completionAxisState = completionAxisState
    )
    val watcherName = s"watcher-${commandName}-${axis}-${runId.id.take(8)}"
    ctx.spawn(CommandWatcherActor(config), watcherName)
    log.info(s"Spawned $watcherName for $commandName on axis $axis")
  }
}