package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import com.typesafe.config.{Config, ConfigFactory}
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse.{SubmitResponse, ValidateCommandResponse}
import csw.params.commands._
import csw.params.core.models.{Id, ObsId}
import csw.logging.client.commons.LogAdminUtil
import csw.logging.models.Level
import csw.prefix.models.Subsystem
import csw.proto.galil.hcd.ControllerCommandActor.ControllerIdentity
import csw.proto.galil.hcd.GalilCommandMessage.GalilCommand
import csw.proto.galil.io.DataRecord
import csw.time.core.models.UTCTime

import scala.compiletime.uninitialized
import scala.concurrent.{ExecutionContextExecutor, Future}

// Add messages here...
sealed trait GalilCommandMessage

object GalilCommandMessage {

  case class GalilCommand(commandString: String) extends GalilCommandMessage

  // SendCommandResult is used by both ControllerCommandActor and ControllerStatusActor
  
  // Ask the CI actor for its controller identity (available after Behaviors.setup completes)
  case class GetIdentity(replyTo: ActorRef[ControllerIdentity]) extends GalilCommandMessage
  
  // Download current embedded program from controller (UL command)
  // Returns the program text as a String
  case class DownloadProgram(replyTo: ActorRef[DownloadProgramResult]) extends GalilCommandMessage
  case class DownloadProgramResult(program: String, error: Option[String] = None) extends GalilCommandMessage

  // Synchronous command execution for CommandHandlerActor — uses the dedicated command connection.
  case class SendCommand(commandString: String, replyTo: ActorRef[SendCommandResult]) extends GalilCommandMessage
  case class SendCommandResult(response: String, error: Option[String] = None) extends GalilCommandMessage

  /** Set the socket read timeout on the command connection. 0 = infinite (block until response).
   *  Used during axis setup to survive BZ commutation pauses without desynchronizing the socket. */
  case class SetReadTimeout(timeoutMs: Int, replyTo: ActorRef[SendCommandResult]) extends GalilCommandMessage



  /**
   * Execute an embedded program with automatic thread allocation.
   *
   * The CI actor queries MG _NO to find a free thread (1-7), optionally sends
   * preCommands as a single compound command, sends "XQ #label,thread", then
   * queries MG _NO again to confirm the thread started. Both MG _NO results
   * update IS threadStatus in real-time. The allocated thread number is returned
   * in the result.
   *
   * preCommands (if present) is sent atomically within the same galilIo.synchronized
   * block as the XQ — eliminating a separate CI round-trip for callers that need to
   * set embedded variables (e.g. dmd[idx]=target) immediately before launching a program.
   * If the preCommands send fails, ExecuteProgram returns an error and XQ is not sent.
   *
   * Thread management uses the hardware as the source of truth — no
   * separate pool bookkeeping. The controller's _NO bitmask is always
   * authoritative for thread availability.
   *
   * @param label       Program label without # prefix (e.g. "MoveA", "HomeB")
   * @param replyTo     Actor to receive the result
   * @param preCommands Optional semicolon-joined command string to send before XQ
   *                    (e.g. "dmd[0]=1000" or "Atarget[0]=500;Atarget[1]=10")
   */
  case class ExecuteProgram(
    label: String,
    replyTo: ActorRef[ExecuteProgramResult],
    preCommands: Option[String] = None
  ) extends GalilCommandMessage

  /**
   * Result of ExecuteProgram.
   *
   * @param thread           Thread number that was allocated and used
   * @param threadWasActive  true if MG _NO confirmed the thread was running after XQ
   * @param error            None on success, Some(message) if XQ was rejected or no threads available
   */
  case class ExecuteProgramResult(
    thread: Int,
    threadWasActive: Boolean,
    error: Option[String] = None
  )

  /**
   * Halt an active execution thread (SDD 4.8.1 — Halting the Active Command).
   *
   * The CI actor confirms the thread is still active via MG _NO and sends HX to halt
   * it if so. All operations are synchronized under galilIo.synchronized to prevent
   * interleaving with QR polling.
   *
   * This message only kills the thread — it does NOT send ST. The caller is responsible
   * for any subsequent motor stop (ST) or embedded stop program (#StopX) as appropriate:
   *   - checkAndInterrupt: sends ST after HaltExecution, then starts a new embedded program
   *   - stopAxis: does not need ST — #StopX handles motor deceleration
   *
   * @param thread  Thread number from IS.activeThread. Use 0 to skip HX entirely.
   * @param axis    Axis identifier (used for logging context)
   * @param replyTo Actor to receive HaltExecutionResult
   */
  case class HaltExecution(
    thread: Int,
    axis: Axis,
    replyTo: ActorRef[HaltExecutionResult]
  ) extends GalilCommandMessage

  /**
   * Result of HaltExecution.
   *
   * @param success true if HX succeeded (or thread was already finished and was skipped)
   * @param error   None on success, Some(message) on failure
   */
  case class HaltExecutionResult(
    success: Boolean,
    error: Option[String] = None
  )

  /**
   * Result of a Reconnect attempt.
   * @param success true if the connection is now working (existing or freshly opened)
   * @param error   None on success, Some(description) on failure
   */
  case class ReconnectResult(success: Boolean, error: Option[String] = None)

  /**
   * Attempt to verify and if necessary re-establish the command TCP connection.
   *
   * Step 1: test the existing socket with a lightweight command (MG 0).
   *   - If that succeeds the connection never actually dropped — report Connected, done.
   * Step 2: if the test fails, close the dead socket and open a fresh GalilIoTcp.
   *   - Retest with MG 0. Report Connected on success, Disconnected on failure.
   *
   * Reports commandConnection Connected/Disconnected to IS in either outcome.
   * Used by faultReset (None severity) to recover from a detected connection loss.
   */
  case class Reconnect(replyTo: ActorRef[ReconnectResult]) extends GalilCommandMessage

}

class GalilHcdHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx) {

  import cswCtx._

  private val log                           = loggerFactory.getLogger(ctx)
  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  
  // HCD Configuration - loaded during initialization
  // SDD Section 4.2: "Load controller and axis-specific parameters from the CSW Configuration Service"
  private var hcdConfig: GalilHcdConfig = uninitialized
  
  // ========================================
  // Configuration Loading
  // ========================================
  
