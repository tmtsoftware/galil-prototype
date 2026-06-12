package csw.proto.galil.hcd

import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import com.typesafe.config.{Config, ConfigFactory}
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.params.commands.CommandResponse.{Completed, Error, Started, SubmitResponse, ValidateCommandResponse}
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
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.Try

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

  // Synchronous command execution for CommandHandlerActor; uses the dedicated command connection.
  case class SendCommand(commandString: String, replyTo: ActorRef[SendCommandResult]) extends GalilCommandMessage
  case class SendCommandResult(response: String, error: Option[String] = None) extends GalilCommandMessage

  /** Set the socket read timeout on the command connection. 0 = infinite (block until response).
   *  Used during axis setup to survive BZ commutation pauses without desynchronizing the socket. */
  case class SetReadTimeout(timeoutMs: Int, replyTo: ActorRef[SendCommandResult]) extends GalilCommandMessage



  /**
   * Execute an embedded program with automatic thread allocation.
   *
   * The CI actor queries MG _NO to find a free thread (1-7), optionally sends
   * preCommands as a single compound command, then sends "XQ #label,thread;MG _XQ<thread>"
   * as a single line buffer. The MG _XQ<thread> reads the line currently
   * being executed by thread N, or -1 if the thread has already stopped.
   *
   * Note on host-channel timing: the "all commands on a line execute before the
   * scheduler switches" rule applies to embedded code, not to host TCP commands.
   * Between the XQ and MG on the host parser line, the scheduler may run thread N.
   * Therefore _XQ<N>=-1 in the compound's MG response means thread N has run
   * and finished (e.g. a short program like #StopX that completes in microseconds),
   * not that it never ran. A non-(-1) line number means thread N is still mid-execution.
   *
   * preCommands (if present) is sent atomically within the same galilIo.synchronized
   * block as the XQ; eliminating a separate CI round-trip for callers that need to
   * set embedded variables (e.g. dmd[idx]=target) immediately before launching a program.
   * If the preCommands send fails, ExecuteProgram returns an error and XQ is not sent.
   *
   * Thread allocation uses the controller's MG _NO bitmask as the source of truth , 
   * no separate pool bookkeeping. (Allocation looks for *clear* bits, where _NO is
   * reliable; per-thread state queries during execution use _XQ<n> instead, which is
   * unaffected by the post-CMDERR _NO unreliability documented in ControllerStatusActor.)
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
   * @param threadWasActive  true if MG _XQ<thread> in the compound returned a non-(-1)
   *                         line number (thread N is mid-execution); false if -1
   *                         (thread N has already completed)
   * @param error            None on success, Some(message) if XQ was rejected or no threads available
   */
  case class ExecuteProgramResult(
    thread: Int,
    threadWasActive: Boolean,
    error: Option[String] = None
  )

  /**
   * Halt an active execution thread (SDD 4.8.1; Halting the Active Command).
   *
   * The CI actor confirms the thread is still active via MG _NO and sends HX to halt
   * it if so. All operations are synchronized under galilIo.synchronized to prevent
   * interleaving with QR polling.
   *
   * This message only kills the thread; it does NOT send ST. The caller is responsible
   * for any subsequent motor stop (ST) or embedded stop program (#StopX) as appropriate:
   *   - checkAndInterrupt: sends ST after HaltExecution, then starts a new embedded program
   *   - stopAxis: does not need ST; #StopX handles motor deceleration
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
   *   - If that succeeds the connection never actually dropped; report Connected, done.
   * Step 2: if the test fails, close the dead socket and open a fresh GalilIoTcp.
   *   - Retest with MG 0. Report Connected on success, Disconnected on failure.
   *
   * Reports commandConnection Connected/Disconnected to IS in either outcome.
   * Used by faultReset (None severity) to recover from a detected connection loss.
   */
  case class Reconnect(replyTo: ActorRef[ReconnectResult]) extends GalilCommandMessage

  // ── Fault-recovery primitives ───────────────────────────
  //
  // These three messages back the deeper faultReset severities (Reset and
  // Reload).  They run on the command connection because the simulator and
  // hardware both implement DL/UL/BP/RS as command-connection operations.

  /**
   * Upload an embedded program to the controller (DL command).  Replaces any
   * existing program in the controller's volatile memory.  The program stays
   * volatile until burnt to EEPROM with BurnProgram (BP).
   *
   * @param program the full program text (newline-separated lines, no DL prefix)
   */
  case class UploadProgram(program: String, replyTo: ActorRef[UploadProgramResult]) extends GalilCommandMessage
  case class UploadProgramResult(success: Boolean, error: Option[String] = None)

  /**
   * Burn the currently-loaded volatile program to EEPROM (BP command).
   * Typically takes 2-3 seconds on real hardware; pauses the controller
   * while writing.  The command-connection read timeout is temporarily
   * extended to 5s to cover the burn, then restored to 3s.
   */
  case class BurnProgram(replyTo: ActorRef[BurnProgramResult]) extends GalilCommandMessage
  case class BurnProgramResult(success: Boolean, error: Option[String] = None)

  /**
   * Send the controller-reset command (RS).  RS terminates all open TCP
   * sessions on the controller as part of the reset, so this message does
   * NOT wait for a reply (none is coming); it just writes the bytes and
   * proactively reports the command connection as Disconnected to IS.  The
   * caller is responsible for waiting for the controller to come back and
   * re-opening sockets via Reconnect on the affected actors.
   *
   * On the simulator this is implemented as a controlled connection close
   * so the same caller-side recovery code path is exercised in tests.
   */
  case class SendReset(replyTo: ActorRef[SendResetResult]) extends GalilCommandMessage
  case class SendResetResult(success: Boolean, error: Option[String] = None)

}

/**
 * JVM-shutdown driver: ensures actor PostStop work completes on signal-based JVM
 * exit (SIGINT, SIGTERM) before the JVM tears down, so the controller is safed
 * (ST;MO) and the command socket is closed cleanly.
 *
 * On an HMI Shutdown the supervisor's onShutdown path runs synchronously while the
 * JVM is healthy, so cleanup always completes. On a signal exit, Pekko's
 * CoordinatedShutdown runs from its own JVM hook but races the JVM's exit: the JVM
 * can tear down threads and sockets before PostStop runs, leaving motors energised.
 *
 * This driver registers an additional JVM shutdown hook that:
 *   1. Best-effort resolves the supervisor and sends Shutdown (usually a no-op on
 *      signal exit, since Pekko has already deregistered the component; useful on
 *      any path where Pekko's hook has not fired).
 *   2. Blocks on `system.whenTerminated` (capped at 15s). This is the load-bearing
 *      step: waiting on the actor system's termination Future holds the JVM open
 *      until all CoordinatedShutdown phases complete, so PostStop runs, ST;MO is
 *      sent and acknowledged, and the command socket closes before the JVM exits.
 *
 * The 15s cap prevents a wedged controller or stuck shutdown phase from blocking
 * JVM exit indefinitely; the full sequence completes in roughly 50ms on the
 * simulator and 1.5s on hardware. On timeout the driver announces on stderr and
 * lets the JVM proceed (the OS reclaims sockets; motors may not be safed).
 *
 * Logging caveat: by the time this hook runs, the CSW/SLF4J logging actors may be
 * mid-teardown, so some expected log lines (notably the ControllerCommandActor
 * ST;MO line) may not reach csw.log even though the work happens. The `announce()`
 * helper therefore writes to stderr first and attempts SLF4J only best-effort. To
 * verify ST;MO on a Ctrl-C, check the controller-side or simulator CMD/RSP trace.
 *
 * Registration is one-shot per JVM (guarded by `registered`); Restart re-invokes
 * initialize() but the JVM-level hook persists. This driver is a development-time
 * and defensive safety net, not a replacement for HMI Shutdown or the deployed
 * control system's lifecycle commands.
 */
private object SignalShutdownDriver {
  // JVM-lifetime flag; survives Restart's destruction of GalilHcdHandlers
  // because this object lives at the classloader level, not the actor level.
  private val registered = new java.util.concurrent.atomic.AtomicBoolean(false)

  // Total time we'll let the hook block before giving up and letting the JVM
  // proceed with its exit.  Sized to comfortably cover the observed clean-
  // shutdown duration even on hardware with all 7 axes active.
  private val ShutdownAwaitTimeout = scala.concurrent.duration.Duration(15, scala.concurrent.duration.SECONDS)

