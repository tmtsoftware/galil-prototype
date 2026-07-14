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
import csw.time.core.models.TAITime

import java.time.Instant
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

  // Commands handled by CommandHandlerActor (axis-targeting commands and
  // simple I/O).  faultReset is NOT in either set; it is handled directly
  // by GalilHcd because it drives HCD lifecycle state transitions and
  // re-runs the shared init sequence; routing it through CHA would force
  // CHA to reach across into GalilHcd-owned helpers.
  private val immediateCommands = Set(
    "configAxis", "configRotatingAxis", "configLinearAxis",
    "setBit", "setAO"
  )

  private val longRunningCommands = Set(
    "positionAxis", "homeAxis", "stopAxis", "offsetAxis",
    "selectWheel", "positionWheel", "trackAxis"
  )

  def isImmediate(commandName: String): Boolean = immediateCommands.contains(commandName)
  def isLongRunning(commandName: String): Boolean = longRunningCommands.contains(commandName)

  // ========================================
  // PVA argument bounds (Galil DMC-40x0 Command Reference, "PV PVT Data")
  // ========================================
  // The Galil controller imposes per-argument bounds on PVA that are MUCH
  // tighter than the universal Galil4.2 signed-int32 range.  Sending a
  // PVA wire command outside these bounds yields ':?' rejection followed
  // by 'TC 6 Number out of range', which faults the HCD.  Per the spec:
  //
  //   n0 (ΔP, counts):       -44,000,000  ..  +44,000,000
  //   n1 (V,  counts/sec):   -22,000,000  ..  +22,000,000
  //   n2 (T,  samples):                0  ..        2,048      (0 exits PVT mode)
  //
  // At TM = 1000 µs, the n2 cap means MAX_PER_SEGMENT_DURATION ≈ 2.048 sec.
  // Any latency that pushes (validTime - prev_lastValidTime) past ~2 seconds
  // produces an out-of-range T and a controller-side reject; so this guard
  // catches BOTH wild HCD math (e.g. stale TrackingSession reference times)
  // AND legitimate but excessive validTime gaps from the client.
  val PvaMaxDeltaPosition: Long = 44_000_000L
  val PvaMaxVelocity:      Long = 22_000_000L
  val PvaMaxTSamples:      Long =      2_048L

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
  // configAxis; SDD 4.8.2
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
  // configRotatingAxis; InternalState only
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
  // configLinearAxis; InternalState only
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
  // setBit; SB or CB based on value
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
  // setAO; AO command
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
  // Axis state guard; enforced at execution time (SDD Figure 4-2)
  // ========================================

  /**
   * Re-validates axis state at execution time, closing the race window between
   * onValidate() and handler execution.
   *
   * onValidate() queries IS and accepts/rejects, but the IS update setting the
   * axis to Moving/Homing is a fire-and-forget message that may not have been
   * applied by the time the next command's onValidate() runs.  Re-checking here
   *; inside the single-threaded CommandHandlerActor, after the previous handler
   * has written its state update; gives a much tighter guarantee.
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
   * in Moving, Homing, or Tracking state; SDD 4.8.1 permits these commands to
   * preempt an active move.
   *
   * @param sendST  If true, sends ST after HX to leave the motor stationary for
   *                the next embedded program.  Pass false when the next program
   *                handles the motor stop itself; true for all three of the
   *                current call sites (positionAxis, offsetAxis, selectWheel
   *                want the motor parked before their #MoveX/#SelectX runs;
   *                stopAxis wants #StopX to do its own STx).
   *
   * Sequence:
   *   1. Query IS CmdState for activeThread
   *   2. If activeThread > 0: send HaltExecution to CI actor (HX kills the thread)
   *   3. After successful HX: mark the registry entry Halted in IS
   *      (ThreadHalted; ADR-001), so scan attribution neither completes the
   *      dead thread nor misattributes the halted program's ae[] residue to
   *      whatever command runs next on this axis.
   *   4. If sendST: send ST to stop motor motion
   *   5. Set commandHalted=true; active CommandWatcher sees this and reports CommandFailure
   *   6. 10ms delay for watcher to observe the flag
   *   7. Clear commandHalted; new command will set its own activeCommand
   *   8. UnregisterThread to IS (after successful HX): removes the registry
   *      entry and releases the CI actor's thread reservation. Required
   *      because a Halted entry is excluded from scan attribution, so no
   *      scan will ever complete it.
   *
   * Tracking special case: no embedded thread is running (PVT executes from the
   * controller's per-axis FIFO, not an embedded program), so activeThread=0 and
   * HX is skipped.  The motor is still physically moving, but the caller is
   * responsible for the actual stop; either by sending ST directly (sendST=true)
   * or by running a follow-on program like #StopX that begins with STx.
   */
  private def checkAndInterrupt(
    commandName: String,
    axis: Axis,
    ciActor: ActorRef[GalilCommandMessage],
    internalStateActor: ActorRef[InternalStateActor.Command],
    log: csw.logging.api.scaladsl.Logger,
    askTimeout: Timeout,
    askScheduler: org.apache.pekko.actor.typed.Scheduler,
    ctx: org.apache.pekko.actor.typed.scaladsl.ActorContext[Command],
    sendST: Boolean = true,
    reuseHaltedThread: Boolean = false
  ): Option[Int] = {
    // Step 1: Query the axis's thread from IS's REGISTRY (GetAxisThread) —
    // the authoritative source — not from AxisCmdState.activeThread, which is
    // display state and diverges when a watcher timeout fires
    // clearActiveCommand (zeroing it) while the program still runs. Trusting
    // the display state made a post-timeout stopAxis skip the HX and the
    // Halted mark, then allocate a fresh thread while the axis's real thread
    // was still registered and reserved (S85 finding 4). activeCommand is
    // still read from CmdState (log context only).
    val activeThread: Int = Try {
      val future = AskPattern.Askable(internalStateActor).ask[Option[Int]](
        ref => InternalStateActor.GetAxisThread(axis, ref)
      )(askTimeout, askScheduler)
      Await.result(future, askTimeout.duration)
    }.toOption.flatten.getOrElse(0)
    val activeCmd: Option[ActiveCommand] = Try {
      val future = AskPattern.Askable(internalStateActor).ask[Option[AxisCmdState]](
        ref => InternalStateActor.GetAxisCmdState(axis, ref)
      )(askTimeout, askScheduler)
      Await.result(future, askTimeout.duration).flatMap(_.activeCommand)
    }.getOrElse(None)

    log.info(s"checkAndInterrupt: $commandName interrupting axis $axis " +
      s"(thread=$activeThread, cmd=$activeCmd)")

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

    // Step 3: After successful HX, mark the registry entry Halted in IS
    // (ADR-001) before the next program registers. A Halted entry is excluded
    // from scan attribution: the dead thread's observed-clear must neither
    // complete the interrupted command (the watcher owns that response via
    // the commandHalted pulse) nor let the halted program's ae[axis]==1
    // residue be misattributed as a failure of whatever command runs next on
    // this axis — particularly when the follow-on reuses the same thread
    // number. The synchronous ask guarantees the mark is in place before the
    // follow-on program launches. Skipped on HX failure (nothing was halted)
    // and on the already-released path (no activeThread; Tracking case).
    if haltSucceeded then
      val notifyResult = Try {
        val future = AskPattern.Askable(internalStateActor).ask[InternalStateActor.ThreadHaltedAck](
          ref => InternalStateActor.ThreadHalted(activeThread, axis, ref)
        )(askTimeout, askScheduler)
        Await.result(future, askTimeout.duration)
      }
      notifyResult match {
        case Failure(ex) =>
          log.warn(s"checkAndInterrupt: ThreadHalted($activeThread, $axis) failed: ${ex.getMessage} " +
            s"— scan attribution may misattribute the halted program's residue")
        case Success(_) =>
          log.debug(s"checkAndInterrupt: axis $axis thread $activeThread marked Halted in IS")
      }

    // Step 4: Stop motor motion if requested.  Omitted when the follow-on
    // program handles its own motor stop (e.g. #StopX, which begins with STx).
    // For positionAxis/offsetAxis/selectWheel interrupting a move, the follow-on
    // program is #MoveX/#SelectX which assumes the motor is already parked, so
    // sendST=true is correct there.
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

    // Step 7: Clear flag; new command handler will set its own activeCommand
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("commandHalted" -> false),
      ctx.system.ignoreRef)

    // Step 8: Explicit registry exit for the halted thread. The entry was
    // marked Halted in step 3, so no scan will ever attribute (complete) it —
    // IS's registry entry and the CI actor's reservation need this explicit
    // release. Sent AFTER the commandHalted pulse (steps 5-7) so the old
    // watcher terminates on commandHalted (INTERRUPTED) before the
    // activeThread→0 notification could reach it; mailbox order from CH to
    // IS, and IS to the watcher, preserves this. Idempotent in IS if a scan
    // completion raced the halt.
    // Step 8 (S84): skip the registry exit when the follow-on will REUSE this
    // thread — retain its reservation and registry entry (released later via
    // normal attribution) instead of releasing and racing a re-allocation.
    // Safe in the gap: the Halted mark (step 3) excludes the entry from scan
    // attribution until executeProgramAndWatch re-registers it (Halted →
    // Active, same thread, same axis).
    if haltSucceeded && !reuseHaltedThread then
      internalStateActor ! InternalStateActor.UnregisterThread(activeThread, axis)

    log.info(s"checkAndInterrupt: interruption complete for axis $axis — " +
      s"new command $commandName may proceed")

    // S84: hand the halted thread back for reuse when we retained its reservation
    // (reuseHaltedThread); None otherwise, so callers then allocate as before.
    if haltSucceeded && reuseHaltedThread then Some(activeThread) else None
  }

  // ========================================
  // Long-running command defaults
  // ========================================

  private val defaultMotionTimeout = 3.minutes

  /** Minimum timeout floor; even very short moves get this much time */
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
      // Trapezoidal profile; reaches max speed
      val dCruise = distance - dAccel - dDecel
      val tCruise = dCruise / maxSpeed
      tAccel + tCruise + tDecel
    } else {
      // Triangular profile; doesn't reach max speed
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
   *   Forward ; always approach from below (increasing counts)
   *   Reverse ; always approach from above (decreasing counts)
   *   Shortest; take the shorter of the two arcs
   *
   * The result may differ from the raw target by a whole number of revolutions
   * (countsPerRev = 360 * cpd). The IS demand and the embedded dmd[] variable are set
   * to the adjusted value so that the motion profile and inPosition calculations are correct.
   *
   * @param rawTarget      Raw count demand supplied by the Assembly
   * @param currentPos     Current encoder position (from IS AxisState.position)
   * @param countsPerRev   Counts per revolution (AxisState.countsPerRevolution); integer value
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
   * This is the standard pattern for long-running commands that invoke
   * embedded programs (homeAxis, positionAxis, offsetAxis, selectWheel,
   * positionWheel, stopAxis).  Note: trackAxis under PVT does NOT use this
   * path; it writes PVA segments directly to the controller via sendToController
   * and completes immediately on FIFO acceptance.
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
    onSuccessAxisUpdates: Map[String, Any] = Map.empty,
    forceThread: Option[Int] = None
  ): Unit = {
    // Step 1: Pre-escalate polling rate so ControllerStatusActor is at action rate
    // before the program starts. This ensures IS updates flow quickly.
    statusMonitor ! ControllerStatusActor.SetPollingRate(10.0)

    // Step 2: Execute program via CI actor (thread allocated from pool, optional preCommands,
    // and "XQ;MG _XQ<thread>" compound all inside galilIo.synchronized in the CI actor)
    def executeOnce(): Try[GalilCommandMessage.ExecuteProgramResult] = Try {
      val future = AskPattern.Askable(ciActor).ask[GalilCommandMessage.ExecuteProgramResult](
        ref => GalilCommandMessage.ExecuteProgram(label, ref, preCommands, forceThread)
      )(askTimeout, askScheduler)
      Await.result(future, askTimeout.duration)
    }
    var result = executeOnce()

    // Transient-exhaustion retry (S85 finding 3): under a stop burst, the
    // ReleaseThread messages from the scan that attributed the previous
    // completions can still be queued BEHIND our ExecuteProgram in the CI
    // actor's mailbox — allocation then sees every thread reserved while the
    // hardware shows them all free, and fails a command that would succeed
    // milliseconds later. One bounded retry after a beat covers exactly that
    // in-flight-release race; a second failure is reported as before (genuine
    // exhaustion, or a leaked reservation to investigate).
    if result.toOption.exists(_.error.exists(_.startsWith("No threads available"))) then
      log.warn(s"$commandName $axis: no threads allocatable (releases may be in flight) — retrying once in 200ms")
      Thread.sleep(200)
      result = executeOnce()

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
        // mask only after IS observes the next QR scan; which is also when CS
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
  // positionAxis; SDD 4.8.4, ICD 2.2.1.7
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

    // SDD 4.8.1 interruption is applied BELOW, just before executing the move (after
    // the soft-limit and at-target checks), so a rejected or no-op command never
    // disturbs an in-flight move and the halted thread is always reused. (S84 Option 1)

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

    // Defensive soft-limit check.  Both entry paths run the same envelope check
    // before accepting the command (CSW: GalilHcd.validateAxisStateAndLimits;
    // HMI: HmiServer.axisCommandRejection), each via CommandGate.checkSoftLimit.
    // This backstop catches any path that bypasses both; and is itself a no-op
    // for rotating axes, axes with softLimitsEnabled=false, or axes whose limits
    // are not configured.
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

    // SDD 4.8.1 interruption, applied here (after all early-return checks, before
    // setting the new command's state so a dying interrupted watcher's
    // clearActiveCommand cannot wipe the new activeCommand): halt the in-flight
    // move's thread and reuse it via forceThread. (S84 Option 1 reorder)
    val reuseThread: Option[Int] =
      if maybeAxisState.exists(_.axisState == AxisStateEnum.Moving) then
        checkAndInterrupt("positionAxis", axis, ciActor, internalStateActor, log, askTimeout, askScheduler, ctx,
          reuseHaltedThread = true)
      else None

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
    // block, atomically before XQ; eliminating a separate CI round-trip.
    executeProgramAndWatch(
      label = s"Move${axis.char}",
      axis = axis,
      commandName = "positionAxis",
      forceThread = reuseThread,
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
  // homeAxis; SDD 4.8.3, ICD 2.2.1.6
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
  // stopAxis; SDD 4.8.5, ICD 2.2.1.11
  // ========================================

  /**
   * Stops any active motion on the specified axis by executing the embedded #StopX program.
   *
   * If the axis is Moving or Homing, the active embedded program is halted first via
   * checkAndInterrupt (HX only; no ST) before #StopX runs. #StopX handles motor
   * deceleration itself, so a separate ST would be redundant.
   *
   * For Tracking, #StopX runs directly; there is no embedded thread (PVT runs
   * FIFO-driven on the controller, not via an #TrackX program), so there's
   * nothing to interrupt.  STx inside #StopX is what physically stops the
   * motor and drains any pending PVT FIFO segments.
   *
   * Sequence:
   *   1. Query axisState to determine whether interruption is needed and the completion state
   *   2. If Moving or Homing: checkAndInterrupt (HX active thread only; no ST, #StopX handles deceleration)
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
    // The completion state depends on both the current axisState and the homed flag , 
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

    // Interrupt-before-#StopX: only needed when an embedded program is currently
    // running on the axis (Moving = #MoveX, Homing = #HomeX).  For Tracking,
    // there's no embedded thread; PVT runs FIFO-driven on the controller; so
    // there's nothing to interrupt.  #StopX (run next) begins with STx, which
    // handles the actual motor stop in all three cases.
    val reuseThread: Option[Int] =
      if currentAxisState == AxisStateEnum.Moving || currentAxisState == AxisStateEnum.Homing then
        checkAndInterrupt("stopAxis", axis, ciActor, internalStateActor, log, askTimeout, askScheduler, ctx,
          sendST = false, reuseHaltedThread = true)
      else None

    // Update active command for this stop
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Stop, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // Execute the embedded stop program. #StopX is responsible for the full
    // application-defined stop sequence: motor deceleration, brakes, I/O updates, etc.
    // For a Tracking-→-Idle stop, we additionally clear the trackingSession ledger on
    // completion to maintain the invariant `axisState == Tracking ⇔ trackingSession.isDefined`.
    val onSuccessUpdates: Map[String, Any] =
      if currentAxisState == AxisStateEnum.Tracking then
        Map("trackingSession" -> None)
      else
        Map.empty

    executeProgramAndWatch(
      label = s"Stop${axis.char}",
      axis = axis,
      commandName = "stopAxis",
      forceThread = reuseThread,
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
      timeout = 5.seconds,
      onSuccessAxisUpdates = onSuccessUpdates
    )
  }

  // ========================================
  // offsetAxis; ICD 2.2.1.8
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

    // SDD 4.8.1 interruption is applied BELOW, just before executing the move (after
    // the not-initialized, soft-limit and zero-distance checks), so a rejected or
    // no-op command never disturbs an in-flight move and the halted thread is always
    // reused. (S84 Option 1)

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

    // Defensive soft-limit check.  See handlePositionAxis for the full rationale , 
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

    // Zero-distance offset; already at target, complete immediately
    if Math.abs(distance) <= currentState.get.inPositionThreshold then
      log.info(s"offsetAxis $axis: zero distance, already at target (distance=$distance)")
      internalStateActor ! InternalStateActor.UpdateAxisState(axis,
        Map("demand" -> target),
        ctx.system.ignoreRef)
      crm.updateCommand(Completed(runId))
      return

    // SDD 4.8.1 interruption, applied here (after all early-return checks, before
    // setting the new command's state so a dying interrupted watcher's
    // clearActiveCommand cannot wipe the new activeCommand): halt the in-flight
    // move's thread and reuse it via forceThread. (S84 Option 1 reorder)
    val reuseThread: Option[Int] =
      if currentState.exists(_.axisState == AxisStateEnum.Moving) then
        checkAndInterrupt("offsetAxis", axis, ciActor, internalStateActor, log, askTimeout, askScheduler, ctx,
          reuseHaltedThread = true)
      else None

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
    // block, atomically before XQ; eliminating a separate CI round-trip.
    executeProgramAndWatch(
      label = s"Move${axis.char}",
      axis = axis,
      commandName = "offsetAxis",
      forceThread = reuseThread,
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
  // selectWheel; SDD 3.3.2.5, ICD 2.2.1.9
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

    val reuseThread: Option[Int] =
      if maybeWheelState.exists(_.axisState == AxisStateEnum.Moving) then
        checkAndInterrupt("selectWheel", axis, ciActor, internalStateActor, log, askTimeout, askScheduler, ctx,
          reuseHaltedThread = true)
      else None

    // 1. Update AxisCmdState: set active command, clear error
    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Select, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // 2. Transition to Moving and record the commanded wheel slot. While set, inPosition
    // for this axis is driven by the embedded #Select program's declared success
    // (wheelPosition == this slot), not the encoder angle — see calculateInPosition.
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map("axisState" -> AxisStateEnum.Moving, "commandedWheelPosition" -> position),
      ctx.system.ignoreRef)

    // 3. Execute embedded program with thread confirmation + watcher.
    // dmd[idx]=position is sent as preCommands inside ExecuteProgram's galilIo.synchronized
    // block, atomically before XQ; eliminating a separate CI round-trip.
    executeProgramAndWatch(
      label = s"Select${axis.char}",
      axis = axis,
      commandName = "selectWheel",
      forceThread = reuseThread,
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
  // positionWheel; ICD 2.2.1.10
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

    // SDD 4.8.1 interruption is applied BELOW, just before executing the move (after
    // the at-target check), so a no-op command never disturbs an in-flight move and
    // the halted thread is always reused. (S84 Option 1)

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

    // SDD 4.8.1 interruption, applied here (after all early-return checks, before
    // setting the new command's state so a dying interrupted watcher's
    // clearActiveCommand cannot wipe the new activeCommand): halt the in-flight
    // move's thread and reuse it via forceThread. (S84 Option 1 reorder)
    val reuseThread: Option[Int] =
      if maybeAxisState.exists(_.axisState == AxisStateEnum.Moving) then
        checkAndInterrupt("positionWheel", axis, ciActor, internalStateActor, log, askTimeout, askScheduler, ctx,
          reuseHaltedThread = true)
      else None

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
    // block, atomically before XQ; eliminating a separate CI round-trip.
    executeProgramAndWatch(
      label = s"Move${axis.char}",
      axis = axis,
      commandName = "positionWheel",
      forceThread = reuseThread,
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
  // trackAxis (PVT); see SDD §3.4
  // ========================================

  /**
   * Sets the specified axis into tracking mode using Galil PVT streaming.
   *
   * Each invocation writes one segment of a Position-Velocity-Time trajectory to
   * the controller's per-axis FIFO and Completes as soon as the segment is
   * accepted.  The "tracking session" lifetime is held in IS as `axisState =
   * Tracking` and the companion `trackingSession` ledger on `AxisState`; it is
   * NOT a long-running CSW command lifecycle.
   *
   * Per-segment sequence:
   *   - Query IS (AxisState, HcdState) to get current position, countsPerRevolution
   *     (rotating axes), the prior `TrackingSession` ledger (if any), and the
   *     controller's `_TM` sample period read at init.
   *   - Convert the user-frame target (degrees for rotating, counts for linear) and
   *     rate (deg/sec or counts/sec) into controller-frame counts/counts-per-sec.
   *   - Compute the PVA tuple:
   *         ΔP        = position_new_counts - prev_endpoint_counts
   *         V         = rate_in_counts_per_sec  (integer, segment-END velocity)
   *         T_samples = (validTime - prev_validTime).micros / _TM
   *     where for the first segment in an Idle axis prev_endpoint is the polled
   *     `AxisState.position` and prev_validTime is TAI-now (implicit "v_start = 0").
   *   - Send `PVA<x>=ΔP,V,T` and (first segment only) `BT<x>`, atomically as a
   *     single CI write.
   *   - On success: update AxisState (axisState = Tracking, demand, trackingSession),
   *     set AxisCmdState.activeCommand = Track, complete the CSW command.
   *
   * Guards (any one of which Errors the command):
   *   - HCD not initialized; controllerSamplePeriodMicros == 0
   *   - validTime not strictly increasing past prior session's lastValidTime
   *   - rate × T translates to a segment where (ΔP, V, T) collapse to (0, 0, 0) , 
   *     PVA=0,0,0 is the active end-of-trajectory marker and would truncate the queue
   *   - axisState ∉ {Idle, Tracking} at execution time (handled by guardAxisState)
   *
   * Future extensions (deferred):
   *   - Velocity-limit pre-check against AxisState.maxSpeed
   *   - Configurable upper bound on `validTime - now` (Assembly lookahead horizon)
   *   - Linear axes are unit-blind passthrough; exercised under tests but not on lab
   *     hardware in S64 (lab has two rotating steppers only)
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
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._

    val axisChoice = setup(TrackAxisCommand.axisKey).head
    val axis = Axis.fromChar(axisChoice.name.head)
    val positionUser = setup(TrackAxisCommand.positionKey).head.toDouble
    val rateUser = setup(TrackAxisCommand.rateKey).head.toDouble
    val validTime = setup(TrackAxisCommand.validTimeKey).head
    val validTimeInstant = validTime.value

    log.info(s"trackAxis $axis: position=$positionUser, rate=$rateUser, validTime=$validTimeInstant")

    // Re-validate axis state under the SDD figure 4-2 guard.  Closes the race with
    // onValidate.  trackAxis is permitted from {Idle, Tracking}.
    if guardAxisState("trackAxis", axis, runId, internalStateActor, crm, log, askTimeout, askScheduler).isDefined
    then return

    // Fetch AxisState (for position, cpr, mechanismType, trackingSession) and HcdState
    // (for controllerSamplePeriodMicros).  Two serial asks; IS processes serially so
    // these reflect a consistent snapshot relative to other CHA actions.
    val axisStateOpt: Option[AxisState] = Try {
      val f = internalStateActor.ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )(askTimeout, askScheduler)
      Await.result(f, askTimeout.duration)
    }.getOrElse(None)

    val hcdState: HcdState = Try {
      val f = internalStateActor.ask[HcdState](
        ref => InternalStateActor.GetHcdState(ref)
      )(askTimeout, askScheduler)
      Await.result(f, askTimeout.duration)
    }.getOrElse(HcdState())

    val axisState = axisStateOpt match {
      case Some(s) => s
      case None =>
        val msg = s"trackAxis $axis: axis state unavailable"
        log.error(msg)
        crm.updateCommand(Error(runId, msg))
        return
    }

    // Guard: HCD must have read _TM during init.
    val samplePeriodMicros = hcdState.controllerSamplePeriodMicros
    if samplePeriodMicros <= 0 then
      val msg = s"trackAxis $axis: controller sample period not yet known " +
                s"(_TM read pending) — HCD not fully initialized"
      log.error(msg)
      crm.updateCommand(Error(runId, msg))
      return

    // Unit conversion (user frame → controller counts frame).
    // Rotating axes: degrees → counts via countsPerRevolution / 360, integer arithmetic.
    // Linear axes: passthrough; HCD is unit-blind for linear (S63 design decision #3).
    val isRotating = axisState.mechanismType == MechanismType.Rotating
    val cprOpt = axisState.countsPerRevolution

    // For rotating axes without a known countsPerRevolution we cannot do the conversion.
    // This means the axis is mid-init; the embedded `cpd[]` read hasn't completed.
    if isRotating && cprOpt.isEmpty then
      val msg = s"trackAxis $axis: rotating axis has no countsPerRevolution " +
                s"(motion config not yet read from controller)"
      log.error(msg)
      crm.updateCommand(Error(runId, msg))
      return

    // userToCounts: converts a user-frame value (degrees or counts) to controller counts.
    // For rotating axes the formula is value * cpr / 360.  We round to nearest count;
    // accumulated rounding error across many segments is bounded because the ledger
    // stores actual delivered counts, not a recomputed-from-degrees count.
    def userToCounts(value: Double): Long =
      if isRotating then
        val cpr = cprOpt.get
        math.round(value * cpr / 360.0)
      else
        math.round(value)

    val positionCounts0 = userToCounts(positionUser)
    val rateCountsPerSec: Long =
      if isRotating then math.round(rateUser * cprOpt.get / 360.0)
      else                math.round(rateUser)

    // Figure out the prev-endpoint state.  Two cases:
    //   - Idle (first segment): prev endpoint is the polled motor position (in counts),
    //     prev validTime is "now" (TAI).  v_start at trajectory begin is 0; PVA does
    //     not take v_start, the controller infers it from the prior segment's V_end or
    //     from rest.
    //   - Tracking (subsequent segment): prev endpoint and prev validTime come from
    //     the trackingSession ledger.
    val nowInstant = TAITime.now().value
    val (prevEndpointCounts, prevValidTime, isFirstSegment): (Long, Instant, Boolean) =
      axisState.trackingSession match {
        case Some(s) => (s.lastTargetCounts, s.lastValidTime, false)
        case None    => (math.round(axisState.position), nowInstant, true)
      }

    // Rotating-axis wrap correction.
    //
    // The Assembly's trajectory frame is degrees in [0, 360); it has no concept of
    // accumulated revolutions.  The controller frame is accumulated counts and can
    // span many revolutions in either direction (encoder accumulates without wrap).
    // `positionCounts0` is the literal counts-equivalent of `positionUser` in the
    // current absolute frame (i.e. `positionUser × cpr / 360`, always near 0..cpr).
    // If we used it directly as the segment endpoint, then for a mechanism that's
    // accumulated several revolutions of count, `deltaP = positionCounts0 - prev`
    // would unwind multiple revolutions to reach a far smaller absolute count value
    //; visible to the operator as the motor doing an unexpected ~360° back-rotation.
    //
    // The intended behavior is: the Assembly says "go to physical angle θ"; the HCD
    // chooses the equivalent absolute count nearest to where the motor currently is
    // (so the motor moves the shortest arc to reach θ).  Equivalently: shift
    // `positionCounts0` by the integer number of revolutions that minimizes
    // `|positionCounts - prevEndpointCounts|`.
    //
    // Applies on every segment, not just the first.  Continuation segments encounter
    // the same wrap when the trajectory's degrees crosses 0/360 (segment N at 359°,
    // segment N+1 at 1°, both in 0..360); the Assembly hands us 1° meaning "+2°
    // ahead of where you are", not "-358° back".  The same logic resolves both.
    //
    // Non-rotating axes pass through unchanged: counts are already in absolute frame.
    val positionCounts: Long =
      if isRotating then
        val cpr = cprOpt.get.toLong  // safe: cpr is set from controller as a positive integer count
        // Diff in counts BEFORE wrap correction.  Can be ±many revolutions.
        val rawDelta = positionCounts0 - prevEndpointCounts
        // Reduce to the smallest-magnitude equivalent within ±cpr/2.  We compute
        // `rawDelta mod cpr` with Euclidean modulo (always non-negative), then if
        // that wraps past cpr/2 shift it into the negative half.  This puts
        // shortestDelta in [-cpr/2, +cpr/2).
        val mod          = Math.floorMod(rawDelta, cpr)
        val shortestDelta = if mod > cpr / 2 then mod - cpr else mod
        val adjusted     = prevEndpointCounts + shortestDelta
        if adjusted != positionCounts0 then
          val revShift = (adjusted - positionCounts0) / cpr
          log.info(s"trackAxis $axis: wrap-corrected positionCounts $positionCounts0 → $adjusted " +
                   s"(shift ${revShift} rev, rawDelta=$rawDelta → shortestDelta=$shortestDelta, " +
                   s"prevEndpointCounts=$prevEndpointCounts, cpr=$cpr)")
        adjusted
      else
        positionCounts0

    // Diagnostic dump (S65 bug hunt): when something downstream rejects, we need to
    // see EXACTLY what seeded the PVA math.  Prints once per trackAxis at INFO level.
    // Specifically tracks `trackingSession` ledger state and the "now vs prevValidTime"
    // relationship, since the STB axis D failure showed a 52,814,381,000µs delta that
    // could only come from a stale Some(_) ledger.
    axisState.trackingSession match {
      case Some(s) =>
        val ageSec = java.time.Duration.between(s.lastValidTime, nowInstant).toNanos / 1e9
        log.info(s"trackAxis $axis: SEEDING from trackingSession ledger — " +
                 s"lastTargetCounts=${s.lastTargetCounts}, " +
                 s"lastValidTime=${s.lastValidTime} (age ${"%.3f".format(ageSec)}s vs now $nowInstant), " +
                 s"btFiredAt=${s.btFiredAt}, segmentsSubmitted=${s.segmentsSubmitted}; " +
                 s"axisState.position=${axisState.position}")
      case None =>
        log.info(s"trackAxis $axis: SEEDING from polled position (first segment) — " +
                 s"axisState.position=${axisState.position} → counts=$prevEndpointCounts; " +
                 s"prevValidTime=nowInstant=$nowInstant; " +
                 s"new validTimeInstant=$validTimeInstant")
    }

    // Monotonic-validTime guard.  Strictly increasing is required so T_samples is positive.
    if !validTimeInstant.isAfter(prevValidTime) then
      val msg = s"trackAxis $axis: validTime $validTimeInstant not after prev " +
                s"$prevValidTime (must be strictly monotonically increasing)"
      log.warn(msg)
      crm.updateCommand(Error(runId, msg))
      return

    // Compute PVA arguments.
    val deltaP: Long = positionCounts - prevEndpointCounts
    val deltaMicros: Long = java.time.Duration.between(prevValidTime, validTimeInstant).toNanos / 1_000L
    val tSamples: Long = math.round(deltaMicros.toDouble / samplePeriodMicros.toDouble)

    // Guard: T_samples must be >= 1.  Sub-sample-period segments cannot be expressed.
    if tSamples < 1 then
      val msg = s"trackAxis $axis: validTime delta ${deltaMicros}µs is shorter than " +
                s"one controller sample period (${samplePeriodMicros}µs) — segment " +
                s"too short to express"
      log.warn(msg)
      crm.updateCommand(Error(runId, msg))
      return

    // Guard: PVA argument bounds (Galil DMC-40x0 Command Reference, "PV PVT Data").
    // The controller rejects out-of-range arguments with ':?', and the subsequent
    // 'TC 6 Number out of range' faults the HCD.  Reject BEFORE the wire write so
    // the failure surfaces as a clean command error with diagnostic text instead
    // of as a fault.  The T cap (2,048 samples ≈ 2.048s @ TM=1000) is the
    // binding constraint for normal tracking; a validTime gap > ~2s exceeds it.
    //   ΔP: ±44,000,000 counts
    //   V : ±22,000,000 counts/sec
    //   T :   1..2,048 samples
    if math.abs(deltaP) > PvaMaxDeltaPosition then
      val msg = s"trackAxis $axis: ΔP=$deltaP counts exceeds PVA position bound " +
                s"(±$PvaMaxDeltaPosition); validTime gap likely too large or " +
                s"prev-endpoint state stale"
      log.warn(msg)
      crm.updateCommand(Error(runId, msg))
      return

    if math.abs(rateCountsPerSec) > PvaMaxVelocity then
      val msg = s"trackAxis $axis: V=$rateCountsPerSec counts/sec exceeds PVA " +
                s"velocity bound (±$PvaMaxVelocity)"
      log.warn(msg)
      crm.updateCommand(Error(runId, msg))
      return

    if tSamples > PvaMaxTSamples then
      val gapSec = deltaMicros.toDouble / 1_000_000.0
      val msg = s"trackAxis $axis: T=$tSamples samples exceeds PVA time bound " +
                s"($PvaMaxTSamples samples ≈ ${PvaMaxTSamples * samplePeriodMicros / 1_000_000.0}s " +
                s"at TM=$samplePeriodMicros); validTime gap was ${"%.3f".format(gapSec)}s — " +
                s"either prev-endpoint state is stale or the gap between successive " +
                s"trackAxis calls is too long for the controller"
      log.warn(msg)
      crm.updateCommand(Error(runId, msg))
      return

    // Guard: per-axis velocity envelope (configured maxSpeed in counts/sec).
    //
    // Two distinct checks, both gated on the same axis maxSpeed:
    //
    //   (a) Requested rate.  |rateCountsPerSec| must be within maxSpeed.  Catches
    //       Assembly-side miscalculation: a trajectory whose instantaneous-rate
    //       sample exceeds what the mechanism is configured to do.
    //
    //   (b) Implied average velocity for this segment, |ΔP / T_seconds|.  This is
    //       the velocity the mechanism would have to sustain to physically arrive
    //       at the segment endpoint on time.  Catches the SEEDING-from-stale-
    //       position failure mode: if axisState.position lags reality (or if the
    //       Assembly's first target is far from where the mechanism actually is),
    //       the first segment computes a small T and a huge ΔP; the controller
    //       would dutifully slam toward the target at unsafe velocity.  Without
    //       this guard, the only mitigation was to physically move the mechanism
    //       to "near zero" before launching a TrackInjector run.
    //
    // maxSpeed = None ⇒ reject.  Tracking on an axis with no configured velocity
    // envelope is not safe by default; the operator should configure the axis
    // before tracking it.
    //
    // No safety margin: this is the configured envelope.  If the Assembly needs
    // to operate up to that limit, it will; anything beyond is the right thing
    // to refuse.  The error message includes both checks' values so the operator
    // (and the Assembly developer) can see which one tripped and by how much.
    axisState.maxSpeed match {
      case None =>
        val msg = s"trackAxis $axis: cannot start tracking — maxSpeed not configured " +
                  s"for this axis (configure via configAxis before tracking)"
        log.warn(msg)
        crm.updateCommand(Error(runId, msg))
        return

      case Some(maxSpeedCountsPerSec) =>
        // (a) Requested rate.
        if math.abs(rateCountsPerSec) > maxSpeedCountsPerSec then
          val msg = s"trackAxis $axis: requested rate ${rateCountsPerSec} counts/sec " +
                    s"exceeds configured maxSpeed ${maxSpeedCountsPerSec.toLong} counts/sec"
          log.warn(msg)
          crm.updateCommand(Error(runId, msg))
          return

        // (b) Implied average velocity.  T_seconds = T_samples × samplePeriodMicros / 1e6.
        // Using integer math to keep this exact:
        //   |ΔP| × 1_000_000  vs  maxSpeed × T × samplePeriodMicros   (counts/sec)
        val absDeltaP = math.abs(deltaP)
        val impliedVelocityNumerator   = absDeltaP * 1_000_000L
        val maxVelocityNumeratorDouble = maxSpeedCountsPerSec * tSamples.toDouble * samplePeriodMicros.toDouble
        if impliedVelocityNumerator.toDouble > maxVelocityNumeratorDouble then
          val impliedCountsPerSec = absDeltaP.toDouble * 1_000_000.0 / (tSamples * samplePeriodMicros)
          val seedingSource =
            if isFirstSegment then s"first segment SEEDed from axisState.position=${axisState.position}"
            else                    s"continuation from trackingSession ledger"
          val msg = s"trackAxis $axis: implied segment velocity " +
                    s"${"%.0f".format(impliedCountsPerSec)} counts/sec " +
                    s"(|ΔP|=$absDeltaP over ${tSamples * samplePeriodMicros / 1_000.0}ms) " +
                    s"exceeds maxSpeed ${maxSpeedCountsPerSec.toLong} counts/sec — " +
                    s"$seedingSource; the mechanism may need to be repositioned " +
                    s"closer to the trajectory start point before tracking"
          log.warn(msg)
          crm.updateCommand(Error(runId, msg))
          return
    }

    // Guard: avoid the (0, 0, 0) terminator collision.  If a user-supplied segment
    // legitimately has zero delta AND zero rate AND we round T to anything, the PVA
    // wire form would be 0,0,0; the active end-of-trajectory marker; which would
    // truncate the FIFO instead of expressing the intended "hold position" segment.
    // The fix is to ensure T is non-zero (we already guard tSamples >= 1), so
    // PVA=0,0,T with T > 0 expresses "hold here for T samples" unambiguously.
    // The remaining failure case is the pathological "all three zero" tuple, which
    // can only arise if our guards above let through tSamples == 0; defensive check:
    if deltaP == 0L && rateCountsPerSec == 0L && tSamples == 0L then
      val msg = s"trackAxis $axis: degenerate PVA=0,0,0 (would truncate FIFO)"
      log.error(msg)
      crm.updateCommand(Error(runId, msg))
      return

    // Build the wire command.  Galil PVT wire format:
    //   PV<axis>=ΔP,V,T   where the third letter of the command name IS the axis
    //                     designator (PVA = axis A, PVB = axis B, etc.); there is
    //                     no separate axis argument.
    //   BT<axis>          Begin Trajectory for the named axis only.  Bare BT (no
    //                     axis) would start all axes with pending segments; never
    //                     used in this project; we always operate per-axis.
    //
    // First segment of a session emits PVA + BT atomically in one TCP frame so the
    // FIFO starts executing immediately on receipt.  Subsequent segments are PVA
    // only; BT is only meaningful at session start.
    val pvaCmd = s"PV${axis.char}=$deltaP,$rateCountsPerSec,$tSamples"
    val wireCmd =
      if isFirstSegment then s"$pvaCmd;BT${axis.char}"
      else                    pvaCmd

    log.info(s"trackAxis $axis: ${if isFirstSegment then "FIRST" else "cont"} segment " +
             s"ΔP=$deltaP V=$rateCountsPerSec T=$tSamples (validTime delta ${deltaMicros}µs); " +
             s"wire='$wireCmd'")

    // Write to controller.
    val sendResult = sendToController(ciActor, wireCmd, log, askTimeout, askScheduler)
    sendResult match {
      case Failure(ex) =>
        val msg = s"trackAxis $axis: PVA write failed: ${ex.getMessage}"
        log.error(msg)
        crm.updateCommand(Error(runId, msg))
        return
      case Success(response) =>
        log.debug(s"trackAxis $axis: controller responded '$response'")
    }

    // Update IS; atomic from CHA's perspective.  Session ledger, demand, axis state.
    val btFiredAt = if isFirstSegment then nowInstant else
      axisState.trackingSession.map(_.btFiredAt).getOrElse(nowInstant)
    val newSegmentCount =
      axisState.trackingSession.map(_.segmentsSubmitted + 1L).getOrElse(1L)
    val newSession = TrackingSession(
      lastTargetCounts = positionCounts,
      lastValidTime    = validTimeInstant,
      btFiredAt        = btFiredAt,
      segmentsSubmitted = newSegmentCount
    )

    internalStateActor ! InternalStateActor.UpdateAxisCmdState(axis,
      Map("activeCommand" -> ActiveCommand.Track, "axisErrorMsg" -> ""),
      ctx.system.ignoreRef)

    // demand uses controller-frame counts so HMI/CSW publication is consistent with
    // how positionAxis / offsetAxis store demand.  (`motorDemand` wraps to [0, cpr)
    // for display purposes.)
    internalStateActor ! InternalStateActor.UpdateAxisState(axis,
      Map(
        "demand"           -> positionCounts.toDouble,
        "axisState"        -> AxisStateEnum.Tracking,
        "trackingSession"  -> newSession
      ),
      ctx.system.ignoreRef)

    // PVT segment Completes as soon as the FIFO accepts it.  Per S63 design #8: each
    // trackAxis runId completes within milliseconds of submission ("PVA accepted into
    // FIFO" = Completed).  The "tracking session" is an axis state, not a long-running
    // CSW command lifecycle.
    crm.updateCommand(Completed(runId))
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

  // faultReset is handled by GalilHcd.onSubmit, not here, because it drives
  // HCD-level lifecycle state (Faulted → Uninitialized → Ready) and re-uses the
  // shared runInitSequence(). See GalilHcd.handleFaultReset.
}