  /**
   * Load HCD configuration from Configuration Service
   * 
   * Per SDD Section 4.2: "On startup, the HCD retrieves its config file from 
   * the service to initialize axes, motion profiles, and I/O parameters."
   * 
   * For local testing, config file path can be specified via system property:
   *   -Dgalil.config.path=GalilHcdConfig-Hardware.conf
   * Default is GalilHcdConfig.conf
   * 
   * In production, this will use CSW Configuration Service:
   *   val configClient = ConfigClientFactory.clientApi(ctx.system, locationService)
   *   configClient.getActive(Paths.get("galil/GalilHcdConfig-Hardware.conf"))
   */
  private def loadConfiguration(): GalilHcdConfig = {
    // Get config file path from system property, default to GalilHcdConfig.conf
    val configPath = sys.props.getOrElse("galil.config.path", "GalilHcdConfig.conf")
    
    log.info(s"Loading HCD configuration from $configPath")
    
    try {
      // For local testing: load from resources
      // In production: replace with ConfigClientService.getActive()
      val config = ConfigFactory.load(configPath.stripSuffix(".conf"))
      val hcdConfig = GalilHcdConfig.fromConfig(config)
      
      log.info(s"Configuration loaded successfully from $configPath")
      log.info(s"  Controller: ${hcdConfig.controller.hostString}:${hcdConfig.controller.port}")
      log.info(s"  Controller ID: ${hcdConfig.controller.id}")
      log.info(s"  Embedded Program: ${hcdConfig.controller.embeddedProgram}")
      log.info(s"  Simulation Mode: ${hcdConfig.simulate}")
      log.info(s"  Active Axes: ${hcdConfig.activeAxes.zipWithIndex.filter(_._1).map(p => ('A' + p._2).toChar).mkString(", ")}")
      
      hcdConfig
    } catch {
      case ex: Exception =>
        log.error(s"Failed to load configuration from $configPath: ${ex.getMessage}", ex = ex)
        log.warn("Using default test configuration")
        GalilHcdConfig.defaultTestConfig
    }
  }
  
  // ========================================
  // State Management Actors
  // ========================================
  
  // 1. InternalStateActor - Central state repository (no dependencies)
  private val internalStateActor: ActorRef[InternalStateActor.Command] =
    ctx.spawn(InternalStateActor.apply(loggerFactory), "InternalStateActor")
  
  // 2. ControllerCommandActor - command TCP connection, created during initialize() after config is loaded
  //    Owns the GalilIo TCP connection. All Galil I/O goes through this actor.
  private var controllerCommandActor: ActorRef[GalilCommandMessage] = uninitialized
  
  // 3. ControllerStatusActor - status TCP connection and QR polling, created during initialize()
  private var statusMonitor: ActorRef[ControllerStatusActor.Command] = uninitialized

  // 4. ControllerConsoleActor - console TCP handle, hardware-only, spawned after command actor is ready
  private var consoleActor: ActorRef[ControllerConsoleActor.Command] = uninitialized
  
  // 4. CommandHandlerActor - created during initialize() after CI actor and InternalState are ready
  private var commandHandlerActor: ActorRef[CommandHandlerActor.Command] = uninitialized
  
  // 4. CurrentStatePublisher - CSW current state publications
  private val currentStatePublisher: ActorRef[CurrentStatePublisherActor.Command] =
    ctx.spawn(
      CurrentStatePublisherActor.behavior(
        componentInfo.prefix,
        internalStateActor,
        cswCtx.currentStatePublisher,  // Pass CSW's publisher directly!
        loggerFactory
      ),
      "CurrentStatePublisher"
    )

  // 5. HMI Server - Embedded HTTP/WebSocket test console (created during initialize)
  private var hmiServer: hmi.HmiServer = uninitialized

  // ========================================
  // Lifecycle Handlers
  // ========================================
  