  /**
   * Register a JVM shutdown hook on first call; no-op on subsequent calls.
   *
   * The hook closes over `cswCtx` and the `ActorSystem` rather than `ctx`
   * because `ctx` belongs to the TLA actor, which gets destroyed and
   * recreated on Restart; keeping a reference to it would create a stale
   * binding.  The actor system is JVM-scoped and stable across Restart.
   */
  def registerOnce(
    cswCtx: CswContext,
    system: org.apache.pekko.actor.typed.ActorSystem[?]
  ): Unit = {
    if (registered.compareAndSet(false, true)) {
      val log = cswCtx.loggerFactory.getLogger
      val hook = new Thread(() => runShutdown(cswCtx, system), "galil-hcd-signal-shutdown")
      try {
        Runtime.getRuntime.addShutdownHook(hook)
        log.info("Signal shutdown driver registered")
      } catch {
        case ex: IllegalStateException =>
          log.warn(s"Could not register signal shutdown driver: ${ex.getMessage}")
      }
    }
  }

  /**
   * Drive a clean CSW Shutdown from the JVM-shutdown thread.  Runs on its
   * own thread (not on a Pekko dispatcher) so blocking calls are safe.
   *
   * Strategy:
   *   1. Resolve our supervisor by component prefix.
   *   2. If found, send Shutdown.  If not found, the system is probably
   *      already shutting down; just wait for it.
   *   3. Block on `whenTerminated` with a hard timeout so a wedged shutdown
   *      can't hang the JVM forever.
   *
   * stderr is used for the visible status messages because by the time the
   * hook runs the SLF4J logging actors may be mid-teardown; stderr is the
   * one channel guaranteed to reach the operator's terminal.  We also try
   * SLF4J for the structured log file; if it works it works.
   */
  private def runShutdown(
    cswCtx: CswContext,
    system: org.apache.pekko.actor.typed.ActorSystem[?]
  ): Unit = {
    import csw.command.client.messages.{ComponentMessage, SupervisorContainerCommonMessages}
    import csw.location.api.extensions.URIExtension._
    import csw.location.api.models.{ComponentId, Connection}
    import scala.concurrent.{Await, ExecutionContext}
    import scala.concurrent.duration._

    val log    = cswCtx.loggerFactory.getLogger
    val prefix = cswCtx.componentInfo.prefix
    // ActorSystem in implicit scope: required by URIExtension.toActorRef and
    // also serves as the ExecutionContext for any Future operations below.
    given org.apache.pekko.actor.typed.ActorSystem[?] = system
    given ExecutionContext = system.executionContext

    def announce(msg: String): Unit = {
      System.err.println(s"[galil-hcd] $msg")
      try log.warn(msg) catch { case _: Throwable => () }
    }

    announce(s"JVM shutdown — driving clean CSW Shutdown for $prefix")

    try {
      // Step 1: resolve our supervisor.  Short timeout; if location service
      // is unreachable, fall through to the await below.
      val connection = Connection.PekkoConnection(
        ComponentId(prefix, cswCtx.componentInfo.componentType)
      )
      val resolveFuture = cswCtx.locationService.resolve(connection, 3.seconds)
      val maybeSupervisor = Await.result(resolveFuture, 4.seconds)

      maybeSupervisor match {
        case Some(loc) =>
          val supervisor: ActorRef[ComponentMessage] =
            loc.uri.toActorRef.unsafeUpcast[ComponentMessage]
          supervisor ! SupervisorContainerCommonMessages.Shutdown
          announce("Shutdown message sent to supervisor; waiting for termination")
        case None =>
          // Either location service is gone or the supervisor has already
          // unregistered (which happens early in a normal CSW Shutdown).
          // Either way the right thing to do is just wait.
          announce("Supervisor not resolvable — likely already shutting down; waiting for termination")
      }
    } catch {
      case ex: Throwable =>
        announce(s"Resolve/send failed: ${ex.getMessage} — proceeding to await termination anyway")
    }

    // Step 2: block until the actor system is fully done, capped at the
    // hook's overall budget.  whenTerminated is the Future that completes
    // when ALL the coordinated-shutdown phases have run; including our
    // component's PostStop chain, which is what does the ST;MO.
    try {
      Await.result(system.whenTerminated, ShutdownAwaitTimeout)
      announce("Actor system terminated cleanly — JVM exit proceeding")
    } catch {
      case _: java.util.concurrent.TimeoutException =>
        announce(s"Shutdown await exceeded ${ShutdownAwaitTimeout.toSeconds}s — proceeding to JVM exit anyway")
      case ex: Throwable =>
        announce(s"Shutdown await failed: ${ex.getMessage} — proceeding to JVM exit")
    }
  }
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
    // accepts the live Prefix object directly; no HOCON string round-trip; and writes into
    // the same ConcurrentHashMap that LoggerImpl.componentLoggingState reads on every log call.
    //
    // logLevel in application.conf must remain "debug" (Gate 2 open) so that runtime elevation
    // to DEBUG via the HMI is not permanently blocked by LogActor's global level floor.
    //
    // KNOWN CSW LIMITATION (workaround is the LogAdminUtil call below): the static
    // component-log-levels HOCON block cannot reliably target a deeply-nested prefix like
    // "ICS.HCD.GalilMotion.1" because Config.entrySet() key rendering does not round-trip
    // back to the runtime Prefix.  Confirmed NOT a numeric-suffix issue ("1" -> "one"); it
    // is the nesting depth.  The runtime path here (live Prefix, no HOCON round-trip)
    // sidesteps it.  Filing an upstream CSW issue is tracked in the project backlog.
    LogAdminUtil.setComponentLogLevel(componentInfo.prefix, Level.INFO)

    log.info("Initializing Galil HCD")

    // Register a JVM-level shutdown hook that holds the JVM open until
    // Pekko's CoordinatedShutdown phases (including our actor PostStop
    // signals) have fully completed on signal-based exit (SIGINT, SIGTERM).
    // Without this, the JVM tears down threads and sockets out from under
    // the in-progress shutdown and our ST;MO safe-state policy may not run
    // to completion.  See SignalShutdownDriver for the empirical findings
    // and the full picture.  One-shot per JVM (Restart re-runs initialize()
    // but the hook persists).
    SignalShutdownDriver.registerOnce(cswCtx, ctx.system)
    
    // Phase 1: Load controller and axis-specific parameters from CSW Configuration Service
    hcdConfig = loadConfiguration()
    
    // Phase 2: Connect to controller and verify identity
    // Create ControllerCommandActor; opens command TCP connection.
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
    
    // Block until the actor has completed setup (connect + ID); standard CSW init pattern
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
    // Skipped in simulation mode; no physical controller to receive CF I / CW 2.
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
    // not from config; the embedded code sets this during axis Setup.
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

