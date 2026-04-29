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
    "setBit", "setAO", "faultReset"
  )

  private val longRunningCommands = Set(
    "positionAxis", "homeAxis", "stopAxis", "offsetAxis",
    "selectWheel", "positionWheel", "trackAxis"
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
    statusMonitor: ActorRef[ControllerStatusActor.Command]
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
                case "faultReset" =>
                  handleFaultReset(setup, runId, internalStateActor, stateUpdateAdapter,
                    controllerInterfaceActor, statusMonitor, commandResponseManager, log,
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
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor, loggerFactory)
                case "homeAxis" =>
                  handleHomeAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor, loggerFactory)
                case "stopAxis" =>
                  handleStopAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor, loggerFactory)
                case "offsetAxis" =>
                  handleOffsetAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor, loggerFactory)
                case "selectWheel" =>
                  handleSelectWheel(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor, loggerFactory)
                case "positionWheel" =>
                  handlePositionWheel(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor, loggerFactory)
                case "trackAxis" =>
                  handleTrackAxis(setup, runId, controllerInterfaceActor, internalStateActor,
                    commandResponseManager, log, askTimeout, askScheduler, ctx, statusMonitor, loggerFactory)
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

    // Send compound command(s) to controller if there are any Galil commands.
    // GalilIo.send() handles splitting compound responses correctly, even when
    // multiple ":" arrive in a single TCP packet (e.g. for assignment commands).
    //
    // Chunking via GalilIo.chunkCompound respects the controller's per-line
    // buffer (80 chars). With max-int numeric values, all five motor params
    // would exceed 80 chars; the helper packs sub-commands greedily and we
    // send each chunk separately.
    if (commands.nonEmpty) {
      val chunks = csw.proto.galil.io.GalilIo.chunkCompound(commands.toSeq)
      var failed = false
      val it = chunks.iterator
      while (it.hasNext && !failed) {
        val cmdString = it.next()
        log.info(s"configAxis $axis: sending $cmdString")
        sendToController(ciActor, cmdString, log, askTimeout, askScheduler) match {
          case Success(_) =>
            log.debug(s"configAxis $axis: chunk OK")
          case Failure(ex) =>
            crm.updateCommand(Error(runId, s"configAxis $axis failed: ${ex.getMessage}"))
            failed = true
        }
      }
      if (failed) return
      log.info(s"configAxis $axis: controller updated (${chunks.size} chunk(s))")
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
  // ========================================
  // Axis state guard — enforced at execution time (SDD Figure 4-2)
  // ========================================

  /**
   * Re-validates axis state at execution time, closing the race window between
   * onValidate() and handler execution.
   *
   * onValidate() queries IS and accepts/rejects, but the IS update setting the
   * axis to Moving/Homing is a fire-and-forget message that may not have been
   * applied by the time the next command's onValidate() runs.  Re-checking here
   * — inside the single-threaded CommandHandlerActor, after the previous handler
   * has written its state update — gives a much tighter guarantee.
   *
   * Returns None if accepted, or Some(error response) if rejected.
   */
  private def guardAxisState(
    commandName: String,
    axis: Axis,
    runId: Id,
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Option[Error] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import scala.concurrent.Await

    val maybeAxisState = try {
      val f = internalStateActor.ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )(askTimeout, askScheduler)
      Await.result(f, askTimeout.duration)
    } catch {
      case _: Exception => None
    }

    maybeAxisState match {
      case Some(axisState) =>
        axisState.axisState.validateCommand(commandName) match {
          case None =>
            None  // Accepted — proceed
          case Some(reason) =>
            log.warn(s"$commandName $axis rejected: $reason")
            crm.updateCommand(Error(runId, reason))
            Some(Error(runId, reason))
        }
      case None =>
        val msg = s"$commandName $axis: axis not initialized"
        log.error(msg)
        crm.updateCommand(Error(runId, msg))
        Some(Error(runId, msg))
    }
  }

  // ========================================
  // Command interruption protocol (SDD 4.8.1)
  // ========================================

  /**
   * Interrupt the currently active command on this axis before starting a new one.
   *
   * Called by positionAxis, offsetAxis, selectWheel, and stopAxis when the axis is
   * in Moving or Homing state — SDD 4.8.1 permits these commands to preempt an active move.
   *
   * @param sendST  If true (default), sends ST after HX to leave the motor stationary for
   *                the next embedded program. Pass false when the next program is #StopX,
   *                which handles motor deceleration itself.
   *
   * Sequence:
   *   1. Query IS CmdState for activeThread
   *   2. If activeThread > 0: send HaltExecution to CI actor (HX kills the thread)
   *   3. After successful HX: prompt ControllerStatusActor to update its per-axis
   *      tracking, so the next QR scan doesn't misattribute the halted program's
   *      ae[] residue to whatever command runs next on this axis.
   *   4. If sendST: send ST to stop motor motion
   *   5. Set commandHalted=true — active CommandWatcher sees this and reports CommandFailure
   *   6. 10ms delay for watcher to observe the flag
   *   7. Clear commandHalted — new command will set its own activeCommand
   *
   * Tracking special case: #TrackX ends with EN so the motor continues jogging
   * but the thread has already released. activeThread will be 0. HaltExecution is
   * skipped (and so is the CS prompt), but ST is still sent (if sendST=true)
   * to stop jogging motion, and commandHalted is set in case a slow watcher is
   * still running.
   */
  private def checkAndInterrupt(
    commandName: String,
    axis: Axis,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    sendST: Boolean = true
  ): Unit = {
    // Step 1: Query activeThread from IS
    val (activeThread, activeCmd) = Try {
      val future = AskPattern.Askable(internalStateActor).ask[Option[AxisCmdState]](
        ref => InternalStateActor.GetAxisCmdState(axis, ref)
      )(askTimeout, askScheduler)
      val cmdState = Await.result(future, askTimeout.duration)
      (cmdState.map(_.activeThread).getOrElse(0),
       cmdState.flatMap(_.activeCommand))
    }.getOrElse((0, None))

    log.info(s"checkAndInterrupt: $commandName on Moving axis $axis " +
      s"(thread=$activeThread, cmd=$activeCmd) — interruption needed")

    // Step 2: Halt the active thread if one is running (HX via CI actor).
    // On success, capture haltSucceeded=true so step 3 can do the ae attribution.
    var haltSucceeded = false
    if activeThread > 0 then
      log.info(s"checkAndInterrupt: halting axis $axis thread=$activeThread for $commandName")
      val haltResult = Try {
        val future = AskPattern.Askable(ciActor).ask[GalilCommandMessage.HaltExecutionResult](
          ref => GalilCommandMessage.HaltExecution(activeThread, axis, ref)
        )(askTimeout, askScheduler)
        Await.result(future, askTimeout.duration)
      }
      haltResult match {
        case Failure(ex) =>
          log.warn(s"checkAndInterrupt: HaltExecution error for $axis thread=$activeThread: " +
            s"${ex.getMessage} — proceeding")
        case Success(result) if !result.success =>
          log.warn(s"checkAndInterrupt: HaltExecution failed for $axis thread=$activeThread: " +
            s"${result.error.getOrElse("unknown")} — proceeding")
        case Success(_) =>
          log.info(s"checkAndInterrupt: axis $axis thread $activeThread halted")
          haltSucceeded = true
      }
    else
      log.info(s"checkAndInterrupt: axis $axis activeThread=0, skipping HX (already released)")

    // Step 3: After successful HX, prompt CS to update its per-axis tracking
    // before the next program registers. Without this, the next QR scan could
    // see "axis registered with thread N, but thread N just cleared, ae==1" and
    // misattribute the residue (the entry-time flag from the program we just
    // halted) as an unexplained failure of whatever command runs next on this
    // axis — particularly when the next command happens to reuse the same
    // thread number. Skipped on HX failure (nothing was halted) and on the
    // already-released path (no activeThread to begin with — Tracking case).
    if haltSucceeded then
      val notifyResult = Try {
        val future = AskPattern.Askable(statusMonitor).ask[ControllerStatusActor.NotifyAxisHaltedAck](
          ref => ControllerStatusActor.NotifyAxisHalted(axis, ref)
        )(askTimeout, askScheduler)
        Await.result(future, askTimeout.duration)
      }
      notifyResult match {
        case Failure(ex) =>
          log.warn(s"checkAndInterrupt: NotifyAxisHalted($axis) failed: ${ex.getMessage} " +
            s"— next QR scan's Step 3 backstop may misattribute residue")
        case Success(_) =>
          log.debug(s"checkAndInterrupt: axis $axis halt notification acked by CS")
      }

    // Step 4: Stop motor motion if requested. Omitted when the next program is #StopX,
    // which handles deceleration itself. Always sent for other interrupting commands
    // (including the Tracking case where the thread has released but motor is still jogging).
    if sendST then
      sendToController(ciActor, s"ST ${axis.char}", log, askTimeout, askScheduler) match {
        case Failure(ex) =>
          log.warn(s"checkAndInterrupt: ST ${axis.char} failed: ${ex.getMessage} — proceeding")
        case _ =>
          log.info(s"checkAndInterrupt: axis $axis motor stopped")
      }

    // Step 5: Signal existing watcher (if any) that its command was interrupted
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("commandHalted" -> true),
      ctx.system.ignoreRef)

    // Step 6: Brief delay for watcher to observe the flag and self-terminate
    Thread.sleep(10)

    // Step 7: Clear flag — new command handler will set its own activeCommand
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("commandHalted" -> false),
      ctx.system.ignoreRef)

    log.info(s"checkAndInterrupt: interruption complete for axis $axis — " +
      s"new command $commandName may proceed")
  }

  // ========================================
  // Long-running command defaults
  // ========================================

  private val defaultMotionTimeout = 3.minutes

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
  // Approach algorithm for rotating axes
  // ========================================

  /**
   * Adjust a raw count target using the configured approach algorithm for a rotating axis.
   *
   * For rotating mechanisms the same angular position can be reached from either direction.
   * Given a raw target (absolute encoder counts) and the axis's current position, this method
   * returns the count value that the motor should actually move to, according to:
   *   Forward  — always approach from below (increasing counts)
   *   Reverse  — always approach from above (decreasing counts)
   *   Shortest — take the shorter of the two arcs
   *
   * The result may differ from the raw target by a whole number of revolutions
   * (countsPerRev = 360 * cpd). The IS demand and the embedded dmd[] variable are set
   * to the adjusted value so that the motion profile and inPosition calculations are correct.
   *
   * @param rawTarget      Raw count demand supplied by the Assembly
   * @param currentPos     Current encoder position (from IS AxisState.position)
   * @param countsPerRev   Counts per revolution (AxisState.countsPerRevolution) — integer value
   * @param algorithm      Configured approach algorithm
   * @return               Adjusted absolute count target
   */
  private def applyApproachAlgorithm(
    rawTarget: Double,
    currentPos: Double,
    countsPerRev: Double,
    algorithm: RotatingAlgorithm
  ): Double =
    // countsPerRev is the integer count for one full revolution, passed directly from config.
    // Round to nearest integer defensively in case of any floating-point residual.
    val cpr = Math.round(countsPerRev).toDouble
    // Phase of current position within one revolution [0, countsPerRev)
    val curMod = ((currentPos % cpr) + cpr) % cpr
    // Phase of raw target within one revolution [0, cpr)
    val tgtMod = ((rawTarget % cpr) + cpr) % cpr
    // Base count aligning candidate to the same revolution as current position
    val base = currentPos - curMod
    val candidate = base + tgtMod
    val result = algorithm match
      case RotatingAlgorithm.Forward =>
        if candidate >= currentPos then candidate else candidate + cpr
      case RotatingAlgorithm.Reverse =>
        if candidate <= currentPos then candidate else candidate - cpr
      case RotatingAlgorithm.Shortest =>
        val fwd = if candidate >= currentPos then candidate else candidate + cpr
        val rev = if candidate <= currentPos then candidate else candidate - cpr
        if (fwd - currentPos) <= (currentPos - rev) then fwd else rev
    // Round final result to nearest integer count
    Math.round(result).toDouble

  // ========================================
  // Execute embedded program with thread-start confirmation
  // ========================================

  /**
   * Executes an embedded program via the CI actor's ExecuteProgram protocol
   * and spawns a CommandWatcher to await completion.
   *
   * This is the standard pattern for all long-running commands that invoke
   * embedded programs (homeAxis, positionAxis, offsetAxis, selectWheel, trackAxis,
   * stopAxis).
   *
   * Flow:
   *   1. Pre-escalate ControllerStatusActor to action polling rate
   *   2. Ask CI actor to ExecuteProgram (sends "XQ #label,thread;MG _XQ<thread>"
   *      as one compound, returning threadWasActive based on the MG _XQ result)
   *   3. If XQ rejected → set Error state, report Error to CRM
   *   4. Otherwise (regardless of threadWasActive) → register the thread with IS
   *      and spawn the CommandWatcher. The watcher evaluates the completion mask
   *      after the next QR scan, where CS reads ae[] and surfaces any program
   *      error. This uniform path ensures a fast-completing program that errored
   *      cannot be reported as Completed before the error surfaces.
   *
   * @param label         Embedded program label without # (e.g. "MoveA", "HomeB")
   * @param axis          The axis being commanded
   * @param commandName   Command name for logging and error messages
   * @param runId         CSW command run ID
   * @param mask          CompletionMask for the CommandWatcher
   * @param completionAxisState  AxisState to transition to on completion (Idle or Tracking)
   * @param ciActor       ControllerCommandActor reference
   * @param internalStateActor  InternalStateActor reference
   * @param statusMonitor ControllerStatusActor reference (for rate escalation)
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
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    loggerFactory: LoggerFactory,
    timeout: FiniteDuration = defaultMotionTimeout,
    preCommands: Option[String] = None,
    onSuccessAxisUpdates: Map[String, Any] = Map.empty
  ): Unit = {
    // Step 1: Pre-escalate polling rate so StatusMonitor is at action rate
    // before the program starts. This ensures IS updates flow quickly.
    statusMonitor ! ControllerStatusActor.SetPollingRate(10.0)

    // Step 2: Execute program via CI actor (thread allocated from pool, optional preCommands,
    // and "XQ;MG _XQ<thread>" compound all inside galilIo.synchronized in the CI actor)
    val result = Try {
      val future = AskPattern.Askable(ciActor).ask[GalilCommandMessage.ExecuteProgramResult](
        ref => GalilCommandMessage.ExecuteProgram(label, ref, preCommands)
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

      case Success(execResult) =>
        // XQ accepted. Whether the parser-side _XQ<n> follow-up caught the thread
        // mid-execution (line >= 0) or saw it already completed (-1), we always
        // register and spawn the watcher. The watcher evaluates the completion
        // mask only after IS observes the next QR scan — which is also when CS
        // reads ae[] for this axis and reports any program error. Without this
        // uniform path, a fast-completing program that errored could be reported
        // as Completed before the next QR scan surfaces the error. (SDD: a
        // command must not be declared complete without confirmation from at
        // least one full scan.)
        val thread = execResult.thread
        if execResult.threadWasActive then
          log.debug(s"$commandName $axis: thread $thread confirmed active, registering and spawning watcher")
        else
          // _XQ<n>=-1 immediately after XQ means thread N has already run and
          // stopped. The host parser yields between commands on a line, so a
          // short embedded program (e.g. #StopX is just STX;MG;EN) can complete
          // in microseconds before the parser-side MG runs. Not an error; the
          // watcher will evaluate completion (and any ae[] error) on the next
          // QR scan.
          log.debug(s"$commandName $axis: thread $thread already completed by the time _XQ was queried " +
            s"(short program ran to completion between XQ and the parser-side MG); " +
            s"registering and spawning watcher to evaluate on next scan")
        internalStateActor ! InternalStateActor.RegisterThread(thread, axis)
        spawnWatcher(axis, commandName, runId, mask, internalStateActor, crm, log, ctx,
          loggerFactory = loggerFactory, timeout = timeout, completionAxisState = completionAxisState,
          onSuccessAxisUpdates = onSuccessAxisUpdates)
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
      Map("clearActiveCommand" -> true, "axisErrorMsg" -> errorMsg),
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
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    loggerFactory: LoggerFactory
  ): Unit = {
    val axisChoice = setup(PositionAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val rawTarget = setup(PositionAxisCommand.targetKey).head.toDouble
    val idx = axis.index

    log.info(s"positionAxis $axis: rawTarget=$rawTarget")

    // Execution-time state machine guard (SDD Figure 4-2).
    // Re-validates here because onValidate() has a race window: the IS update
    // that sets axisState → Moving/Homing is fire-and-forget, and may not have
    // been applied before the next command's onValidate() query.
    if guardAxisState("positionAxis", axis, runId, internalStateActor, crm, log, askTimeout, askScheduler).isDefined
    then return

    // Query current axis state for position check and timeout calculation
    val maybeAxisState = Try {
      val future = AskPattern.Askable(internalStateActor).ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )(askTimeout, askScheduler)
      Await.result(future, askTimeout.duration)
    }.getOrElse(None)

    // If axis is currently Moving, apply SDD 4.8.1 interruption protocol before starting
    // the new command: halt active thread (HX + ST), signal watcher, then proceed.
    if maybeAxisState.exists(_.axisState == AxisStateEnum.Moving) then
      checkAndInterrupt("positionAxis", axis, ciActor, internalStateActor, statusMonitor, log, askTimeout, askScheduler, ctx)

    // For rotating axes with countsPerRevolution configured, apply the approach algorithm
    // to resolve the algorithm-adjusted count target. This may add or subtract a whole
    // revolution to ensure the motor approaches from the correct direction.
    val target = maybeAxisState match {
      case Some(axisState) if axisState.mechanismType == MechanismType.Rotating =>
        axisState.countsPerRevolution match {
          case Some(cpd) if cpd > 0.0 =>
            val alg = axisState.algorithm.getOrElse(RotatingAlgorithm.Shortest)
            val adjusted = applyApproachAlgorithm(rawTarget, axisState.position, cpd, alg)
            if adjusted != rawTarget then
              log.info(s"positionAxis $axis: approach algorithm $alg adjusted target $rawTarget → $adjusted")
            adjusted
          case _ =>
            log.debug(s"positionAxis $axis: rotating axis but countsPerRevolution not set, using raw target")
            rawTarget
        }
      case _ => rawTarget
    }

    // Defensive soft-limit check.  The CSW path runs the same check in
    // GalilHcd.validateAxisStateAndLimits before accepting the command, and the
    // HMI path runs it in HmiServer.softLimitRejection before submitting.  This
    // backstop catches any path that bypasses both — and is itself a no-op for
    // rotating axes, axes with softLimitsEnabled=false, or axes whose limits are
    // not configured.
    maybeAxisState.flatMap(_.checkSoftLimit(target)) match {
      case Some(reason) =>
        val msg = s"positionAxis $axis: $reason"
        log.warn(msg)
        crm.updateCommand(Error(runId, msg))
        return
      case None => // accepted; continue
    }

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

    // 1. Update AxisState: demand (for inPosition calc) + transition to Moving
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("demand" -> target, "axisState" -> AxisStateEnum.Moving),
      ctx.system.ignoreRef)

    // 2. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Move, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 3. Execute embedded program with computed timeout.
    // dmd[idx]=target is sent as preCommands inside ExecuteProgram's galilIo.synchronized
    // block, atomically before XQ — eliminating a separate CI round-trip.
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
      loggerFactory = loggerFactory,
      timeout = moveTimeout,
      preCommands = Some(s"dmd[$idx]=$target")
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
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    loggerFactory: LoggerFactory
  ): Unit = {
    val axisChoice = setup(HomeAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)

    log.info(s"homeAxis $axis")

    // Execution-time state machine guard (SDD Figure 4-2).
    // Re-validates here because onValidate() has a race window: the IS update
    // that sets axisState → Moving/Homing is fire-and-forget, and may not have
    // been applied before the next command's onValidate() query.
    if guardAxisState("homeAxis", axis, runId, internalStateActor, crm, log, askTimeout, askScheduler).isDefined
    then return

    // 1. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Home, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 2. Transition to Homing and clear the homed flag.
    // Clearing homed here means a failed home (Homing → Error via ae[], or timeout,
    // or stopAxis from Homing) will correctly report the axis as not-homed.
    // On success, the CommandWatcher will set homed=true atomically with axisState=Idle.
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Homing, "homed" -> false),
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
      ctx = ctx,
      loggerFactory = loggerFactory,
      onSuccessAxisUpdates = Map("homed" -> true)
    )
  }

  // ========================================
  // stopAxis — SDD 4.8.5, ICD 2.2.1.11
  // ========================================

  /**
   * Stops any active motion on the specified axis by executing the embedded #StopX program.
   *
   * If the axis is Moving or Homing, the active embedded program is halted first via
   * checkAndInterrupt (HX only — no ST) before #StopX runs. #StopX handles motor
   * deceleration itself, so a separate ST would be redundant.
   *
   * For Tracking, #StopX runs directly — #TrackX ends with EN so the thread has already
   * released, and ST from #StopX is sufficient to stop the jogging motor.
   *
   * Sequence:
   *   1. Query axisState to determine whether interruption is needed and the completion state
   *   2. If Moving or Homing: checkAndInterrupt (HX active thread only — no ST, #StopX handles deceleration)
   *   3. Execute embedded #StopX program (full application stop: deceleration, brakes, I/O)
   *   4. Spawn CommandWatcher with stopAxis mask
   *
   * Valid from any axis state (Lost, Idle, Homing, Moving, Tracking, Error).
   * Completion state depends on prior state and whether the axis has a valid home reference:
   *   Lost            → Lost  (no change to homed status)
   *   Idle            → Idle
   *   Homing          → Lost  (homing interrupted; position unknown)
   *   Moving/Tracking → Idle  (was homed; position known)
   *   Error (homed)   → Idle  (fault hit a homed axis; stop clears the fault)
   *   Error (!homed)  → Lost  (home attempt itself failed; position unknown)
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
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    loggerFactory: LoggerFactory
  ): Unit = {
    val axisChoice = setup(StopAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)

    log.info(s"stopAxis $axis")

    // Execution-time state machine guard (SDD Figure 4-2).
    // Re-validates here because onValidate() has a race window: the IS update
    // that sets axisState → Moving/Homing is fire-and-forget, and may not have
    // been applied before the next command's onValidate() query.
    if guardAxisState("stopAxis", axis, runId, internalStateActor, crm, log, askTimeout, askScheduler).isDefined
    then return

    // Query axisState to determine: (a) whether interruption is needed, (b) completion state.
    // The completion state depends on both the current axisState and the homed flag —
    // e.g. Error → Lost if the last home failed, Error → Idle if the axis was homed before the fault.
    val (completionState, currentAxisState) = Try {
      val future = AskPattern.Askable(internalStateActor).ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )(askTimeout, askScheduler)
      val axisStateOpt = Await.result(future, askTimeout.duration)
      val homed = axisStateOpt.exists(_.homed)
      val target = axisStateOpt.map(_.axisState.stopCompletionState(homed)).getOrElse(AxisStateEnum.Idle)
      val current = axisStateOpt.map(_.axisState).getOrElse(AxisStateEnum.Idle)
      log.info(s"stopAxis $axis: current state=$current, homed=$homed, completion→$target")
      (target, current)
    }.getOrElse((AxisStateEnum.Idle, AxisStateEnum.Idle))

    // If an embedded program is running (Moving or Homing), halt it and stop the motor
    // before executing #StopX. Without this, the running #MoveX or #HomeX program would
    // restart motion after #StopX sends ST, making the stop ineffective.
    if currentAxisState == AxisStateEnum.Moving || currentAxisState == AxisStateEnum.Homing then
      checkAndInterrupt("stopAxis", axis, ciActor, internalStateActor, statusMonitor, log, askTimeout, askScheduler, ctx, sendST = false)

    // Update active command for this stop
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Stop, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // Execute the embedded stop program. #StopX is responsible for the full
    // application-defined stop sequence: motor deceleration, brakes, I/O updates, etc.
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
      loggerFactory = loggerFactory,
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
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    loggerFactory: LoggerFactory
  ): Unit = {
    val axisChoice = setup(OffsetAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val distance = setup(OffsetAxisCommand.distanceKey).head.toDouble
    val idx = axis.index

    // Execution-time state machine guard (SDD Figure 4-2).
    // Re-validates here because onValidate() has a race window: the IS update
    // that sets axisState → Moving/Homing is fire-and-forget, and may not have
    // been applied before the next command's onValidate() query.
    if guardAxisState("offsetAxis", axis, runId, internalStateActor, crm, log, askTimeout, askScheduler).isDefined
    then return

    // Read current position from InternalState
    implicit val timeout: Timeout = askTimeout
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = askScheduler

    val posFuture = AskPattern.Askable(internalStateActor).ask[Option[AxisState]](
      ref => InternalStateActor.GetAxisState(axis, ref)
    )
    val currentState = Await.result(posFuture, askTimeout.duration)

    // If axis is currently Moving, apply SDD 4.8.1 interruption protocol before starting
    // the new command: halt active thread (HX + ST), signal watcher, then proceed.
    if currentState.exists(_.axisState == AxisStateEnum.Moving) then
      checkAndInterrupt("offsetAxis", axis, ciActor, internalStateActor, statusMonitor, log, askTimeout, askScheduler, ctx)

    val currentPosition = currentState match {
      case Some(state) => state.position
      case None =>
        crm.updateCommand(Error(runId, s"offsetAxis $axis: axis not initialized"))
        return
    }

    // Compute the raw target from offset + current position
    val rawTarget = currentPosition + distance
    log.info(s"offsetAxis $axis: distance=$distance, current=$currentPosition, rawTarget=$rawTarget")

    // For rotating axes with countsPerRevolution configured, apply the approach algorithm.
    // offsetAxis computes an absolute target from the offset and then adjusts for direction.
    val target = currentState match {
      case Some(axisState) if axisState.mechanismType == MechanismType.Rotating =>
        axisState.countsPerRevolution match {
          case Some(cpd) if cpd > 0.0 =>
            val alg = axisState.algorithm.getOrElse(RotatingAlgorithm.Shortest)
            val adjusted = applyApproachAlgorithm(rawTarget, currentPosition, cpd, alg)
            if adjusted != rawTarget then
              log.info(s"offsetAxis $axis: approach algorithm $alg adjusted target $rawTarget → $adjusted")
            adjusted
          case _ =>
            log.debug(s"offsetAxis $axis: rotating axis but countsPerRevolution not set, using raw target")
            rawTarget
        }
      case _ => rawTarget
    }

    // Defensive soft-limit check.  See handlePositionAxis for the full rationale —
    // the validate-time check should already have caught any violation; this is a
    // backstop and a no-op for any axis that doesn't have soft limits configured
    // and enabled.
    currentState.flatMap(_.checkSoftLimit(target)) match {
      case Some(reason) =>
        val msg = s"offsetAxis $axis: $reason"
        log.warn(msg)
        crm.updateCommand(Error(runId, msg))
        return
      case None => // accepted; continue
    }

    // Zero-distance offset — already at target, complete immediately
    if Math.abs(distance) <= currentState.get.inPositionThreshold then
      log.info(s"offsetAxis $axis: zero distance, already at target (distance=$distance)")
      internalStateActor ! InternalStateActor.UpdateAxisState(axis,
        Map("demand" -> target),
        ctx.system.ignoreRef)
      crm.updateCommand(Completed(runId))
      return

    // Update state
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("demand" -> target, "axisState" -> AxisStateEnum.Moving),
      ctx.system.ignoreRef)
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Move, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // Compute timeout from motor config
    val moveTimeout = computeMoveTimeout(Math.abs(distance), currentState.get, log)

    // Execute move with thread confirmation + watcher (same mask as positionAxis).
    // dmd[idx]=target is sent as preCommands inside ExecuteProgram's galilIo.synchronized
    // block, atomically before XQ — eliminating a separate CI round-trip.
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
      loggerFactory = loggerFactory,
      timeout = moveTimeout,
      preCommands = Some(s"dmd[$idx]=$target")
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
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    loggerFactory: LoggerFactory
  ): Unit = {
    val axisChoice = setup(SelectWheelCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val position = setup(SelectWheelCommand.positionKey).head
    val idx = axis.index

    log.info(s"selectWheel $axis: position=$position")

    // Execution-time state machine guard (SDD Figure 4-2).
    // Re-validates here because onValidate() has a race window: the IS update
    // that sets axisState → Moving/Homing is fire-and-forget, and may not have
    // been applied before the next command's onValidate() query.
    if guardAxisState("selectWheel", axis, runId, internalStateActor, crm, log, askTimeout, askScheduler).isDefined
    then return

    // If axis is currently Moving, apply SDD 4.8.1 interruption protocol before starting
    // the new command: halt active thread (HX + ST), signal watcher, then proceed.
    val maybeWheelState = Try {
      val future = AskPattern.Askable(internalStateActor).ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )(askTimeout, askScheduler)
      Await.result(future, askTimeout.duration)
    }.getOrElse(None)

    if maybeWheelState.exists(_.axisState == AxisStateEnum.Moving) then
      checkAndInterrupt("selectWheel", axis, ciActor, internalStateActor, statusMonitor, log, askTimeout, askScheduler, ctx)

    // 1. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Select, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 2. Transition to Moving
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Moving),
      ctx.system.ignoreRef)

    // 3. Execute embedded program with thread confirmation + watcher.
    // dmd[idx]=position is sent as preCommands inside ExecuteProgram's galilIo.synchronized
    // block, atomically before XQ — eliminating a separate CI round-trip.
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
      ctx = ctx,
      loggerFactory = loggerFactory,
      preCommands = Some(s"dmd[$idx]=$position")
    )
  }

  // ========================================
  // positionWheel — ICD 2.2.1.10
  // ========================================

  /**
   * Positions a rotating mechanism to an absolute angular position (in degrees).
   *
   * Requires the axis to be configured as Rotating with countsPerRevolution set.
   * If countsPerRevolution is not set, the command is rejected with an Error.
   *
   * Sequence:
   *   1. Resolve countsPerRevolution from current axis state (reject if not set)
   *   2. Convert angular demand (degrees) to raw count target:
   *        rawTarget = (angleDeg / 360.0) * countsPerRevolution
   *   3. Apply approach algorithm (same as positionAxis)
   *   4. Check already-at-position (same shortcut as positionAxis)
   *   5. Set demand: dmd[idx]=target
   *   6. Update AxisState.demand and transition to Moving
   *   7. Update AxisCmdState: set activeCommand=Move, clear axisErrorMsg
   *   8. Execute embedded program: XQ #MoveX,thread (same as positionAxis)
   *   9. Spawn CommandWatcher with positionAxis mask (inPosition checked)
   */
  private def handlePositionWheel(
    setup: Setup,
    runId: Id,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    loggerFactory: LoggerFactory
  ): Unit = {
    val axisChoice = setup(PositionWheelCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val angleDeg = setup(PositionWheelCommand.positionKey).head.toDouble
    val idx = axis.index

    log.info(s"positionWheel $axis: angleDeg=$angleDeg°")

    // Execution-time state machine guard (SDD Figure 4-2).
    if guardAxisState("positionWheel", axis, runId, internalStateActor, crm, log, askTimeout, askScheduler).isDefined
    then return

    // Query current axis state
    val maybeAxisState = Try {
      val future = AskPattern.Askable(internalStateActor).ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )(askTimeout, askScheduler)
      Await.result(future, askTimeout.duration)
    }.getOrElse(None)

    // Require rotating axis with countsPerRevolution configured
    val countsPerRev = maybeAxisState.flatMap { s =>
      if s.mechanismType == MechanismType.Rotating then s.countsPerRevolution.filter(_ > 0.0)
      else None
    }

    countsPerRev match {
      case None =>
        val reason = maybeAxisState match {
          case Some(s) if s.mechanismType != MechanismType.Rotating =>
            s"axis $axis is not configured as a rotating mechanism"
          case _ =>
            s"axis $axis countsPerRevolution is not set; configure the axis first"
        }
        log.error(s"positionWheel $axis: $reason")
        crm.updateCommand(Error(runId, s"positionWheel $axis: $reason"))
        return
      case _ =>
    }

    val cpr = countsPerRev.get

    // If axis is currently Moving, apply SDD 4.8.1 interruption protocol
    if maybeAxisState.exists(_.axisState == AxisStateEnum.Moving) then
      checkAndInterrupt("positionWheel", axis, ciActor, internalStateActor, statusMonitor, log, askTimeout, askScheduler, ctx)

    // Convert angular demand to raw count target
    val rawTarget = (angleDeg / 360.0) * cpr

    // Apply approach algorithm
    val alg = maybeAxisState.flatMap(_.algorithm).getOrElse(RotatingAlgorithm.Shortest)
    val currentPos = maybeAxisState.map(_.position).getOrElse(0.0)
    val target = applyApproachAlgorithm(rawTarget, currentPos, cpr, alg)
    if target != rawTarget then
      log.info(s"positionWheel $axis: approach algorithm $alg adjusted rawTarget $rawTarget → $target")

    // Check if axis is already at the requested position
    maybeAxisState match {
      case Some(axisState) =>
        val distance = Math.abs(axisState.position - target)
        if distance <= axisState.inPositionThreshold then
          log.info(s"positionWheel $axis: already at target $target (pos=${axisState.position}, " +
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

    // 1. Update AxisState: demand (for inPosition calc) + transition to Moving
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("demand" -> target, "axisState" -> AxisStateEnum.Moving),
      ctx.system.ignoreRef)

    // 2. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Move, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 3. Execute embedded program with computed timeout (same #MoveX as positionAxis).
    // dmd[idx]=target is sent as preCommands inside ExecuteProgram's galilIo.synchronized
    // block, atomically before XQ — eliminating a separate CI round-trip.
    executeProgramAndWatch(
      label = s"Move${axis.char}",
      axis = axis,
      commandName = "positionWheel",
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
      loggerFactory = loggerFactory,
      timeout = moveTimeout,
      preCommands = Some(s"dmd[$idx]=$target")
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
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    loggerFactory: LoggerFactory
  ): Unit = {
    val axisChoice = setup(TrackAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val target1 = setup(TrackAxisCommand.target1Key).head.toDouble
    val maybeTarget2 = setup.get(TrackAxisCommand.target2Key).map(_.head.toDouble)

    log.info(s"trackAxis $axis: target1=$target1 (position), target2=${maybeTarget2.getOrElse("(not sent)")}")

    // Execution-time state machine guard (SDD Figure 4-2).
    // Re-validates here because onValidate() has a race window: the IS update
    // that sets axisState → Moving/Homing is fire-and-forget, and may not have
    // been applied before the next command's onValidate() query.
    if guardAxisState("trackAxis", axis, runId, internalStateActor, crm, log, askTimeout, askScheduler).isDefined
    then return

    // 1. Set tracking targets in embedded variables
    // Per SDD Table 3-1: #TrackX uses Xtarget[0] (position) and Xtarget[1] (velocity)
    // Only send target2 if explicitly provided — omitting it preserves the previous rate.
    // Sending 0 would stop the motor, which is not the intent of omitting the parameter.
    val targetCmd = maybeTarget2 match {
      case Some(t2) => s"${axis.char}target[0]=$target1;${axis.char}target[1]=$t2"
      case None     => s"${axis.char}target[0]=$target1"
    }

    // 2. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Track, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 3. Update AxisState: set demand and transition to Tracking
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("demand" -> target1, "axisState" -> AxisStateEnum.Tracking),
      ctx.system.ignoreRef)

    // 4. Execute embedded tracking program with thread confirmation + watcher.
    // targetCmd is sent as preCommands inside ExecuteProgram's galilIo.synchronized
    // block, atomically before XQ — eliminating a separate CI round-trip.
    // On completion, axis stays in Tracking (not Idle) — motor continues jogging.
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
      ctx = ctx,
      loggerFactory = loggerFactory,
      preCommands = Some(targetCmd)
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
    loggerFactory: LoggerFactory,
    timeout: FiniteDuration = defaultMotionTimeout,
    completionAxisState: AxisStateEnum = AxisStateEnum.Idle,
    onSuccessAxisUpdates: Map[String, Any] = Map.empty
  ): Unit = {
    val config = CommandWatcherActor.WatchConfig(
      runId = runId,
      axis = axis,
      commandName = commandName,
      mask = mask,
      timeout = timeout,
      internalStateActor = internalStateActor,
      commandResponseManager = crm,
      loggerFactory = loggerFactory,
      completionAxisState = completionAxisState,
      onSuccessAxisUpdates = onSuccessAxisUpdates
    )
    val watcherName = s"watcher-${commandName}-${axis}-${runId.id.take(8)}"
    ctx.spawn(CommandWatcherActor(config), watcherName)
    log.info(s"Spawned $watcherName for $commandName on axis $axis")
  }

  /**
   * Handle faultReset command (SDD Section 4.6.4 — Fault Recovery Actor).
   *
   * Severity levels (in order of intrusiveness):
   *   None  — Clear error messages and transition HCD from Faulted to Ready.
   *            The controller error latch was already cleared by the TC 1 call
   *            that detected the fault. No controller interaction needed.
   *            For connection-loss faults: clears the Faulted state so commands
   *            are accepted again. If the connection is still down, the next
   *            command attempt will re-fault with a fresh error message.
   *   Init  — Reconnect dropped connections and re-run setup. (Not yet implemented.)
   *   Minor — Reset controller and re-initialize. (Not yet implemented.)
   *   Major — Reload embedded code and re-initialize. (Not yet implemented.)
   *
   * When reconnection logic is added (Init severity), it will attempt to re-open
   * any Disconnected TCP handles then re-run #Init and #SetupX. That complexity
   * may warrant a dedicated FaultRecoveryActor at that point.
   */
  private def handleFaultReset(
    setup: Setup,
    runId: Id,
    internalStateActor: ActorRef[InternalStateActor.Command],
    stateUpdateAdapter: ActorRef[InternalStateActor.UpdateResponse],
    controllerCommandActor: ActorRef[GalilCommandMessage],
    statusMonitor: ActorRef[ControllerStatusActor.Command],
    crm: CommandResponseManager,
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler
  ): Unit =
    import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

    val severity = try
      setup(FaultResetCommand.severityKey).head.name
    catch
      case _: Exception => "None"  // default to least intrusive

    log.info(s"faultReset: severity=$severity")

    severity match
      case "None" =>
        // Step 1: attempt to reconnect any dropped TCP connections.
        // Each actor first tests its existing socket (the connection may have
        // recovered on its own), then opens a fresh socket if needed.
        // We run command reconnect first, then status — sequentially via Await
        // so results are clear in the log before we decide the overall outcome.
        log.info("faultReset None: attempting connection recovery")

        val cmdResult = Try {
          Await.result(
            AskPattern.Askable(controllerCommandActor).ask[GalilCommandMessage.ReconnectResult](
              ref => GalilCommandMessage.Reconnect(ref)
            )(Timeout(15.seconds), askScheduler),
            16.seconds
          )
        }.getOrElse(GalilCommandMessage.ReconnectResult(
          success = false,
          error   = Some("Command reconnect ask timed out")))

        val stsResult = Try {
          Await.result(
            AskPattern.Askable(statusMonitor).ask[ControllerStatusActor.ReconnectResult](
              ref => ControllerStatusActor.Reconnect(ref)
            )(Timeout(15.seconds), askScheduler),
            16.seconds
          )
        }.getOrElse(ControllerStatusActor.ReconnectResult(
          success = false,
          error   = Some("Status reconnect ask timed out")))

        // Step 2: evaluate results and update HCD state accordingly
        val cmdOk = cmdResult.success
        val stsOk = stsResult.success

        log.info(s"faultReset None: command=${if cmdOk then "OK" else "FAILED"}, " +
                 s"status=${if stsOk then "OK" else "FAILED"}")

        if cmdOk && stsOk then
          // Both connections working — clear fault and return to Ready
          internalStateActor ! InternalStateActor.UpdateHcdState(
            Map(
              "state"              -> HcdStateEnum.Ready,
              "controllerErrorMsg" -> ""
            ),
            stateUpdateAdapter
          )
          log.info("faultReset None: all connections recovered — HCD Ready")
          crm.updateCommand(Completed(runId))
        else
          // One or both still down — build a clear error message and stay Faulted.
          // Use EnterFaulted so per-axis state transitions (Homing→Lost,
          // Moving/Tracking→Error) are re-applied consistently with other
          // fault-entry paths. No SafeAllMotors attempt here — at least one
          // connection is known bad, so sending ST;MO would IOException.
          val failures = Seq(
            if !cmdOk then Some(s"Command: ${cmdResult.error.getOrElse("failed")}") else None,
            if !stsOk then Some(s"Status: ${stsResult.error.getOrElse("failed")}") else None
          ).flatten.mkString("; ")
          val errorMsg = s"Connection recovery failed — $failures"
          internalStateActor ! InternalStateActor.EnterFaulted(errorMsg)
          log.error(s"faultReset None: $errorMsg")
          crm.updateCommand(Error(runId, errorMsg))

      case other =>
        val msg = s"faultReset severity='$other' not yet implemented"
        log.warn(msg)
        crm.updateCommand(Error(runId, msg))
}