  override def initialize(): Unit = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    
    implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(5.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
    
    // Set the component log level explicitly at the very start of initialize(), before any
    // actor or framework code has a chance to emit DEBUG messages.
    //
    // BACKGROUND: CSW's ComponentLoggingStateManager.from() builds the per-component level map
    // from the component-log-levels HOCON block at startup.  The map key is constructed by
    // calling Prefix.apply(entrySet().getKey()) on the raw HOCON path string.  For component
    // names that contain multiple internal dots (e.g. "ICS.HCD.GalilMotion.1" or ".one"),
    // the Typesafe Config path rendering does not round-trip cleanly back to the runtime Prefix
    // value: the key is stored under the wrong string, causing a silent miss, and all loggers
    // fall back to defaultLogLevel (= logLevel in application.conf = DEBUG).
    //
    // The fix uses the same runtime path as the HMI: LogAdminUtil.setComponentLogLevel()
    // accepts the live Prefix object directly — no HOCON string round-trip — and writes into
    // the same ConcurrentHashMap that LoggerImpl.componentLoggingState reads on every log call.
    //
    // logLevel in application.conf must remain "debug" (Gate 2 open) so that runtime elevation
    // to DEBUG via the HMI is not permanently blocked by LogActor's global level floor.
    //
    // TODO: identify the exact Typesafe Config entrySet() key-rendering rule for multi-dot
    // component names and file a CSW issue / workaround so component-log-levels can be used
    // reliably for such prefixes in the future.  Testing confirmed the numeric-suffix theory
    // ("1" -> "one") does NOT fix the problem; the issue is the depth of nesting.
    LogAdminUtil.setComponentLogLevel(componentInfo.prefix, Level.INFO)

    log.info("Initializing Galil HCD")
    
    // Phase 1: Load controller and axis-specific parameters from CSW Configuration Service
    hcdConfig = loadConfiguration()
    
    // Phase 2: Connect to controller and verify identity
    // Create ControllerCommandActor — opens command TCP connection.
    // The actor connects to the Galil controller and identifies it during Behaviors.setup.
    // We use the ask pattern (standard CSW approach) to block until the actor is ready.
    log.info("Establishing controller connection")
    val galilConfig = GalilConfig(hcdConfig.controller.hostString, hcdConfig.controller.port)
    controllerCommandActor = ctx.spawn(
      ControllerCommandActor.behavior(
        galilConfig,
        loggerFactory,
        internalStateActor,
        simulate = hcdConfig.simulate
      ),
      "ControllerCommandActor"
    )
    
    // Block until the actor has completed setup (connect + ID) — standard CSW init pattern
    val identityFuture = controllerCommandActor.ask[ControllerIdentity](ref => GalilCommandMessage.GetIdentity(ref))
    val identity = Await.result(identityFuture, 5.seconds)
    log.info(s"Controller ready: firmware=${identity.firmware}, model=DMC-${identity.model}, axes=${identity.axisCount}")

    // Store controller axis count in HcdState so HMI can determine I/O capabilities
    // (8-axis controllers support slave I/O expansion module, enabling bits 9-16)
    if identity.axisCount > 0 then
      internalStateActor ! InternalStateActor.UpdateHcdState(
        Map("controllerAxisCount" -> identity.axisCount),
        ctx.system.ignoreRef
      )

    // Phase 2b: Spawn ControllerConsoleActor as a sibling (hardware-only).
    // Opens a dedicated third TCP handle; CF I + CW 2 claim all unsolicited MG output.
    // The latch blocks until the handle is live so MG lines are captured before #Init runs.
    // Skipped in simulation mode — no physical controller to receive CF I / CW 2.
    if !hcdConfig.simulate then
      val consoleLatch = new java.util.concurrent.CountDownLatch(1)
      consoleActor = ctx.spawn(
        ControllerConsoleActor(
          host               = galilConfig.host,
          port               = galilConfig.port,
          prefix             = componentInfo.prefix,
          log                = log,
          readyLatch         = consoleLatch,
          internalStateActor = internalStateActor
        ),
        "ControllerConsoleActor"
      )
      val ready = consoleLatch.await(ControllerConsoleActor.ReadyTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
      if !ready then
        log.warn("ControllerConsoleActor did not become ready within timeout — proceeding without MG capture")
    
    // Phase 3: Start status monitoring with adaptive polling rate
    // Motor type (stepper vs servo) is read from the QR DataRecord switches byte,
    // not from config — the embedded code sets this during axis Setup.
    // Polling rate adapts automatically based on axis states:
    //   standby rate: all axes idle/lost/error
    //   action rate:  any axis homing/moving/tracking
    val standbyRate = hcdConfig.controller.standbyPollingRateHz
    val actionRate = hcdConfig.controller.actionPollingRateHz

    // Build the set of configured axes (per hcdConfig.activeAxes flags) for CS
    // to scope its per-scan ae[] reads. CS uses this set to issue
    // "MG ae[<idx1>],ae[<idx2>],..." for exactly the axes that exist on the
    // controller, ignoring any unused axis slots.
    val configuredAxesSet: Set[Axis] =
      hcdConfig.activeAxes.zipWithIndex.collect {
        case (true, idx) if idx < Axis.values.length => Axis.values(idx)
      }.toSet

    statusMonitor = ctx.spawn(
      ControllerStatusActor.apply(
        galilConfig,
        internalStateActor,
        loggerFactory,
        commandActor = controllerCommandActor,
        configuredAxes = configuredAxesSet,
        standbyPollingRateHz = standbyRate,
        actionPollingRateHz = actionRate
      ),
      "ControllerStatusActor"
    )
    statusMonitor ! ControllerStatusActor.SetPolling(enabled = true)
    log.info(s"ControllerStatusActor created - standby: ${standbyRate}Hz, action: ${actionRate}Hz, " +
      s"configured axes: ${configuredAxesSet.toSeq.sortBy(_.index).mkString(",")}")

    // Wire IS → CS so IS can forward RegisterAxisThread / ClearAxisThread
    // events. Must happen after CS is spawned (CS doesn't exist when IS is
    // constructed at HCD startup). Without this, CS's axis→thread map stays
    // empty and per-axis program-error attribution from ae[] is disabled.
    internalStateActor ! InternalStateActor.SetStatusActor(statusMonitor)

    // Store configured polling rates in IS
    internalStateActor ! InternalStateActor.UpdateHcdState(
      Map(
        "standbyPollingRateHz" -> standbyRate,
        "actionPollingRateHz" -> actionRate,
        "currentPollingRateHz" -> standbyRate
      ),
      ctx.system.ignoreRef
    )

    // Phase 3b: Create CommandHandlerActor
    commandHandlerActor = ctx.spawn(
      CommandHandlerActor.behavior(
        controllerCommandActor,
        internalStateActor,
        commandResponseManager,
        loggerFactory,
        statusMonitor
      ),
      "CommandHandlerActor"
    )
    log.info("CommandHandlerActor created")

    // Phase 3d: Start HMI server immediately after actors are ready — before
    // Phase 4 (#Init, #SetupX) so initialization MG output appears in the HMI.
    // HmiLogAppender.broadcast is wired here; any log.info() from this point
    // (including [GALIL:prefix] console lines) streams to connected browsers.
    // HTTP binding is non-blocking so this adds negligible latency to init.
    try {
      val hmiPort = if (hcdConfig.simulate) 9090 else 9090 + hcdConfig.controller.id
      hmiServer = new hmi.HmiServer(
        internalStateActor  = internalStateActor,
        commandHandlerActor = commandHandlerActor,
        hcdPrefix           = componentInfo.prefix,
        log                 = log,
        port                = hmiPort
      )(ctx.system)
      hmiServer.start()
      log.info(s"HMI test console starting on port $hmiPort")
    } catch {
      case ex: Exception =>
        log.warn(s"HMI server failed to start (non-fatal): ${ex.getMessage}")
    }

    // Phase 3c: Initialize InternalState with per-axis config values
    // This ensures thresholds, mechanism types, etc. are set before any commands arrive
    for ((axisChar, axisConfig) <- hcdConfig.axes) {
      val axis = Axis.fromChar(axisChar.head)
      internalStateActor ! InternalStateActor.UpdateAxisState(axis,
        Map(
          "inPositionThreshold" -> axisConfig.inPositionThreshold,
          "mechanismType" -> (axisConfig.mechanismType match {
            case "rotating" => MechanismType.Rotating
            case "linear"   => MechanismType.Linear
            case _          => MechanismType.Linear
          }),
          "algorithm" -> (axisConfig.algorithm match {
            case "shortest" => RotatingAlgorithm.Shortest
            case "forward"  => RotatingAlgorithm.Forward
            case "reverse"  => RotatingAlgorithm.Reverse
            case _          => RotatingAlgorithm.Forward
          }),
          "upperLimit"  -> axisConfig.upperLimit,
          "lowerLimit"  -> axisConfig.lowerLimit,
          // Seed IS with motion parameters from config.
          // These are authoritative — writeMotionConfig() pushes them to the
          // controller's embedded variables after #SetupX, making the config
          // file the single source of truth. Assembly overrides via configAxis.
          "maxSpeed"    -> axisConfig.maxSpeed,
          "acceleration"-> axisConfig.acceleration,
          "deceleration"-> axisConfig.deceleration,
          "motionDelay" -> axisConfig.motionDelay,
          "indexSpeed"  -> axisConfig.indexSpeed,
          // countsPerRevolution is config-file-authoritative for rotating axes.
          // writeMotionConfig() pushes this to cpr[] on the controller,
          // supplanting whatever #SetupX initialised. If 0.0 on a rotating
          // axis, writeMotionConfig() will log a warning and skip it.
          "countsPerRevolution" -> axisConfig.countsPerRevolution,
          "axisName" -> axisConfig.axisName.getOrElse("")
        ),
        ctx.system.ignoreRef
      )
      log.info(s"Axis $axisChar config applied: threshold=${axisConfig.inPositionThreshold}, " +
        s"type=${axisConfig.mechanismType}, algorithm=${axisConfig.algorithm}, " +
        s"speed=${axisConfig.maxSpeed}, accel=${axisConfig.acceleration}")
    }
    // Set the activeAxes flags and simulation mode on HcdState so the HMI knows which axes are configured
    internalStateActor ! InternalStateActor.UpdateHcdState(
      Map(
        "activeAxes" -> hcdConfig.activeAxes.toArray,
        "simulation" -> hcdConfig.simulate
      ),
      ctx.system.ignoreRef
    )

    // Phase 4: Embedded program verification + controller initialization
    //
    // Program verification only runs against real hardware — it compares
    // the controller's stored DMC program to the resource file. The simulator
    // has no real embedded program to verify.
    //
    // Controller init (XQ #Init), axis setup (XQ #SetupX), and motion config
    // write (speed[], accel[], cpr[], etc.) run in both modes — the simulator
    // handles these commands just like real hardware.
    val initFuture = for {
      _ <- if (!hcdConfig.simulate) {
        for {
          _ <- verifyEmbeddedProgram()
          // Brief pause to allow QR polling to produce at least one threadStatus
          // update before we start monitoring threads for completion.
          _ = Thread.sleep(1200) // > 1 standby poll cycle (1Hz)
        } yield ()
      } else {
        log.info("Simulation mode — skipping embedded program verification")
        Future.successful(())
      }
      _ <- initController()
      // Suspend QR polling during axis setup. BZ (Brushless Zero) commutation
      // pauses all controller communication per the Galil manual, causing QR
      // polls on the status connection to time out or return corrupt data.
      // Polling is re-enabled after all setup programs complete.
      _ = { statusMonitor ! ControllerStatusActor.SetPolling(enabled = false)
            log.info("QR polling suspended for axis setup (BZ commutation)") }
      _ <- setupAxes()
      _ = { statusMonitor ! ControllerStatusActor.SetPolling(enabled = true)
            log.info("QR polling resumed after axis setup") }
      // Write motion config AFTER setupAxes — #SetupX establishes motor type
      // and hardware config; we then overwrite the motion parameters with the
      // authoritative values from the HCD config file. This applies to both
      // hardware (supplanting EEPROM defaults) and simulator (initialising
      // what would otherwise be unset embedded variables).
      _ <- writeMotionConfig()
      _ = log.info(s"Galil HCD initialized successfully (simulate=${hcdConfig.simulate})")
    } yield ()
    
    try {
      Await.result(initFuture, 120.seconds)
    } catch {
      case ex: Exception =>
        log.error("Initialization failed", ex = ex)
        throw ex
    }
    
  }

  // ========================================
  // Phase 4: Embedded Program Verification
  // ========================================
  
  /**
   * Comparison result for embedded program verification
   */
  sealed trait ComparisonResult
  case object ProgramMatch extends ComparisonResult
  case class ProgramMismatch(differences: String) extends ComparisonResult
  
  /**
   * Verify controller has expected embedded program
   * 
   * Downloads current program from controller and compares to expected.
   * Logs results but does NOT automatically upload.
   */
  private def verifyEmbeddedProgram(): Future[Unit] = {
    val programName = hcdConfig.controller.embeddedProgram
    log.info(s"Verifying embedded program: $programName")
    
    for {
      // Step 1: Load expected program from resources
      expectedProgram <- loadEmbeddedProgram()
      
      // Step 2: Download current program from controller
      actualProgram <- downloadProgramFromController()
      
      // Step 3: Compare (ignoring whitespace)
      comparisonResult = comparePrograms(expectedProgram, actualProgram)
      
      // Step 4: Log results
      _ = logComparisonResult(comparisonResult)
      
    } yield ()
  }
  
  /**
   * Load embedded DMC program from resources
   */
  private def loadEmbeddedProgram(): Future[String] = {
    val programPath = hcdConfig.controller.embeddedProgram
    log.info(s"Loading embedded program from programs/$programPath")
    
    Future {
      val resourcePath = s"programs/$programPath"
      val stream = getClass.getClassLoader.getResourceAsStream(resourcePath)
      if (stream == null) {
        throw new RuntimeException(s"Embedded program not found: $resourcePath")
      }
      val source = scala.io.Source.fromInputStream(stream)
      try {
        val content = source.mkString
        log.info(s"Loaded embedded program: ${content.length} bytes")
        content
      } finally {
        source.close()
      }
    }
  }
  
  /**
   * Download current program from controller via ControllerCommandActor.
   * Uses the ask pattern to block until the download completes.
   * QR polling continues uninterrupted on its dedicated status connection.
   */
  private def downloadProgramFromController(): Future[String] = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    
    implicit val timeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(10.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
    
    log.info("Downloading current program from controller")
    
    val resultFuture = controllerCommandActor.ask[GalilCommandMessage.DownloadProgramResult](
      ref => GalilCommandMessage.DownloadProgram(ref)
    )
    val result = Await.result(resultFuture, 10.seconds)
    
    result.error match {
      case Some(errMsg) =>
        log.error(s"Download failed: $errMsg")
        Future.failed(new RuntimeException(s"Download failed: $errMsg"))
      case None =>
        log.info(s"Downloaded program: ${result.program.length} characters")
        Future.successful(result.program)
    }
  }
  
  /**
   * Compare two DMC programs, ignoring whitespace differences
   * 
   * @param expected Program from resources
   * @param actual Program from controller
   * @return ProgramMatch or ProgramMismatch with details
   */
  private def comparePrograms(expected: String, actual: String): ComparisonResult = {
    // Normalize both programs for comparison
    val expectedNormalized = normalizeProgram(expected)
    val actualNormalized = normalizeProgram(actual)
    
    if (expectedNormalized == actualNormalized) {
      log.info("Programs match (ignoring whitespace)")
      ProgramMatch
    } else {
      // Find differences
      val diff = findDifferences(expectedNormalized, actualNormalized)
      log.warn("Programs differ")
      ProgramMismatch(diff)
    }
  }
  
  /**
   * Normalize DMC program for comparison.
   * 
   * The Galil controller auto-compresses uploaded code by stripping:
   * - Inline comments (everything after ' on a line)
   * - REM comment lines
   * - Blank lines
   * - All inter-token whitespace (spaces around operators, after keywords, etc.)
   *
   * The controller removes whitespace aggressively across the whole line,
   * including inside MG string literals (a trailing tab/space in a string
   * becomes a single space). We model this with a simple collapse: strip all
   * whitespace from each line. This matches `diff -w` semantics and is correct
   * for functional comparison — the only theoretical false-negative would be
   * a string literal where spaces are load-bearing, which does not occur in
   * this codebase.
   */
  private def normalizeProgram(program: String): String = {
    program
      .split("\r?\n")             // Handle both CR+LF and LF
      .map { line =>
        // Strip inline comments: everything after ' (Galil comment marker)
        val commentIdx = line.indexOf('\'')
        if (commentIdx >= 0) line.substring(0, commentIdx) else line
      }
      .filterNot { line => val t = line.trim; t.nonEmpty && t.startsWith("REM") }  // Remove REM comment lines
      .map(_.replaceAll("\\s+", ""))  // Strip all whitespace — matches controller compression
      .filter(_.nonEmpty)             // Remove blank lines
      .mkString("\n")
  }
  
  /**
   * Produce a unified-style diff between two normalized programs.
   *
   * Shows all lines with markers:
   *   lines only in expected (resource):  "- <line>"
   *   lines only in actual (controller):  "+ <line>"
   *   matching lines (context):           "  <line>"
   *
   * Also writes three files to /tmp for offline review:
   *   embedded_expected.dmc  — normalized resource program
   *   embedded_actual.dmc    — normalized controller program
   *   embedded_diff.txt      — the full diff output
   */
  private def findDifferences(expected: String, actual: String): String = {
    val expectedLines = expected.split("\n").toIndexedSeq
    val actualLines = actual.split("\n").toIndexedSeq

    // Simple LCS-based diff
    val diff = computeDiff(expectedLines, actualLines)

    // Write files for offline review
    try {
      val dir = java.nio.file.Paths.get("/tmp")
      java.nio.file.Files.writeString(dir.resolve("embedded_expected.dmc"), expected)
      java.nio.file.Files.writeString(dir.resolve("embedded_actual.dmc"), actual)
      java.nio.file.Files.writeString(dir.resolve("embedded_diff.txt"), diff)
      log.info("Diff files written to /tmp/embedded_expected.dmc, /tmp/embedded_actual.dmc, /tmp/embedded_diff.txt")
    } catch {
      case e: Exception =>
        log.warn(s"Could not write diff files to /tmp: ${e.getMessage}")
    }

    // Return a summary for the log — first few differences + counts
    val removedCount = diff.linesIterator.count(_.startsWith("- "))
    val addedCount = diff.linesIterator.count(_.startsWith("+ "))
    val summary = new StringBuilder()
    summary.append(s"Line count: expected ${expectedLines.length}, actual ${actualLines.length}\n")
    summary.append(s"Differences: $removedCount lines only in resource, $addedCount lines only in controller\n")
    summary.append("First differences:\n")

    // Show up to 10 diff lines
    var shown = 0
    for (line <- diff.linesIterator if shown < 10) {
      if (line.startsWith("- ") || line.startsWith("+ ")) {
        summary.append(s"  $line\n")
        shown += 1
      }
    }
    if (shown >= 10) summary.append("  ... (see /tmp/embedded_diff.txt for full diff)\n")

    summary.toString()
  }

  /**
   * Compute a unified-style diff between two line sequences using LCS.
   * Returns the full diff text with "- ", "+ ", "  " prefixes.
   */
  private def computeDiff(expected: IndexedSeq[String], actual: IndexedSeq[String]): String = {
    // Build LCS table
    val m = expected.length
    val n = actual.length
    val lcs = Array.ofDim[Int](m + 1, n + 1)
    for (i <- 1 to m; j <- 1 to n) {
      lcs(i)(j) = if (expected(i - 1) == actual(j - 1)) lcs(i - 1)(j - 1) + 1
                  else math.max(lcs(i - 1)(j), lcs(i)(j - 1))
    }

    // Backtrack to produce diff
    val result = scala.collection.mutable.ArrayBuffer[String]()
    var i = m
    var j = n
    while (i > 0 || j > 0) {
      if (i > 0 && j > 0 && expected(i - 1) == actual(j - 1)) {
        result.prepend(s"  ${expected(i - 1)}")
        i -= 1; j -= 1
      } else if (j > 0 && (i == 0 || lcs(i)(j - 1) >= lcs(i - 1)(j))) {
        result.prepend(s"+ ${actual(j - 1)}")
        j -= 1
      } else {
        result.prepend(s"- ${expected(i - 1)}")
        i -= 1
      }
    }

    result.mkString("\n")
  }
  
  /**
   * Log the comparison result
   */
  private def logComparisonResult(result: ComparisonResult): Unit = {
    result match {
      case ProgramMatch =>
        log.info("✓ Embedded program verification PASSED")
        log.info("  Controller has expected program")
        
      case ProgramMismatch(differences) =>
        log.warn("⚠ Embedded program verification FAILED")
        log.warn("  Controller program differs from expected")
        log.warn(s"  $differences")
        log.warn("  Actions:")
        log.warn("    1. Review differences")
        log.warn("    2. If controller is correct: Update resource file")
        log.warn("    3. If resource is correct: Upload to controller manually")
        log.warn(s"    4. Expected program: ${hcdConfig.controller.embeddedProgram}")
    }
  }
  
  // ========================================
  // Phase 4b: Controller Init and Axis Setup
  // ========================================
  
  /**
   * Initialize controller - execute #Init program on thread 0.
   *
   * #Init declares and initializes all embedded variables (arrays, defaults).
   * Thread 0 is the general-purpose thread; threads 1-7 are reserved for
   * per-axis operations. #Init completes quickly (<1s).
   */
  private def initController(): Future[Unit] = {
    import scala.concurrent.duration._

    log.info("Executing #Init")

    val result = sendAndWaitForThread("Init", timeout = 5.seconds)
    result match {
      case scala.util.Success(_) =>
        log.info("#Init completed successfully")
        Future.successful(())
      case scala.util.Failure(e) =>
        log.error(s"#Init failed: ${e.getMessage}")
        Future.failed(e)
    }
  }

  /**
   * Read motion configuration from controller after #Init and store in InternalState.
   *
   * After #Init runs, the embedded variables (speed[], accel[], decel[], hspd[], hoff[], mdelay[])
   * are initialized from the controller's flash EEPROM defaults. Reading them now seeds the IS
   * with the actual working values so the HCD can:
   *   - Calculate realistic timeouts for long-running commands
   *   - Report current configuration to Assemblies (future ICD extension)
   *   - Operate correctly in stand-alone testing before an Assembly calls configAxis
   */
  /**
   * Write motion configuration from HCD config file to the controller's embedded variables.
   *
   * Called after #SetupX runs. The HCD config file is the authoritative source for all
   * motion parameters — this write supplants whatever values the embedded #SetupX programs
   * initialised, making the config file the single source of truth when under HCD control.
   *
   * Three-tier parameter authority:
   *   Tier 1 (embedded EEPROM defaults) — used for standalone Galil Tools testing, no HCD
   *   Tier 2 (HCD config file)          — written here; effective for HCD standalone or with Assembly
   *   Tier 3 (Assembly configAxis)      — runtime override for the current session
   *
   * Embedded variables written per axis:
   *   speed[idx]  ← maxSpeed       (counts/sec)
   *   accel[idx]  ← acceleration   (counts/sec²)
   *   decel[idx]  ← deceleration   (counts/sec²)
   *   hspd[idx]   ← indexSpeed     (counts/sec)
   *   hoff[idx]   ← indexOffset    (encoder counts)
   *   mdelay[idx] ← motionDelay    (ms)
   *   cpr[idx]    ← countsPerRevolution (rotating axes only; skipped with warning if 0.0)
   *
   * IS is already seeded from config in Phase 3c; no IS updates are needed here.
   */
  private def writeMotionConfig(): Future[Unit] = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._

    implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(5.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    log.info("Writing motion configuration from config file to controller")

    val axisNames = Seq("A", "B", "C", "D", "E", "F", "G", "H")
    val activeAxes = axisNames.zip(hcdConfig.activeAxes).filter(_._2).map(_._1)

    activeAxes.foreach { axisName =>
      val axis = Axis.fromChar(axisName.head)
      val idx = axis.index

      hcdConfig.axes.get(axisName) match {
        case None =>
          log.warn(s"Axis $axisName active but no config entry found — skipping motion config write")

        case Some(axisConfig) =>
          // Build compound command for all motion parameters
          val commands = scala.collection.mutable.ListBuffer[String](
            s"speed[$idx]=${axisConfig.maxSpeed}",
            s"accel[$idx]=${axisConfig.acceleration}",
            s"decel[$idx]=${axisConfig.deceleration}",
            s"hspd[$idx]=${axisConfig.indexSpeed}",
            s"hoff[$idx]=${axisConfig.indexOffset}",
            s"mdelay[$idx]=${axisConfig.motionDelay}"
          )

          // cpr is rotating-axis-only; warn if missing for a rotating axis
          if (axisConfig.mechanismType == "rotating") {
            if (axisConfig.countsPerRevolution > 0.0) {
              commands += s"cpr[$idx]=${axisConfig.countsPerRevolution}"
            } else {
              log.warn(s"Axis $axisName is rotating but countsPerRevolution=0.0 in config — cpr[] not written")
            }
          }

          // Chunk to respect the controller's 80-char per-line buffer.
          // The full 7-param compound exceeds 80 chars even with modest values
          // (e.g. ~102 chars with default values), so chunking is required.
          val chunks = csw.proto.galil.io.GalilIo.chunkCompound(commands.toSeq)
          log.debug(s"Axis $axisName motion config write: ${chunks.size} chunk(s): ${chunks.mkString(" | ")}")

          var anyFailed = false
          val it = chunks.iterator
          while (it.hasNext && !anyFailed) {
            val cmdString = it.next()
            try {
              val future = controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
                ref => GalilCommandMessage.SendCommand(cmdString, ref)
              )
              val result = Await.result(future, 2.seconds)
              result.error match {
                case Some(err) =>
                  log.error(s"Axis $axisName motion config write failed on chunk '$cmdString': $err")
                  anyFailed = true
                case None =>
                  log.debug(s"Axis $axisName chunk OK: $cmdString")
              }
            } catch {
              case ex: Exception =>
                log.error(s"Axis $axisName motion config write exception on chunk '$cmdString': ${ex.getMessage}")
                anyFailed = true
            }
          }
          if (!anyFailed) {
            log.info(s"Axis $axisName motion config written: " +
              s"speed=${axisConfig.maxSpeed}, accel=${axisConfig.acceleration}, " +
              s"decel=${axisConfig.deceleration}, cpr=${axisConfig.countsPerRevolution} " +
              s"(${chunks.size} chunk(s))")
          }
      }
    }

    Future.successful(())
  }