    // Store configured controller id and polling rates in IS. controllerId is
    // pushed here so the published CurrentState reflects the configured value
    // rather than the HcdState default.
    internalStateActor ! InternalStateActor.UpdateHcdState(
      Map(
        "controllerId" -> hcdConfig.controller.id,
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

    // Phase 3d: Start HMI server immediately after actors are ready; before
    // Phase 4 (#Init, #SetupX) so initialization MG output appears in the HMI.
    // HmiLogAppender.broadcast is wired here; any log.info() from this point
    // (including [GALIL:prefix] console lines) streams to connected browsers.
    // HTTP binding is non-blocking so this adds negligible latency to init.
    try {
      // HMI port is a direct function of controller.id (0..N), identical in
      // simulation and on hardware. Ids must be unique per host; the port is the
      // only per-host resource keyed off id. The CSW prefix is independent of it.
      val hmiPort = 9090 + hcdConfig.controller.id
      hmiServer = new hmi.HmiServer(
        internalStateActor      = internalStateActor,
        commandHandlerActor     = commandHandlerActor,
        controllerCommandActor  = controllerCommandActor,
        hcdPrefix               = componentInfo.prefix,
        log                     = log,
        locationService         = locationService,
        componentInfo           = componentInfo,
        port                    = hmiPort,
        // Route HMI-issued faultReset directly to handleFaultReset.  Runs
        // on the class-level execution context so the HMI HTTP handler
        // returns immediately (the recovery is long-running).
        onFaultReset            = (setup, runId) => Future { handleFaultReset(setup, runId) }
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
          // These are authoritative; writeMotionConfig() pushes them to the
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
    // Set the activeAxes flags and simulation mode on HcdState so the HMI knows which axes are configured.
    // Also set initializingReason so the HMI banner says "HCD starting up" while runInitSequence runs.
    internalStateActor ! InternalStateActor.UpdateHcdState(
      Map(
        "activeAxes"         -> hcdConfig.activeAxes.toArray,
        "simulation"         -> hcdConfig.simulate,
        "initializingReason" -> "startup"
      ),
      ctx.system.ignoreRef
    )

    // Phase 4: Embedded program verification + controller initialization.
    //
    // The body of Phase 4 lives in runInitSequence(); it is the sequence
    // we'll re-run during faultReset (severities Init/Reset/Reload).  See
    // that method for the step-by-step rationale.
    try {
      Await.result(runInitSequence(), 120.seconds)

      // Transition HCD state from Uninitialized → Ready.  Until this point,
      // commands are rejected by the universal gate in CommandHandlerActor
      // (and by the HMI and CSW-validate gates that mirror it).  Doing the
      // transition AFTER runInitSequence completes guarantees that we only
      // flip to Ready if every step of init actually succeeded; if any
      // step throws, we fall through to the catch below and come up Faulted.
      internalStateActor ! InternalStateActor.UpdateHcdState(
        Map(
          "state"              -> HcdStateEnum.Ready,
          "initializingReason" -> ""
        ),
        ctx.system.ignoreRef
      )
      log.info(s"Galil HCD initialized successfully (simulate=${hcdConfig.simulate})")
    } catch {
      case ex: Exception =>
        // Init failed.  We deliberately do NOT re-throw: throwing tears the
        // component down (CSW stops the TLA → onShutdown → the HMI server and
        // all actors go away), leaving no way to recover without restarting the
        // process.  Instead we come up Faulted; the HMI server and actors are
        // already started by this point, so the component still reaches Running
        // and registers with Location Service, the HMI shows the fault with a
        // Clear Fault control, and the operator recovers via faultReset
        // (Init/Reload re-run runInitSequence) or Restart.  This makes a startup
        // failure behave identically to a runtime fault, reusing the same
        // recovery machinery; only possible because the faultReset work made
        // runInitSequence safely repeatable.  EnterFaulted (not a raw state
        // write) so per-axis transitions and initializingReason clearing are
        // applied consistently with every other fault.
        val reason = s"Initialization failed: ${ex.getMessage}"
        log.error(reason, ex = ex)
        internalStateActor ! InternalStateActor.EnterFaulted(reason)
    }
    
  }

  // ========================================
  // Phase 4: Controller Initialization Sequence
  // ========================================

  /**
   * Run the controller-side initialization sequence.  Used by initialize()
   * at startup and (in subsequent sessions) by handleFaultReset for the
   * Init/Reset/Reload severities.
   *
   * Preconditions (caller's responsibility):
   *   - hcdConfig has been loaded
   *   - controllerCommandActor, controllerStatusActor, hmiServer, internalStateActor
   *     are all alive
   *   - command and status TCP connections are functional (or will fail
   *     here with a clear error)
   *   - HCD state is Uninitialized (so the validate / HMI gates reject
   *     stray commands while this sequence runs)
   *
   * Steps (shared by initialize() and faultReset recovery):
   *   1. (hardware only) verifyEmbeddedProgram; diff-only warning, never fails
   *   2. initController (XQ #Init + post-init TC 1 check)
   *   3. polling off → setupAxes → polling on  (BZ commutation pauses TCP)
   *   4. writeMotionConfig; push authoritative motion params from config file
   *   5. readLimitConfig  ; query LD per axis to seed limit-enabled flags
   *
   * The caller is responsible for the Uninitialized → Ready transition
   * after this Future succeeds, and for the Uninitialized → Faulted
   * transition (with reason text) on failure.
   */
  private def runInitSequence(): Future[Unit] = {
    for {
      // Suppress the status actor's per-scan ae[] read until #Init has
      // dimensioned the embedded arrays.  On a freshly power-cycled controller
      // (whose #AUTO does not run #Init) ae[] does not exist yet, and QR
      // polling is already running here; an early `MG ae[i]` would latch
      // controller error 57, which the post-#Init TC 1 check would then
      // misattribute to #Init.  Re-asserted false on every (re)init; including
      // the recovery paths that route through here; because Reset's RS clears
      // the arrays.  Set true again right after initController below.
      // (Wrapped in Future.successful so it can be the first comprehension step;
      // the send runs eagerly when this generator is evaluated.)
      _ <- Future.successful(statusMonitor ! ControllerStatusActor.SetEmbeddedArraysReady(ready = false))
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
      // #Init has now dimensioned ae[] et al.; re-enable the status actor's
      // ae[] read (see SetEmbeddedArraysReady(false) at the top of this for).
      _ = statusMonitor ! ControllerStatusActor.SetEmbeddedArraysReady(ready = true)
      // Read the controller's servo-loop sample period (_TM, µs/sample) and stash
      // it in HcdState.  PVT segment durations are expressed in samples on the
      // wire, so handleTrackAxis converts (validTime delta in µs) / _TM to get
      // T_samples.  _TM is a controller-global scalar set by the embedded #Init;
      // typically 1000µs but read rather than hardcoded so a non-default servo
      // rate would be honored.
      _ <- readSamplePeriod()
      // Suspend QR polling during axis setup. BZ (Brushless Zero) commutation
      // pauses all controller communication per the Galil manual, causing QR
      // polls on the status connection to time out or return corrupt data.
      // Polling is re-enabled after all setup programs complete.
      _ = { statusMonitor ! ControllerStatusActor.SetPolling(enabled = false)
            log.info("QR polling suspended for axis setup (BZ commutation)") }
      _ <- setupAxes()
      _ = { statusMonitor ! ControllerStatusActor.SetPolling(enabled = true)
            log.info("QR polling resumed after axis setup") }
      // Write motion config AFTER setupAxes; #SetupX establishes motor type
      // and hardware config; we then overwrite the motion parameters with the
      // authoritative values from the HCD config file. This applies to both
      // hardware (supplanting EEPROM defaults) and simulator (initialising
      // what would otherwise be unset embedded variables).
      _ <- writeMotionConfig()
      _ <- readLimitConfig()
      _ = log.info(s"Galil HCD init sequence complete (simulate=${hcdConfig.simulate})")
    } yield ()
  }

  // ========================================
  // Fault Recovery; handleFaultReset
  // ========================================

  /**
   * Handle the faultReset CSW command (SDD §4.6.4).  Routed directly from
   * onSubmit, runs on a Future so the framework thread is not blocked.
   *
   * Severity ladder (per ICD §2.2.1.13 and SDD §4.6.4):
   *   None:   Just clear the error message; HCD goes Faulted → Ready.
   *            Pre-step: verify both command and status TCP connections
   *            still respond.  If either is dead we fail recovery rather
   *            than let stale Ready state mislead callers.
   *   Init:   Verify connections, then Faulted → Uninitialized → re-run
   *            the controller init sequence (verifyEmbedded → initController
   *            → setupAxes → writeMotionConfig → readLimitConfig) → Ready.
   *            Used when the controller's embedded program state has gone
   *            stale (e.g. a stale axis error) but the hardware itself is
   *            otherwise fine.
   *   Reset:  Verify connections, send RS to the controller, wait for it
   *            to come back (controller reset takes ~5-10s on STB), reconnect
   *            all three TCP handles (command/status/console) with a 15s
   *            wall-clock budget, then run the init sequence.  Used when
   *            embedded program state is suspected and Init alone won't help.
   *   Reload: Verify connections, upload fresh embedded code from the
   *            HCD's resource folder (DL), burn it to EEPROM (BP), then
   *            perform the Reset sequence (RS + reconnect + init).  Used
   *            when the controller's embedded program is wrong and needs
   *            forced replacement.  EEPROM-write; use sparingly.
   *
   * Final SubmitResponse is delivered via the CRM:
   *   - Completed(runId) on success
   *   - Error(runId, msg) on failure (HCD remains Faulted with error msg
   *     placed via EnterFaulted so per-axis transitions are also re-applied)
   */
  private def handleFaultReset(setup: Setup, runId: Id): Unit = {
    import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

    val severity = Try(setup(FaultResetCommand.severityKey).head.name).getOrElse("None")
    log.info(s"faultReset: severity=$severity")

    severity match {
      case "None" =>
        // Verify both controller TCP connections respond.  Per design (Q2):
        // every severity gates on connection health before doing its work.
        // For None, the gate is the entire body; there is no further
        // controller interaction.  If a connection is down, recovery fails
        // and the HCD remains Faulted with a clear reason.
        log.info("faultReset None: verifying controller connections")

        verifyConnectionsAliveEither() match {
          case Right(_) =>
            // Both connections working; clear the controllerErrorMsg and
            // transition Faulted → Ready.
            internalStateActor ! InternalStateActor.UpdateHcdState(
              Map(
                "state"              -> HcdStateEnum.Ready,
                "controllerErrorMsg" -> ""
              ),
              ctx.system.ignoreRef
            )
            log.info("faultReset None: connections OK — HCD Ready")
            commandResponseManager.updateCommand(Completed(runId))

          case Left(failures) =>
            // One or both still down; re-enter Faulted with a fresh reason
            // (idempotent if already Faulted but re-applies per-axis
            // transitions consistently).  No safe-state ST;MO attempt here:
            // at least one connection is bad, so the send would IOException.
            val errorMsg = s"Connection check failed — $failures"
            internalStateActor ! InternalStateActor.EnterFaulted(errorMsg)
            log.error(s"faultReset None: $errorMsg")
            commandResponseManager.updateCommand(Error(runId, errorMsg))
        }

      case "Init" =>
        // Init severity: verify connections, then re-run the full init
        // sequence.  HCD transitions Faulted → Uninitialized for the
        // duration of the sequence (gates already reject all other
        // commands during Uninitialized; this naturally prevents
        // concurrent recoveries) and then either Uninitialized → Ready
        // on success or Uninitialized → Faulted on failure.
        log.info("faultReset Init: verifying controller connections")

        verifyConnectionsAliveEither() match {
          case Left(failures) =>
            val errorMsg = s"Init connection check failed — $failures"
            internalStateActor ! InternalStateActor.EnterFaulted(errorMsg)
            log.error(s"faultReset Init: $errorMsg")
            commandResponseManager.updateCommand(Error(runId, errorMsg))

          case Right(_) =>
            runRecoveryInitPhase(runId, "faultReset Init")
        }

      case "Reset" =>
        // Reset severity: send RS to the controller, wait briefly for it to
        // come back, reconnect all three TCP handles (command + status +
        // console), then run the init sequence.  Per design (Q2): we still
        // verify connections alive first; if the controller is already
        // unreachable, escalating to RS won't help and we fail fast.
        log.info("faultReset Reset: verifying controller connections")

        verifyConnectionsAliveEither() match {
          case Left(failures) =>
            val errorMsg = s"Reset connection check failed — $failures"
            internalStateActor ! InternalStateActor.EnterFaulted(errorMsg)
            log.error(s"faultReset Reset: $errorMsg")
            commandResponseManager.updateCommand(Error(runId, errorMsg))

          case Right(_) =>
            performResetSequence(runId, "faultReset Reset")
        }

      case "Reload" =>
        // Reload severity: upload fresh embedded code from the repository,
        // burn it to EEPROM, then re-run the init phase.  Used when the
        // controller's embedded program is suspected to be wrong or
        // out-of-date and an Init alone won't help (Init re-runs setup
        // against whatever code is already loaded).
        //
        // Reload does NOT perform RS; DL replaces the loaded program in
        // controller RAM as part of the upload itself, so the new program
        // is already active by the time DL completes.  BP persists it to
        // EEPROM.  Adding RS on top is redundant (RS only re-loads from
        // EEPROM, which we just burnt) and harmful; it forces an
        // unnecessary TCP reconnect cycle and can cause controller-side
        // TCPERR (error 123) due to half-closed sockets, as observed on
        // the STB.  TCP stays connected throughout Reload.
        log.info("faultReset Reload: verifying controller connections")

        verifyConnectionsAliveEither() match {
          case Left(failures) =>
            val errorMsg = s"Reload connection check failed — $failures"
            internalStateActor ! InternalStateActor.EnterFaulted(errorMsg)
            log.error(s"faultReset Reload: $errorMsg")
            commandResponseManager.updateCommand(Error(runId, errorMsg))

          case Right(_) =>
            // Suspend QR/AI polling on the status connection for the duration
            // of the upload + burn + post-BP verify. Rationale:
            // the heavy DL transfer (~6KB streamed in chunks over ~3 seconds)
            // and the immediately-following UL during verifyEmbeddedProgram
            // both exercise the controller's TCP scheduler hard.  When QR
            // polling continues in parallel on the status socket, the
            // controller's TCP input/output buffers can get out of sync,
            // triggering the embedded #TCPERR handler (controller error 123
            // "TCP lost sync or timeout").  When that fires, #TCPERR's
            // `MG "HCD TCPERR ", _IA4` writes to all open TCP handles , 
            // including the command socket where UL's response is
            // streaming; and corrupts the UL response stream.  Suspending
            // polling around the heavy command-side work eliminates the
            // contention.  The init phase that follows (runRecoveryInitPhase
            // → runInitSequence) re-enables polling after its own
            // setupAxes block, so we resume here and let the init flow
            // manage it from there.
            log.info("faultReset Reload: suspending QR polling for DL+BP+verify")
            statusMonitor ! ControllerStatusActor.SetPolling(enabled = false)

            try {
              // Upload the program; burn it to EEPROM; then run the init
              // phase against the freshly-loaded code.  Any failure
              // short-circuits straight back to Faulted.
              uploadAndBurnProgram() match {
                case Left(reason) =>
                  val errorMsg = s"Reload program upload/burn failed: $reason"
                  internalStateActor ! InternalStateActor.EnterFaulted(errorMsg)
                  log.error(s"faultReset Reload: $errorMsg")
                  commandResponseManager.updateCommand(Error(runId, errorMsg))

                case Right(_) =>
                  log.info("faultReset Reload: program uploaded and burnt — proceeding to init phase")
                  // runRecoveryInitPhase calls runInitSequence which begins
                  // with verifyEmbeddedProgram (UL).  Polling stays suspended
                  // through that UL.  runInitSequence then re-enables polling
                  // after #Setup completes, so by the time we return from
                  // runRecoveryInitPhase polling is back on.
                  runRecoveryInitPhase(runId, "faultReset Reload")
              }
            } finally {
              // Defensive resume: if uploadAndBurnProgram threw or
              // runRecoveryInitPhase exited early without re-enabling
              // polling, we don't want to leave it suspended forever.
              // The runInitSequence path already re-enables on its happy
              // path; this catches the failure paths.  Sending SetPolling
              // when it's already enabled is a no-op (handleSetPolling
              // checks for change).
              statusMonitor ! ControllerStatusActor.SetPolling(enabled = true)
            }
        }

      case other =>
        val msg = s"faultReset severity='$other' not yet implemented"
        log.warn(msg)
        commandResponseManager.updateCommand(Error(runId, msg))
    }
  }

  /**
   * Send RS, wait for the controller to come back, reconnect all three TCP
   * handles, then run the init phase.  Shared by Reset and Reload severities;
   * Reload runs uploadAndBurnProgram() before this.
   *
   * Caller has already verified connections are alive.  On any failure step,
   * EnterFaulted is sent and CRM Error is delivered.  On success, the init
   * phase handles the final state transition to Ready.
   */
  private def performResetSequence(runId: Id, reason: String): Unit = {
    sendControllerReset() match {
      case Left(rsReason) =>
        val errorMsg = s"$reason RS send failed: $rsReason"
        internalStateActor ! InternalStateActor.EnterFaulted(errorMsg)
        log.error(s"$reason: $errorMsg")
        commandResponseManager.updateCommand(Error(runId, errorMsg))

      case Right(_) =>
        // RS issued; controller is rebooting.  Settle delay before first
        // reconnect attempt; empirically reconnect can succeed anywhere from
        // ~5s to ~10s after RS on STB.  We start polling at 2s with 1s
        // retry interval, total budget 15s.
        log.info(s"$reason: RS sent — settling 2s before reconnect attempts")
        Thread.sleep(2000L)

        reconnectAllWithRetry(budgetMs = 15000) match {
          case Left(rcReason) =>
            val errorMsg = s"$reason post-RS reconnect failed: $rcReason"
            internalStateActor ! InternalStateActor.EnterFaulted(errorMsg)
            log.error(s"$reason: $errorMsg")
            commandResponseManager.updateCommand(Error(runId, errorMsg))

          case Right(_) =>
            log.info(s"$reason: all connections re-established — entering init phase")
            runRecoveryInitPhase(runId, reason)
        }
    }
  }

  /**
   * Reload primitive: load the embedded program from resources, send DL, then
   * send BP.  Returns Right(()) on full success or Left(reason) on any
   * failure. Used by Reload severity.
   *
   * Both DL and BP run on the command connection.  BP takes 2-3s on real
   * hardware; the BurnProgram handler in CCA temporarily extends the socket
   * read timeout to cover this.
   */
  private def uploadAndBurnProgram(): Either[String, Unit] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    val askScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    // Step 1: load the program text from resources
    val programText = Try {
      Await.result(loadEmbeddedProgram(), 10.seconds)
    } match {
      case scala.util.Success(text) => text
      case scala.util.Failure(ex)   => return Left(s"loadEmbeddedProgram failed: ${ex.getMessage}")
    }

    // Step 1b: prepare the program text for DL upload.
    //
    // prepareProgramForUpload strips REM lines and blank lines, removes
    // trailing whitespace, compresses any line over 80 chars by stripping
    // inter-token whitespace (matches GDK behavior; see Galil DL command
    // reference: "If there are too many lines or too many characters per
    // line, the controller will return a ?."), validates that every line
    // is within the 80-char limit, and joins with CR+LF.
    val cleanedProgram = ProgramFileManager.prepareProgramForUpload(programText)
    log.info(s"Prepared program for DL: ${cleanedProgram.length} chars (raw was ${programText.length})")

    // Step 2: upload to controller via DL (galilIo.uploadProgram)
    val uploadResult = Try {
      Await.result(
        controllerCommandActor.ask[GalilCommandMessage.UploadProgramResult](
          ref => GalilCommandMessage.UploadProgram(cleanedProgram, ref)
        )(org.apache.pekko.util.Timeout(15.seconds), askScheduler),
        16.seconds
      )
    }
    uploadResult match {
      case scala.util.Success(r) if r.success => // continue
      case scala.util.Success(r)              => return Left(s"DL failed: ${r.error.getOrElse("unknown")}")
      case scala.util.Failure(ex)             => return Left(s"DL ask threw: ${ex.getMessage}")
    }

    // Step 3: burn to EEPROM via BP
    val burnResult = Try {
      Await.result(
        controllerCommandActor.ask[GalilCommandMessage.BurnProgramResult](
          ref => GalilCommandMessage.BurnProgram(ref)
        )(org.apache.pekko.util.Timeout(10.seconds), askScheduler),
        11.seconds
      )
    }
    burnResult match {
      case scala.util.Success(r) if r.success => Right(())
      case scala.util.Success(r)              => Left(s"BP failed: ${r.error.getOrElse("unknown")}")
      case scala.util.Failure(ex)             => Left(s"BP ask threw: ${ex.getMessage}")
    }
  }

  /**
   * Common post-connection init phase shared by Init, Reset, Reload.
   * Caller has verified connections are alive; this method:
   *   1. transitions HCD state to Uninitialized with the given reason
   *   2. runs runInitSequence with the same 120s budget as initialize()
   *   3. on success → Ready (clears initializingReason), CRM Completed
   *   4. on failure → EnterFaulted (which clears initializingReason as a
   *      side effect), CRM Error
   */
  private def runRecoveryInitPhase(runId: Id, reason: String): Unit = {
    log.info(s"$reason: entering Uninitialized for init sequence")
    internalStateActor ! InternalStateActor.UpdateHcdState(
      Map(
        "state"              -> HcdStateEnum.Uninitialized,
        "controllerErrorMsg" -> "",
        "initializingReason" -> reason
      ),
      ctx.system.ignoreRef
    )

    try {
      Await.result(runInitSequence(), 120.seconds)
      // Recovery succeeded: explicitly clear controllerErrorMsg in addition
      // to setting Ready state.  Without this, a transient Faulted that the
      // status actor may have driven during recovery (e.g., from a TCPERR
      // event raised while DL/BP/RS was disturbing the controller) would
      // leave its error message visible in the HMI banner even though the
      // HCD itself is Ready and functional.  Recovery completing
      // successfully is the authoritative "all clear"; clear the message.
      internalStateActor ! InternalStateActor.UpdateHcdState(
        Map(
          "state"              -> HcdStateEnum.Ready,
          "initializingReason" -> "",
          "controllerErrorMsg" -> ""
        ),
        ctx.system.ignoreRef
      )
      log.info(s"$reason: init sequence complete — HCD Ready")
      commandResponseManager.updateCommand(Completed(runId))
    } catch {
      case ex: Exception =>
        val errorMsg = s"$reason init sequence failed: ${ex.getMessage}"
        log.error(errorMsg, ex = ex)
        internalStateActor ! InternalStateActor.EnterFaulted(errorMsg)
        commandResponseManager.updateCommand(Error(runId, errorMsg))
    }
  }

  /**
   * Send the RS controller-reset command via ControllerCommandActor.
   * Returns Right(()) on success or Left(reason) on failure.  The caller
   * must wait for the controller to come back and reconnect; RS drops
   * all TCP sessions on the controller.
   */
  private def sendControllerReset(): Either[String, Unit] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    val askScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
    Try {
      Await.result(
        controllerCommandActor.ask[GalilCommandMessage.SendResetResult](
          ref => GalilCommandMessage.SendReset(ref)
        )(org.apache.pekko.util.Timeout(5.seconds), askScheduler),
        6.seconds
      )
    } match {
      case scala.util.Success(result) =>
        if (result.success) Right(())
        else Left(result.error.getOrElse("RS reported failure"))
      case scala.util.Failure(ex) =>
        Left(s"RS ask threw: ${ex.getMessage}")
    }
  }

  /**
   * Reconnect all three TCP handles (command, status, console) after an
   * RS, retrying each that fails until either all succeed or the wall-clock
   * budget is exhausted.  The console handle is informational and not
   * required for Ready state, but we attempt it as part of full recovery.
   * Console failures are logged but do not fail the overall reconnect , 
   * Reset/Reload should not be blocked by an unrecoverable console handle.
   *
   * @param budgetMs total wall-clock budget across all retries
   */
  private def reconnectAllWithRetry(budgetMs: Long): Either[String, Unit] = {
    val deadline = System.currentTimeMillis() + budgetMs
    var cmdOk = false
    var stsOk = false
    var lastCmdErr: String = ""
    var lastStsErr: String = ""

    while (!(cmdOk && stsOk) && System.currentTimeMillis() < deadline) {
      if (!cmdOk) tryReconnectCommand() match {
        case Right(_)     => cmdOk = true; log.info("Reconnect-with-retry: command OK")
        case Left(reason) => lastCmdErr = reason
      }
      if (!stsOk) tryReconnectStatus() match {
        case Right(_)     => stsOk = true; log.info("Reconnect-with-retry: status OK")
        case Left(reason) => lastStsErr = reason
      }
      if (!(cmdOk && stsOk) && System.currentTimeMillis() < deadline) {
        Thread.sleep(1000L)
      }
    }

    if (!(cmdOk && stsOk)) {
      val failures = Seq(
        if (!cmdOk) Some(s"Command: $lastCmdErr") else None,
        if (!stsOk) Some(s"Status: $lastStsErr")  else None
      ).flatten.mkString("; ")
      Left(s"Required connections did not come back within ${budgetMs}ms — $failures")
    } else {
      // Required (command+status) connections are back.  Try console as a
      // best-effort.  Console is informational; log but don't fail.
      tryReconnectConsole() match {
        case Right(_)     => log.info("Reconnect-with-retry: console OK")
        case Left(reason) => log.warn(s"Reconnect-with-retry: console failed (informational, continuing): $reason")
      }
      Right(())
    }
  }

  private def tryReconnectCommand(): Either[String, Unit] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    val askScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
    Try {
      Await.result(
        controllerCommandActor.ask[GalilCommandMessage.ReconnectResult](
          ref => GalilCommandMessage.Reconnect(ref)
        )(org.apache.pekko.util.Timeout(15.seconds), askScheduler),
        16.seconds
      )
    } match {
      case scala.util.Success(r) if r.success => Right(())
      case scala.util.Success(r)              => Left(r.error.getOrElse("failed"))
      case scala.util.Failure(ex)             => Left(s"ask threw: ${ex.getMessage}")
    }
  }