  /**
   * Set up all active axes on the controller by running the embedded #Setup program.
   *
   * #Setup (thread 0) launches #SetupA–G on threads 1–7 with WT 2 spacing.
   * Brushless servo axes run BZ (Brushless Zero) commutation which, per the Galil
   * manual, pauses all controller communication until complete. This means the
   * firmware serializes BZ across axes regardless of thread count — #SetupB cannot
   * start until #SetupA's BZ finishes.
   *
   * Thread 0 therefore stays active for almost the entire setup duration (it is
   * blocked on each XQ call while BZ runs on the previous axis). We know setup is
   * complete when ALL threads (0–7) are inactive per MG _NO.
   *
   * We poll MG _NO on the command connection rather than reading IS threadStatus,
   * because QR polling on the status connection is suspended during setup (BZ
   * pauses that connection too). A read timeout on MG _NO means BZ is in progress
   * on that axis — we treat it as "still busy" and keep waiting.
   */
  private def setupAxes(): Future[Unit] = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._

    // During setup the MG _NO poll blocks on the socket for up to 60s (the BZ read timeout).
    // The Pekko ask timeout must exceed the socket read timeout or the ask fails while the
    // actor is still blocked on the read. 75s covers 60s socket timeout + scheduling margin.
    // This implicit governs all ask() calls in this method; Await.result durations are separate.
    implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(75.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    val axisNames = Seq("A", "B", "C", "D", "E", "F", "G", "H")
    val activeAxes = axisNames.zip(hcdConfig.activeAxes).filter(_._2).map(_._1)
    log.info(s"Active axes: ${activeAxes.mkString(", ")}")

    // 1. Motors off before setup — required by many embedded setup commands.
    activeAxes.foreach { axisName =>
      val moCmd = s"MO$axisName"
      log.info(s"Motor off before setup: $moCmd")
      val moResult = Await.result(
        controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
          ref => GalilCommandMessage.SendCommand(moCmd, ref)),
        5.seconds)
      moResult.error.foreach(err => throw new RuntimeException(s"$moCmd failed: $err"))
    }

    // 2. Launch #Setup on thread 0. This spawns all #SetupX programs and returns
    //    its ack promptly (before any BZ starts). We don't use sendAndWaitForThread
    //    here because completion is detected differently — see step 3.
    log.info("Running #Setup")
    val xqResult = Await.result(
      controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
        ref => GalilCommandMessage.SendCommand("XQ #Setup,0", ref)),
      5.seconds)
    xqResult.error.foreach(err => throw new RuntimeException(s"XQ #Setup,0 failed: $err"))
    log.info("XQ #Setup,0 launched — waiting for all threads to complete")

    // 3. Poll MG _NO until all threads (0–7) are inactive (_NO = 0).
    //    Thread 0 stays active until it has spawned all #SetupX programs.
    //    Threads 1–7 go inactive as each axis completes.
    //
    //    BZ (Brushless Zero) pauses all controller communication per the Galil manual.
    //    To avoid desynchronizing the socket with stale pending commands, we set the
    //    command connection read timeout to 0 (infinite) for the duration of the wait.
    //    Each MG _NO call will simply block through any BZ pause and return when the
    //    controller responds. We restore the normal 3s timeout when done.
    //
    //    Overall timeout is enforced by the deadline — 120s covers 4 BZ axes at ~10s each.
    def setReadTimeout(ms: Int): Unit =
      Await.result(
        controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
          ref => GalilCommandMessage.SetReadTimeout(ms, ref)),
        5.seconds)

    // Use a large explicit timeout rather than 0 (infinite) — some JVM/OS combinations
    // treat setSoTimeout(0) inconsistently. 60s comfortably covers the longest BZ pause.
    setReadTimeout(60 * 1000)
    log.info("Command connection read timeout set to 60s for setup")

    try {
      val setupDeadline = 120.seconds.fromNow
      var allDone = false
      while setupDeadline.hasTimeLeft() && !allDone do
        Thread.sleep(500)
        val noResult = Await.result(
          controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
            ref => GalilCommandMessage.SendCommand("MG _NO", ref)),
          130.seconds)  // must exceed setupDeadline; actual blocking is on the socket
        noResult.error match {
          case Some(err) =>
            log.warn(s"MG _NO error during setup: $err")
          case None =>
            val noValue = noResult.response.trim.toDoubleOption.map(_.toInt).getOrElse(-1)
            log.debug(s"MG _NO = 0x${noValue.toHexString}")
            if noValue == 0 then
              log.info("All setup threads completed (_NO=0)")
              allDone = true
        }

      if !allDone then
        throw new RuntimeException("Setup timed out waiting for all threads to complete")
    } finally {
      setReadTimeout(3000)  // restore normal operating timeout
      log.info("Command connection read timeout restored to 3000ms")
    }

    // 4. Apply per-axis config now that hardware setup is done.
    activeAxes.foreach { axisName =>
      hcdConfig.axes.get(axisName).foreach { axisConfig =>
        storeMechanismConfig(axisName, axisConfig)
        log.info(s"Axis $axisName setup complete")
      }
    }

    Future.successful(())
  }
  
  /**
   * Store mechanism configuration for use by command handlers
   */
  private def storeMechanismConfig(axisName: String, axisConfig: AxisConfig): Unit = {
    // Store in axisInfo map for use by command handlers
    // This information is used to implement proper positioning behavior
    log.debug(s"Stored mechanism config for $axisName: ${axisConfig.mechanismType}, ${axisConfig.algorithm}")
  }

  // ========================================
  // Init-time program execution helper
  // ========================================

  /**
   * Send a command to the controller and block until the specified thread completes.
   *
   * Used during initialization to execute embedded programs (#Init, #SetupX) and
   * wait for them to finish. This is different from the command-time flow which
   * uses CommandWatcherActor — during init, we're blocking in initialize() and
   * there's no CRM or external caller to notify.
   *
   * The ControllerStatusActor is already running and updating threadStatus in the IS actor
   * from QR polling. We query IS.HcdState.threadStatus to check if the target
   * thread bit has cleared.
   *
   * @param label   Program label without # prefix (e.g. "Init", "Setup")
   * @param timeout Maximum time to wait for thread completion
   * @return Success(()) if thread completed, Failure if timeout or error
   */
  private def sendAndWaitForThread(
    label: String,
    timeout: scala.concurrent.duration.FiniteDuration
  ): scala.util.Try[Unit] = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._

    implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(5.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    scala.util.Try {
      // 1. Send XQ via ExecuteProgram — allocates thread, sends XQ, then queries
      //    MG _NO to confirm the thread started. Returns threadWasActive as
      //    authoritative hardware truth.
      val execFuture = controllerCommandActor.ask[GalilCommandMessage.ExecuteProgramResult](
        ref => GalilCommandMessage.ExecuteProgram(label, ref)
      )
      val execResult = Await.result(execFuture, 5.seconds)

      execResult.error.foreach { err =>
        throw new RuntimeException(s"ExecuteProgram '#$label' failed: $err")
      }

      val allocatedThread = execResult.thread

      if !execResult.threadWasActive then
        // The post-XQ _NO query did not show the thread active. Either:
        //   (a) XQ was rejected silently (no ? but program didn't start), or
        //   (b) the program was so fast it completed before the _NO query.
        // We cannot distinguish these cases from _NO alone. Fall through to
        // the Faulted check below — if the program errored, the status actor
        // will have already set Faulted+controllerErrorMsg from the QR errorCode.
        log.info(s"Thread $allocatedThread completed (not observed active after XQ — " +
          s"program finished before _NO query, or was a no-op)")
      else
        // Thread confirmed active — poll IS until it clears
        val deadline     = timeout.fromNow
        val pollInterval = 100 // ms
        var completed    = false

        while deadline.hasTimeLeft() && !completed do
          Thread.sleep(pollInterval)

          val hcdStateFuture = internalStateActor.ask[HcdState](
            ref => InternalStateActor.GetHcdState(ref)
          )
          val hcdState    = Await.result(hcdStateFuture, 2.seconds)
          val threadActive = (hcdState.threadStatus & (1 << allocatedThread)) != 0

          if !threadActive then
            log.info(s"Thread $allocatedThread completed (was active, now released)")
            completed = true
          else
            log.debug(s"Thread $allocatedThread active (threadStatus=0x${hcdState.threadStatus.toHexString})")

        if !completed then
          throw new RuntimeException(
            s"Thread $allocatedThread timed out after $timeout waiting for '#$label' to complete")

      // Whether the thread was observed active or completed instantly, query TC 1
      // on the command connection to check for a controller error from this execution.
      // TC 1 returns the most recent error code and description, then clears the latch.
      //
      // We cannot rely on QR errorCode detection here because:
      //   - QR is at 1Hz standby rate, so it may not fire before the caller suspends
      //     polling (as happens immediately after #Init, before setupAxes).
      //   - The controller error latch is shared across all programs; reading it here
      //     while the thread just finished is the most accurate attribution.
      //
      // If TC 1 returns a nonzero code, we also set Faulted in IS so the error is
      // visible in HCD state and the HMI, consistent with errors detected via QR.
      val tcFuture = controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
        ref => GalilCommandMessage.SendCommand("TC 1", ref)
      )
      val tcResult = Await.result(tcFuture, 5.seconds)

      tcResult.error match {
        case Some(err) =>
          // TC itself failed (unlikely — means the command connection is broken)
          log.warn(s"TC 1 query after '#$label' failed: $err")
        case None =>
          val tcText = tcResult.response.trim
          // TC 1 returns "N Description" where N>0 means error, or " 0" if no error.
          // Extract the leading numeric code to determine whether an error occurred.
          val errorCode = tcText.takeWhile(c => c.isDigit || c == ' ').trim.takeWhile(_.isDigit)
          val isError   = errorCode.nonEmpty && errorCode != "0"
          if isError then
            val errorMsg = s"Controller Error: $tcText"
            log.error(s"'#$label' failed: $errorMsg")
            // Set Faulted in IS so the error is visible in HCD state and HMI
            internalStateActor ! InternalStateActor.UpdateHcdState(
              Map(
                "state"              -> HcdStateEnum.Faulted,
                "controllerErrorMsg" -> errorMsg
              ),
              ctx.system.ignoreRef
            )
            throw new RuntimeException(s"'#$label' failed: $errorMsg")
      }
    }
  }

  override def onShutdown(): Unit = {
    log.info("Shutting down Galil HCD")
    
    // Stop HMI server first (clients will see disconnect)
    if (hmiServer != null) hmiServer.stop()
    
    // Stop actors gracefully (null checks for case where initialize failed partway)
    if (statusMonitor != null) statusMonitor ! ControllerStatusActor.SetPolling(enabled = false)
    // Explicitly stop the console actor so its TCP handle is released immediately.
    // Without this, the actor's blocking read thread runs until socket timeout
    // (~3s) before PostStop fires — leaving the controller handle open in the
    // interim. consoleActor is only created in hardware mode.
    if (consoleActor != null) consoleActor ! ControllerConsoleActor.Stop
    currentStatePublisher ! CurrentStatePublisherActor.Shutdown
    
    log.info("Galil HCD shut down")
  }

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  // ========================================
  // Command Validation and Execution
  // ========================================
  
  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    log.debug(s"validateSubmit called: $controlCommand")
    controlCommand match {
      case setup: Setup =>
        val commandName = setup.commandName.name

        // Gate: if HCD is Faulted, only faultReset is permitted.
        // This covers both controller errors and connection loss — any Faulted
        // transition blocks commands until the operator explicitly clears the fault.
        if commandName != "faultReset" then
          val hcdState = queryHcdStateSync()
          if hcdState.state == HcdStateEnum.Faulted then
            val reason = if hcdState.controllerErrorMsg.nonEmpty then hcdState.controllerErrorMsg
                         else "HCD is Faulted"
            log.warn(s"Command '$commandName' rejected: $reason")
            return CommandResponse.Invalid(runId, CommandIssue.OtherIssue(s"HCD Faulted: $reason"))

        // Immediate commands handled by CommandHandlerActor use ICD keys directly
        if (CommandHandlerActor.isImmediate(commandName)) {
          validateImmediateCommand(runId, setup)
        } else if (CommandHandlerActor.isLongRunning(commandName)) {
          validateLongRunningCommand(runId, setup)
        } else {
          CommandResponse.Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"Unknown command: $commandName"))
        }
      case _: Observe =>
        CommandResponse.Invalid(runId, CommandIssue.UnsupportedCommandIssue("Observe not supported"))
    }
  }

  /**
   * Synchronously query HcdState from InternalStateActor.
   * Used in validateCommand which runs on the CSW framework thread.
   * Fails closed — on query failure returns a Faulted state so commands
   * are blocked rather than silently allowed through.
   */
  private def queryHcdStateSync(): HcdState = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(2.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
    try
      Await.result(
        internalStateActor.ask[HcdState](ref => InternalStateActor.GetHcdState(ref)),
        2.seconds
      )
    catch
      case ex: Exception =>
        log.warn(s"queryHcdStateSync failed: ${ex.getMessage} — treating as Faulted")
        HcdState(state = HcdStateEnum.Faulted,
                 controllerErrorMsg = "HCD state query failed — connection may be lost")
  }

  /**
   * Validate immediate commands using ICD key definitions.
   * Checks required parameters are present and axis values are valid.
   */
  private def validateImmediateCommand(runId: Id, setup: Setup): ValidateCommandResponse = {
    import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

    try {
      setup.commandName.name match {
        case "configAxis" =>
          // axis is required; all others are optional
          setup(ConfigAxisCommand.axisKey)
          CommandResponse.Accepted(runId)
          
        case "configRotatingAxis" =>
          // axis and algorithm are required
          setup(ConfigRotatingAxisCommand.axisKey)
          setup(ConfigRotatingAxisCommand.algorithmKey)
          CommandResponse.Accepted(runId)
          
        case "configLinearAxis" =>
          // axis, upperLimit, lowerLimit are all required
          setup(ConfigLinearAxisCommand.axisKey)
          setup(ConfigLinearAxisCommand.upperLimitKey)
          setup(ConfigLinearAxisCommand.lowerLimitKey)
          CommandResponse.Accepted(runId)
          
        case "setBit" =>
          // address and value are required
          setup(SetBitCommand.addressKey)
          val value = setup(SetBitCommand.valueKey).head
          if (value != 0 && value != 1) {
            CommandResponse.Invalid(runId, CommandIssue.ParameterValueOutOfRangeIssue("setBit value must be 0 or 1"))
          } else {
            CommandResponse.Accepted(runId)
          }
          
        case "setAO" =>
          // address and value are required
          setup(SetAOCommand.addressKey)
          setup(SetAOCommand.valueKey)
          CommandResponse.Accepted(runId)

        case "faultReset" =>
          // severity is optional — defaults to None if absent
          CommandResponse.Accepted(runId)
          
        case other =>
          CommandResponse.Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"Unknown immediate command: $other"))
      }
    } catch {
      case _: NoSuchElementException =>
        CommandResponse.Invalid(runId, CommandIssue.MissingKeyIssue("Required parameter missing"))
    }
  }

  /**
   * Validate long-running commands using ICD key definitions.
   * Checks required parameters are present for motion commands,
   * then validates the command is permitted in the current axis state (SDD Figure 4-2).
   */
  private def validateLongRunningCommand(runId: Id, setup: Setup): ValidateCommandResponse = {
    import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

    try {
      // Phase 1: Parameter validation (required keys present)
      val axisChoice = setup.commandName.name match {
        case "positionAxis" =>
          setup(PositionAxisCommand.axisKey).head
          setup(PositionAxisCommand.targetKey)
          setup(PositionAxisCommand.axisKey).head
          
        case "homeAxis" =>
          setup(HomeAxisCommand.axisKey).head
          
        case "stopAxis" =>
          setup(StopAxisCommand.axisKey).head
          
        case "offsetAxis" =>
          setup(OffsetAxisCommand.axisKey).head
          setup(OffsetAxisCommand.distanceKey)
          setup(OffsetAxisCommand.axisKey).head
          
        case "selectWheel" =>
          setup(SelectWheelCommand.axisKey).head
          setup(SelectWheelCommand.positionKey)
          setup(SelectWheelCommand.axisKey).head
          
        case "positionWheel" =>
          setup(PositionWheelCommand.axisKey).head
          setup(PositionWheelCommand.positionKey)
          setup(PositionWheelCommand.axisKey).head
          
        case "trackAxis" =>
          setup(TrackAxisCommand.axisKey).head
          setup(TrackAxisCommand.target1Key)
          // target2 is optional per ICD
          setup(TrackAxisCommand.axisKey).head
          
        case other =>
          return CommandResponse.Invalid(runId,
            CommandIssue.UnsupportedCommandIssue(s"Unknown long-running command: $other"))
      }

      // Phase 2: Axis state machine validation (SDD Figure 4-2)
      val commandName = setup.commandName.name
      val axis = Axis.fromChar(axisChoice.name.head)
      
      validateAxisState(runId, axis, commandName)
      
    } catch {
      case _: NoSuchElementException =>
        CommandResponse.Invalid(runId, CommandIssue.MissingKeyIssue("Required parameter missing"))
    }
  }

  /**
   * Validate that the given command is permitted in the axis's current state.
   * Queries InternalState for the axis's axisState and checks against Figure 4-2 transitions.
   */
  private def validateAxisState(runId: Id, axis: Axis, commandName: String): ValidateCommandResponse = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._

    implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(2.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    try {
      val future = internalStateActor.ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )
      val maybeState = Await.result(future, askTimeout.duration)

      maybeState match {
        case Some(axisState) =>
          axisState.axisState.validateCommand(commandName) match {
            case None =>
              // Transition is valid
              CommandResponse.Accepted(runId)
            case Some(reason) =>
              log.warn(s"Command rejected: $commandName on axis $axis — $reason")
              CommandResponse.Invalid(runId, CommandIssue.OtherIssue(reason))
          }
        case None =>
          // Axis not initialized in IS — reject (axis should be initialized during HCD init)
          CommandResponse.Invalid(runId,
            CommandIssue.OtherIssue(s"Axis $axis not initialized"))
      }
    } catch {
      case ex: Exception =>
        log.error(s"Failed to query axis state for validation: ${ex.getMessage}")
        // On failure to query state, allow the command through rather than blocking
        // (the CommandHandler will catch errors during execution)
        CommandResponse.Accepted(runId)
    }
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.debug(s"onSubmit called: $controlCommand")
    controlCommand match {
      case setup: Setup =>
        val commandName = setup.commandName.name
        
        // Route immediate and long-running commands to CommandHandlerActor
        if (CommandHandlerActor.isImmediate(commandName) || CommandHandlerActor.isLongRunning(commandName)) {
          commandHandlerActor ! CommandHandlerActor.HandleCommand(setup, runId, setup.maybeObsId)
          CommandResponse.Started(runId)
        } else {
          CommandResponse.Error(runId, s"Unknown command: $commandName")
        }
      case x =>
        // Should not happen after validation
        CommandResponse.Error(runId, s"Unexpected submit: $x")
    }
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {
    log.debug(s"onOneway called: $controlCommand")
    // All commands are dispatched via onSubmit; oneway is not used in this HCD
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}

object GalilHcdApp {
  def main(args: Array[String]): Unit = {
    val defaultConfig = ConfigFactory.load("GalilHcd.conf")
    ContainerCmd.start("ICS.HCD.GalilMotion", Subsystem.APS, args, Some(defaultConfig))
  }
}