  private def tryReconnectStatus(): Either[String, Unit] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    val askScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
    Try {
      Await.result(
        statusMonitor.ask[ControllerStatusActor.ReconnectResult](
          ref => ControllerStatusActor.Reconnect(ref)
        )(org.apache.pekko.util.Timeout(15.seconds), askScheduler),
        16.seconds
      )
    } match {
      case scala.util.Success(r) if r.success => Right(())
      case scala.util.Success(r)              => Left(r.error.getOrElse("failed"))
      case scala.util.Failure(ex)             => Left(s"ask threw: ${ex.getMessage}")
    }
  }

  /**
   * Reconnect the console handle if it exists.  Console actor is only
   * spawned in hardware mode; in simulator mode this is a no-op success.
   */
  private def tryReconnectConsole(): Either[String, Unit] = {
    if (consoleActor == null) return Right(())  // simulator mode, nothing to do
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    val askScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler
    Try {
      Await.result(
        consoleActor.ask[ControllerConsoleActor.ReconnectResult](
          ref => ControllerConsoleActor.Reconnect(ref)
        )(org.apache.pekko.util.Timeout((ControllerConsoleActor.ReconnectTimeoutMs + 1000).millis), askScheduler),
        (ControllerConsoleActor.ReconnectTimeoutMs + 2000).millis
      )
    } match {
      case scala.util.Success(r) if r.success => Right(())
      case scala.util.Success(r)              => Left(r.error.getOrElse("failed"))
      case scala.util.Failure(ex)             => Left(s"ask threw: ${ex.getMessage}")
    }
  }

  /**
   * Verify both command and status TCP connections respond.  Each actor's
   * Reconnect handler tests its existing socket first (fast path) and
   * opens a fresh socket only if the test fails.  IS connection-status
   * fields are updated by the actors themselves as a side-effect.
   *
   * Returns Right(()) on success, Left(reason) where reason describes
   * which connection(s) failed and why.
   *
   * Note: the console connection is not part of this check.  It is
   * informational and excluded from isOperational; it is recovered
   * separately during Reset/Reload severities via tryReconnectConsole().
   */
  private def verifyConnectionsAliveEither(): Either[String, Unit] = {
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    val askScheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    val cmdResult = Try {
      Await.result(
        controllerCommandActor.ask[GalilCommandMessage.ReconnectResult](
          ref => GalilCommandMessage.Reconnect(ref)
        )(org.apache.pekko.util.Timeout(15.seconds), askScheduler),
        16.seconds
      )
    }.getOrElse(GalilCommandMessage.ReconnectResult(
      success = false,
      error   = Some("Command reconnect ask timed out")
    ))

    val stsResult = Try {
      Await.result(
        statusMonitor.ask[ControllerStatusActor.ReconnectResult](
          ref => ControllerStatusActor.Reconnect(ref)
        )(org.apache.pekko.util.Timeout(15.seconds), askScheduler),
        16.seconds
      )
    }.getOrElse(ControllerStatusActor.ReconnectResult(
      success = false,
      error   = Some("Status reconnect ask timed out")
    ))

    val cmdOk = cmdResult.success
    val stsOk = stsResult.success
    log.info(s"verifyConnectionsAlive: command=${if (cmdOk) "OK" else "FAILED"}, " +
             s"status=${if (stsOk) "OK" else "FAILED"}")

    if (cmdOk && stsOk) Right(())
    else Left(
      Seq(
        if (!cmdOk) Some(s"Command: ${cmdResult.error.getOrElse("failed")}") else None,
        if (!stsOk) Some(s"Status: ${stsResult.error.getOrElse("failed")}")  else None
      ).flatten.mkString("; ")
    )
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
   * for functional comparison; the only theoretical false-negative would be
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
   *   embedded_expected.dmc ; normalized resource program
   *   embedded_actual.dmc   ; normalized controller program
   *   embedded_diff.txt     ; the full diff output
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

    // Return a summary for the log; first few differences + counts
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
   * Read the controller's servo-loop sample period (`_TM`, microseconds per sample)
   * once at init and stash it in `HcdState.controllerSamplePeriodMicros`.
   *
   * `_TM` is the time base for the entire Galil controller; every motion timing
   * primitive (jog speed, PVT segment duration, profiled-move duration, etc.) is
   * expressed in samples.  Default is 1000 µs/sample (1 kHz servo loop) on both
   * lab DMC-50040 and STB DMC-4080; the value is set by `TM <µs>` and can be
   * reconfigured at runtime, so we read it rather than hardcode.
   *
   * Consumer: `CommandHandlerActor.handleTrackAxis` uses this to convert the PVT
   * segment duration from microseconds (from `validTime` deltas) into integer
   * samples for the `PVA<x>=ΔP,V,T` wire form.  Without this read, every
   * `trackAxis` Errors with "controller sample period not yet known."
   *
   * Failure handling: if the read fails or returns a non-numeric response, log a
   * warning and leave `controllerSamplePeriodMicros = 0` (the default).  The HCD
   * continues to come up; only tracking is unavailable until the next successful
   * read (e.g. after a `faultReset Init`).
   */
  private def readSamplePeriod(): Future[Unit] = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._

    implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(3.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    log.debug("readSamplePeriod: MG _TM")
    // _TM is required, not optional: PVT tracking expresses segment durations in
    // controller samples (validTime delta / _TM), so without it the HCD cannot
    // track.  There is no reason for _TM to be unreadable on a healthy
    // controller, so any failure here fails initialization (HCD comes up
    // Faulted) rather than silently disabling tracking.
    try {
      val future = controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
        ref => GalilCommandMessage.SendCommand("MG _TM", ref)
      )
      val result = Await.result(future, 2.seconds)
      result.error match {
        case Some(err) =>
          Future.failed(new RuntimeException(s"readSamplePeriod: MG _TM failed: $err"))
        case None =>
          val text = result.response.trim
          // `_TM` is reported as a real number (e.g. "1000.0000"); take the truncated
          // integer value.  Anything <= 0 indicates a parse failure or impossible config.
          text.toDoubleOption.map(_.toInt) match {
            case Some(periodMicros) if periodMicros > 0 =>
              internalStateActor ! InternalStateActor.UpdateHcdState(
                Map("controllerSamplePeriodMicros" -> periodMicros),
                ctx.system.ignoreRef
              )
              log.info(s"Controller sample period: _TM = ${periodMicros}µs " +
                       s"(${1000000 / periodMicros} Hz servo loop)")
              Future.successful(())
            case _ =>
              Future.failed(new RuntimeException(s"readSamplePeriod: unparseable _TM response '$text'"))
          }
      }
    } catch {
      case ex: Exception =>
        Future.failed(new RuntimeException(s"readSamplePeriod: exception reading _TM: ${ex.getMessage}", ex))
    }
  }

  /**
   * Write motion configuration from HCD config file to the controller's embedded variables.
   *
   * Called after #SetupX runs. The HCD config file is the authoritative source for all
   * motion parameters; this write supplants whatever values the embedded #SetupX programs
   * initialised, making the config file the single source of truth when under HCD control.
   *
   * Three-tier parameter authority:
   *   Tier 1 (embedded EEPROM defaults); used for standalone Galil Tools testing, no HCD
   *   Tier 2 (HCD config file)         ; written here; effective for HCD standalone or with Assembly
   *   Tier 3 (Assembly configAxis)     ; runtime override for the current session
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

    // Collect per-axis failures across all active axes so init reports a single,
    // complete picture.  A non-empty list fails the returned Future, which fails
    // initialization (and brings the HCD up Faulted); motion config is not
    // optional: running on stale EEPROM values when the config write failed
    // would silently mis-drive the mechanism.
    val failures = scala.collection.mutable.ListBuffer[String]()

    activeAxes.foreach { axisName =>
      val axis = Axis.fromChar(axisName.head)
      val idx = axis.index

      hcdConfig.axes.get(axisName) match {
        case None =>
          val msg = s"axis $axisName active but no config entry found"
          log.error(s"Motion config: $msg — cannot write motion config")
          failures += msg

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
          } else {
            failures += s"axis $axisName motion config write failed"
          }
      }
    }

    if (failures.nonEmpty)
      Future.failed(new RuntimeException(
        s"Motion config write failed (${failures.mkString("; ")})"))
    else
      Future.successful(())
  }

  /**
   * Read per-axis limit-disable configuration from the controller and seed
   * `forwardLimitEnabled`/`reverseLimitEnabled` on each active axis's IS state.
   *
   * Issued as a single compound `MG _LDA,_LDB,...` round-trip on the command
   * connection. Galil `LD` encoding (decoded from the returned float):
   *   0 = both limits enabled
   *   1 = forward disabled, reverse enabled
   *   2 = forward enabled, reverse disabled
   *   3 = both disabled
   *
   * Source of truth is the embedded program (which sets `LDx=...` per axis at
   * setup time). The HCD just reads it back so the HMI can distinguish a
   * physically absent limit (grey) from an enabled-but-clear limit (green) and
   * an enabled-and-hit limit (red).
   *
   * On any parse or I/O failure, we leave the AxisState defaults in place
   * (`forwardLimitEnabled=true`, `reverseLimitEnabled=true`) so the indicator
   * still distinguishes hit vs clear. A WARN is logged but init does not fail , 
   * limit decoration is informational, not safety-critical (the controller
   * enforces the actual `LD` config regardless of what the HCD knows).
   */
  private def readLimitConfig(): Future[Unit] = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import scala.util.Try

    implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(5.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    val axisLetters = Seq("A", "B", "C", "D", "E", "F", "G", "H")
    val activeAxisLetters = axisLetters.zip(hcdConfig.activeAxes).filter(_._2).map(_._1)

    if activeAxisLetters.isEmpty then
      log.info("readLimitConfig: no active axes, skipping")
      return Future.successful(())

    val mgCmd = "MG " + activeAxisLetters.map(l => s"_LD$l").mkString(",")
    log.debug(s"readLimitConfig: $mgCmd")

    try {
      val future = controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
        ref => GalilCommandMessage.SendCommand(mgCmd, ref)
      )
      val result = Await.result(future, 2.seconds)
      result.error match {
        case Some(err) =>
          log.warn(s"readLimitConfig: command failed ($err) — leaving limit-enabled defaults in place")
        case None =>
          val text   = result.response
          // Split on whitespace OR ';' (the latter is how ControllerCommandActor
          // joins multi-response sequences; a single compound MG normally
          // returns one space-separated reply, but we tokenize defensively).
          val tokens = text.trim.split("[\\s;]+").filter(_.nonEmpty)
          if tokens.length != activeAxisLetters.length then
            log.warn(s"readLimitConfig: expected ${activeAxisLetters.length} tokens, got ${tokens.length} (text='${text.trim}') — leaving defaults")
          else
            activeAxisLetters.zip(tokens).foreach { (letter, tok) =>
              Try(tok.toDouble.toInt).toOption match {
                case None =>
                  log.warn(s"readLimitConfig: axis $letter unparseable token '$tok' — leaving defaults")
                case Some(ld) =>
                  val fwdEnabled = (ld & 0x1) == 0   // bit 0 set = forward disabled
                  val revEnabled = (ld & 0x2) == 0   // bit 1 set = reverse disabled
                  val axis = Axis.fromChar(letter.head)
                  internalStateActor ! InternalStateActor.UpdateAxisState(axis,
                    Map(
                      "forwardLimitEnabled" -> fwdEnabled,
                      "reverseLimitEnabled" -> revEnabled
                    ),
                    ctx.system.ignoreRef
                  )
                  log.info(s"Axis $letter limits: LD=$ld → forward=${if fwdEnabled then "enabled" else "disabled"}, reverse=${if revEnabled then "enabled" else "disabled"}")
              }
            }
      }
    } catch {
      case ex: Exception =>
        log.warn(s"readLimitConfig: exception ($ex) — leaving defaults in place")
    }

    Future.successful(())
  }

  /**
   * Set up all active axes on the controller by running the embedded #Setup program.
   *
   * #Setup (thread 0) launches #SetupA-G on threads 1-7 with WT 2 spacing.
   * Brushless servo axes run BZ (Brushless Zero) commutation which, per the Galil
   * manual, pauses all controller communication until complete. This means the
   * firmware serializes BZ across axes regardless of thread count; #SetupB cannot
   * start until #SetupA's BZ finishes.
   *
   * Thread 0 therefore stays active for almost the entire setup duration (it is
   * blocked on each XQ call while BZ runs on the previous axis). We know setup is
   * complete when ALL threads (0-7) are inactive per MG _NO.
   *
   * We poll MG _NO on the command connection rather than reading IS threadStatus,
   * because QR polling on the status connection is suspended during setup (BZ
   * pauses that connection too). A read timeout on MG _NO means BZ is in progress
   * on that axis; we treat it as "still busy" and keep waiting.
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

    // 1. Motors off before setup; required by many embedded setup commands.
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
    //    here because completion is detected differently; see step 3.
    log.info("Running #Setup")
    val xqResult = Await.result(
      controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
        ref => GalilCommandMessage.SendCommand("XQ #Setup,0", ref)),
      5.seconds)
    xqResult.error.foreach(err => throw new RuntimeException(s"XQ #Setup,0 failed: $err"))
    log.info("XQ #Setup,0 launched — waiting for all threads to complete")

    // 3. Poll MG _NO until all threads (0-7) are inactive (_NO = 0).
    //    Thread 0 stays active until it has spawned all #SetupX programs.
    //    Threads 1-7 go inactive as each axis completes.
    //
    //    BZ (Brushless Zero) pauses all controller communication per the Galil manual.
    //    To avoid desynchronizing the socket with stale pending commands, we set the
    //    command connection read timeout to 0 (infinite) for the duration of the wait.
    //    Each MG _NO call will simply block through any BZ pause and return when the
    //    controller responds. We restore the normal 3s timeout when done.
    //
    //    Overall timeout is enforced by the deadline; 120s covers 4 BZ axes at ~10s each.
    def setReadTimeout(ms: Int): Unit =
      Await.result(
        controllerCommandActor.ask[GalilCommandMessage.SendCommandResult](
          ref => GalilCommandMessage.SetReadTimeout(ms, ref)),
        5.seconds)

    // Use a large explicit timeout rather than 0 (infinite); some JVM/OS combinations
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

    // 4. Apply per-axis config now that hardware setup is done.  Also reset
    //    each active axis's IS state to Lost; at startup this matches the
    //    default (no-op); during faultReset Init recovery it clears any
    //    lingering Error/Homing/Moving/Tracking state from before the fault.
    //    Position/velocity will be refreshed by the next QR poll once
    //    polling resumes; activeCommand cmdState is cleared on fault entry
    //    by EnterFaulted.
    activeAxes.foreach { axisName =>
      val axis = Axis.fromChar(axisName.head)
      internalStateActor ! InternalStateActor.UpdateAxisState(axis,
        Map(
          "axisState"  -> AxisStateEnum.Lost,
          "inPosition" -> false,
          "axisError"  -> ""
        ),
        ctx.system.ignoreRef
      )
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
   * uses CommandWatcherActor; during init, we're blocking in initialize() and
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
      // 1. Send XQ via ExecuteProgram; allocates thread, sends "XQ;MG _XQ<thread>"
      //    as a single compound, returns threadWasActive=true if the parser-side
      //    follow-up _XQ query saw a non-(-1) line number.
      val execFuture = controllerCommandActor.ask[GalilCommandMessage.ExecuteProgramResult](
        ref => GalilCommandMessage.ExecuteProgram(label, ref)
      )
      val execResult = Await.result(execFuture, 5.seconds)

      execResult.error.foreach { err =>
        throw new RuntimeException(s"ExecuteProgram '#$label' failed: $err")
      }

      val allocatedThread = execResult.thread

      if !execResult.threadWasActive then
        // The post-XQ _XQ<thread> query returned -1; thread N has already
        // run and stopped. The host parser yields between commands on a line
        // (the no-switch rule applies to embedded code, not host TCP commands),
        // so a short program (e.g. #Init) can complete in microseconds before
        // the parser-side MG runs. Skip the polling loop and proceed to the
        // TC 1 check below; if the program errored the latch will tell us.
        log.info(s"Thread $allocatedThread: _XQ returned -1 immediately after XQ " +
          s"(program completed before the parser-side follow-up query)")
      else
        // Thread confirmed active; poll IS until it clears
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
          // TC itself failed (unlikely; means the command connection is broken)
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

    // Stop HMI server first (clients will see disconnect).  We MUST await
    // termination; without this, the old binding's existing connections
    // (browser keep-alive HTTP, WebSocket) survive into the new TLA's
    // initialize() on Restart, and HTTP requests continue to be routed by
    // the OLD binding's route closures, which hold references to the
    // already-terminated InternalStateActor.  See HmiServer.stop() for
    // the full explanation.  3s budget = 2s hardDeadline + 1s slack.
    if (hmiServer != null) {
      try {
        Await.result(hmiServer.stop(), 3.seconds)
      } catch {
        case ex: Throwable =>
          log.warn(s"HMI server shutdown await failed: ${ex.getMessage} — proceeding anyway")
      }
    }

    // Stop actors gracefully (null checks for case where initialize failed partway)
    if (statusMonitor != null) statusMonitor ! ControllerStatusActor.SetPolling(enabled = false)
    // Explicitly stop the console actor so its TCP handle is released immediately.
    // Without this, the actor's blocking read thread runs until socket timeout
    // (~3s) before PostStop fires; leaving the controller handle open in the
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

        // Gate: reject all commands when the HCD is not in Ready state.
        //   Uninitialized; startup is still in progress (controller setup,
        //                  motion config writes, etc.); accepting commands
        //                  here would race the init sequence.  No exemption:
        //                  faultReset doesn't make sense when there is no
        //                  fault yet.
        //   Faulted     ; operator must clear the fault first; only
        //                  faultReset is permitted (legacy behavior).
        // Ready commands fall through to normal validation.
        // Gate decision is shared with the HMI path via CommandGate; see that
        // object for why the two paths must agree.  The CSW path exempts only
        // faultReset during Faulted (setSoftLimits is an HMI-only action).
        val hcdState = queryHcdStateSync()
        CommandGate.checkHcdState(hcdState, commandName, Set("faultReset")) match
          case Some(reason) =>
            log.warn(s"Command '$commandName' rejected: $reason")
            return CommandResponse.Invalid(runId, CommandIssue.OtherIssue(reason))
          case None => // Ready, or Faulted-with-faultReset — proceed

        // faultReset is handled directly by GalilHcd, not CommandHandlerActor,
        // so it is not in either CHA classification set.  Validate its
        // parameters explicitly here.
        if (commandName == "faultReset") {
          return validateFaultReset(runId, setup)
        }

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
   * Fails closed; on query failure returns a Faulted state so commands
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

        case other =>
          CommandResponse.Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"Unknown immediate command: $other"))
      }
    } catch {
      case _: NoSuchElementException =>
        CommandResponse.Invalid(runId, CommandIssue.MissingKeyIssue("Required parameter missing"))
    }
  }

  /**
   * Validate the faultReset command.
   *
   * faultReset is the only HCD-administrative command; it does not target an
   * axis and is not gated by axis state.  The ICD declares severity as a
   * required enum (None | Init | Reset | Reload), but for backwards
   * compatibility with the existing Clear Fault button (which omits the
   * parameter and expects None semantics), we default to None when the
   * parameter is absent.  An unrecognised severity value is rejected.
   *
   * Per ICD it is a long-running command; the work runs asynchronously and
   * the final SubmitResponse is delivered via the CRM from handleFaultReset.
   */
  private def validateFaultReset(runId: Id, setup: Setup): ValidateCommandResponse = {
    import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

    try {
      // severity is optional; default None; but if provided it must be one
      // of the four ICD-defined values.  ChoiceKey enforces this at parse
      // time, so reading the parameter is sufficient validation.
      val _ = scala.util.Try(setup(FaultResetCommand.severityKey).head.name).getOrElse("None")
      CommandResponse.Accepted(runId)
    } catch {
      case ex: Exception =>
        CommandResponse.Invalid(runId,
          CommandIssue.OtherIssue(s"faultReset: invalid severity parameter: ${ex.getMessage}"))
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
          setup(TrackAxisCommand.positionKey)
          setup(TrackAxisCommand.rateKey)
          setup(TrackAxisCommand.validTimeKey)
          setup(TrackAxisCommand.axisKey).head
          
        case other =>
          return CommandResponse.Invalid(runId,
            CommandIssue.UnsupportedCommandIssue(s"Unknown long-running command: $other"))
      }

      // Phase 2: Axis state machine validation (SDD Figure 4-2) and, for the
      // motion commands that accept a target, soft-limit enforcement.  Both
      // checks reuse a single IS query for the axis state.
      val commandName = setup.commandName.name
      val axis = Axis.fromChar(axisChoice.name.head)

      validateAxisStateAndLimits(runId, axis, commandName, setup)

    } catch {
      case _: NoSuchElementException =>
        CommandResponse.Invalid(runId, CommandIssue.MissingKeyIssue("Required parameter missing"))
    }
  }

  /**
   * Validate that the given command is permitted in the axis's current state, and
   * (for positionAxis and offsetAxis) that the requested absolute target lies within
   * the axis's soft limits.  Both checks share a single InternalState query for the
   * axis.
   *
   * Soft-limit enforcement intentionally applies only to absolute and relative move
   * commands.  homeAxis is excluded because the homing routine is required to seek
   * the limits.  selectWheel / positionWheel / trackAxis are excluded because they
   * are used only on rotating axes and there is no soft-limit configuration for them.
   *
   * Per AxisState.checkSoftLimit, soft-limit enforcement is itself a no-op when the
   * axis is rotating, when softLimitsEnabled is false, or when limits are not
   * configured (degenerate 0.0/0.0).
   */
  private def validateAxisStateAndLimits(
    runId: Id,
    axis: Axis,
    commandName: String,
    setup: Setup
  ): ValidateCommandResponse = {
    import scala.concurrent.Await
    import scala.concurrent.duration._
    import org.apache.pekko.actor.typed.scaladsl.AskPattern._
    import csw.proto.galil.GalilMotionKeys.`ICS.HCD.GalilMotion`._

    implicit val askTimeout: org.apache.pekko.util.Timeout = org.apache.pekko.util.Timeout(2.seconds)
    implicit val scheduler: org.apache.pekko.actor.typed.Scheduler = ctx.system.scheduler

    try {
      val future = internalStateActor.ask[Option[AxisState]](
        ref => InternalStateActor.GetAxisState(axis, ref)
      )
      val maybeState = Await.result(future, askTimeout.duration)

      maybeState match {
        case Some(axisState) =>
          // 1. State machine check first (SDD Figure 4-2); shared with the
          // HMI path and the CHA backstop via the canonical enum method.
          CommandGate.checkAxisState(axisState, commandName) match {
            case Some(reason) =>
              log.warn(s"Command rejected: $commandName on axis $axis — $reason")
              return CommandResponse.Invalid(runId, CommandIssue.OtherIssue(reason))
            case None =>
              // Transition valid; fall through to soft-limit check.
          }

          // 2. Soft-limit check for positionAxis and offsetAxis.  CommandGate
          // resolves the absolute target (positionAxis = absolute target,
          // offsetAxis = current position + distance) and applies the envelope.
          val rawTarget: Option[Double] = commandName match {
            case "positionAxis" => Some(setup(PositionAxisCommand.targetKey).head.toDouble)
            case "offsetAxis"   => Some(setup(OffsetAxisCommand.distanceKey).head.toDouble)
            case _              => None
          }

          CommandGate.checkSoftLimit(axisState, commandName, axis.toString, rawTarget) match {
            case Some(msg) =>
              log.warn(msg)
              CommandResponse.Invalid(runId, CommandIssue.ParameterValueOutOfRangeIssue(msg))
            case None =>
              CommandResponse.Accepted(runId)
          }

        case None =>
          // Axis not initialized in IS; reject (axis should be initialized during HCD init)
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

        // faultReset is an HCD-administrative command; it does not target an
        // axis, does not allocate a controller thread, and does not go through
        // the normal CommandHandlerActor / CommandWatcherActor pipeline.  It is
        // dispatched directly here so that it can drive HCD lifecycle state
        // transitions (Faulted → Uninitialized → Ready) and call shared init
        // helpers (runInitSequence) that live on this class.  The work runs
        // off-thread so onSubmit returns Started immediately; the final
        // SubmitResponse is delivered via the CRM when handleFaultReset
        // completes.
        if (commandName == "faultReset") {
          Future { handleFaultReset(setup, runId) }
          CommandResponse.Started(runId)
        } else if (CommandHandlerActor.isImmediate(commandName) || CommandHandlerActor.isLongRunning(commandName)) {
          // Route other immediate and long-running commands to CommandHandlerActor
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