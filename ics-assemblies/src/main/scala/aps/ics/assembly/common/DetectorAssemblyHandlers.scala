package aps.ics.assembly.common

import org.apache.pekko.actor.Cancellable
import org.apache.pekko.actor.typed.scaladsl.ActorContext
import org.apache.pekko.pattern.after
import org.apache.pekko.util.Timeout
import com.typesafe.config.{Config, ConfigFactory}
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.models.CswContext
import csw.framework.scaladsl.ComponentHandlers
import csw.location.api.models.TrackingEvent
import csw.logging.client.commons.LogAdminUtil
import csw.logging.models.Level
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, ControlCommand, Setup}
import csw.params.core.models.Id
import csw.params.events.SystemEvent
import csw.prefix.models.Prefix
import csw.time.core.models.UTCTime

import csw.proto.galil.config.ConfigServiceLoader

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

/**
 * Common base for the APS-ICS *detector* assembly MOCKS (APT / PIT / PSH),
 * SDD §5. Unlike the motion assemblies this extends CSW [[ComponentHandlers]]
 * directly — there is no Galil HCD, no axes, no PVT. While the real Detector HCD
 * is still to be built, the mock collapses the HCD into the assembly: it
 * MANUFACTURES a synthetic frame in memory on each exposure (no MM-file
 * transfer this cut) and hands it to a [[DetectorImagePublisher]] (a logging
 * STUB by default — no esw-vbds dependency).
 *
 * What the base owns:
 *   - config load (Config Service active version, else bundled resource)
 *   - the mutable camera/cooling/config state (READY/DEGRADED/FAULTED operational
 *     model + IDLE/BUSY/STREAMING/PAUSED camera state)
 *   - periodic telemetry: `status` @1 Hz and `temperatureStatus` @0.1 Hz (a small
 *     temperature drift toward the set point makes the value move)
 *   - the exposure choreography: BUSY -> wait the integration time -> generate a
 *     frame -> (optionally) publish over VBDS (stub) + metrics -> IDLE -> Completed
 *   - the optional guiding LOOP (STREAMING) — used by APT; PIT/PSH never start it
 *   - command-state gating (Faulted, busy/streaming) and the Started+CRM dispatch
 *
 * What each concrete provides (it knows its own generated *DetectorKeys, whose
 * event/command schemas differ between APT and PIT/PSH):
 *   - the per-event builders ([[buildStatusEvent]], [[buildTemperatureEvent]],
 *     [[publishSetupStatus]], [[publishConfigStatus]], the metrics/store/failure
 *     events) and the command sets it accepts while Faulted / busy
 *   - [[validateSpecificCommand]] / [[handleSpecificCommand]] mapping its command
 *     names onto the base helpers below
 *
 * Concurrency: the @volatile state fields are written from the TLA thread (command
 * intake) and from exposure/timer callbacks (scheduler threads). The assembly
 * serialises real work (validateCommand rejects new commands while BUSY), so the
 * writes do not race in practice; the periodic generators only read. This mirrors
 * the lightweight @volatile telemetry idiom used by the motion base.
 */
abstract class DetectorAssemblyHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends ComponentHandlers(ctx, cswCtx):

  import cswCtx._

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  implicit val timeout: Timeout             = Timeout(10.seconds)
  protected val log                         = loggerFactory.getLogger
  protected val assemblyPrefix: Prefix      = componentInfo.prefix

  // ---- Subclass contract -------------------------------------------------

  /** Local config resource name (prototype fallback). */
  protected def configResource: String

  /** VBDS stream name this detector publishes to (from config). */
  protected def imagePublisher: DetectorImagePublisher = stubPublisher

  /** Build the `status` event (1 Hz) from current state, using this detector's keys. */
  protected def buildStatusEvent(): SystemEvent

  /** Build the `temperatureStatus` event (0.1 Hz). */
  protected def buildTemperatureEvent(): SystemEvent

  /** Publish the on-change `setupStatus` event. */
  protected def publishSetupStatus(): Unit

  /** Publish the on-change `configStatus` event. */
  protected def publishConfigStatus(): Unit

  /** Publish `detectorExposureMetrics` after a frame is produced. Default no-op. */
  protected def publishExposureMetrics(frame: Frame, integrationTime: Double): Unit = ()

  /** Publish `exposureStoreCompleted` after a store. Default no-op. */
  protected def publishExposureStoreCompleted(filename: String): Unit = ()

  /** Publish `apsCommandFailureEvent` on a command failure. Default no-op. */
  protected def publishCommandFailure(message: String): Unit = ()

  /** Command names accepted while the assembly is FAULTED (typically recover/resetCamera). */
  protected def faultRecoveryCommands: Set[String]

  /** Command names accepted while the camera is BUSY/STREAMING (aborts, loop control, recover). */
  protected def busyExemptCommands: Set[String]

  /** Validate a detector-specific command (param presence). */
  protected def validateSpecificCommand(runId: Id, setup: Setup): ValidateCommandResponse

  /** Dispatch a detector-specific command to a Future result (uses the base helpers). */
  protected def handleSpecificCommand(runId: Id, setup: Setup): Future[SubmitResponse]

  // ---- Mutable state -----------------------------------------------------

  @volatile protected var componentConfig: Config = ConfigFactory.empty()
  @volatile protected var cfg: DetectorConfig     = DetectorConfig.fromConfig(ConfigFactory.empty())

  @volatile protected var detectorState: DetectorState   = DetectorState.Ready
  @volatile protected var cameraState: CameraAcqState    = CameraAcqState.Idle
  @volatile protected var coolingHealth: CoolingHealth   = CoolingHealth.Good
  @volatile protected var cameraPresent: Boolean         = true

  @volatile protected var currentTemperature: Float = 20.0f // ambient until cooled
  @volatile protected var temperatureSetPoint: Float = -40.0f
  @volatile protected var fanSpeed: String           = "MEDIUM"

  @volatile protected var roi: Roi              = Roi(0, 0, 128, 128)
  @volatile protected var hBin: Int             = 1
  @volatile protected var vBin: Int             = 1
  @volatile protected var gainMode: String      = "12-BIT"
  @volatile protected var acquisitionMode: String = "SINGLE"
  @volatile protected var bufferModel: String   = "SINGLE"
  @volatile protected var loopRateHz: Float     = 0.0f
  @volatile protected var loopIntegrationTime: Double = 0.0

  private val frameCounter           = new AtomicLong(0L)
  protected lazy val stubPublisher   = new StubImagePublisher(log)
  private var statusTimer: Option[Cancellable] = None
  private var tempTimer: Option[Cancellable]   = None
  private var loopTimer: Option[Cancellable]   = None

  // Per-tick temperature step toward the set point (degC per 10 s tick).
  private val TempStepPerTick = 3.0f

  // =========================================================================
  // Lifecycle
  // =========================================================================

  override def initialize(): Unit =
    // Default this component's log level to INFO at runtime; see the rationale
    // comment in MotionAssemblyHandlers.initialize() (and the underlying CSW
    // limitation analysis in GalilHcd.scala, "KNOWN CSW LIMITATION").
    LogAdminUtil.setComponentLogLevel(componentInfo.prefix, Level.INFO)

    log.info(s"$assemblyPrefix: initialize (detector mock)")
    val csPath = componentInfo.prefix.toString.replace('.', '/') + ".conf"
    val loaded = ConfigServiceLoader.load(csPath, configResource, locationService, ctx.system)
    componentConfig = loaded.config
    log.info(s"$assemblyPrefix: config from ${loaded.source}")
    cfg = DetectorConfig.fromConfig(componentConfig)
    // Seed live state from the configured defaults.
    roi                 = cfg.defaultRoi
    hBin                = cfg.hBin
    vBin                = cfg.vBin
    gainMode            = cfg.gainMode
    acquisitionMode     = cfg.acquisitionMode
    bufferModel         = cfg.bufferModel
    temperatureSetPoint = cfg.temperatureSetPoint
    fanSpeed            = cfg.fanSpeed
    detectorState       = DetectorState.Ready
    cameraState         = CameraAcqState.Idle
    isOnline            = true
    log.info(s"$assemblyPrefix: image publisher = ${imagePublisher.kind}, " +
      s"vbds stream = '${cfg.vbdsStream}', default ROI = ${roi.width}x${roi.height}")
    // Periodic telemetry. status @1 Hz, temperatureStatus @0.1 Hz (every 10 s).
    statusTimer = Some(ctx.system.scheduler.scheduleWithFixedDelay(1.second, 1.second)(() => publishStatus()))
    tempTimer   = Some(ctx.system.scheduler.scheduleWithFixedDelay(0.seconds, 10.seconds)(() => tickTemperature()))
    // On-change events once at startup so subscribers have an initial value.
    publishSetupStatus()
    publishConfigStatus()

  override def onShutdown(): Unit =
    log.info(s"$assemblyPrefix: onShutdown")
    statusTimer.foreach(_.cancel())
    tempTimer.foreach(_.cancel())
    loopTimer.foreach(_.cancel())

  override def onGoOffline(): Unit = { isOnline = false }
  override def onGoOnline(): Unit  = { isOnline = true }

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}
  override def onOperationsMode(): Unit                                  = {}
  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {}

  // No HCD this cut, so nothing to track. (Container connections are empty.)
  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    log.debug(s"$assemblyPrefix: tracking event ignored (no Detector HCD in mock): $trackingEvent")

  // =========================================================================
  // Telemetry
  // =========================================================================

  protected def publishStatus(): Unit =
    val _ = eventService.defaultPublisher.publish(buildStatusEvent())

  protected def publishTemperature(): Unit =
    val _ = eventService.defaultPublisher.publish(buildTemperatureEvent())

  /** Drift the temperature toward the set point a step at a time, publish it, and
   *  derive a coarse coolingHealth from how far off we are. */
  private def tickTemperature(): Unit =
    val delta = temperatureSetPoint - currentTemperature
    if math.abs(delta) <= TempStepPerTick then currentTemperature = temperatureSetPoint
    else currentTemperature += math.signum(delta) * TempStepPerTick
    coolingHealth =
      if math.abs(temperatureSetPoint - currentTemperature) <= 1.0f then CoolingHealth.Good
      else if math.abs(temperatureSetPoint - currentTemperature) <= 10.0f then CoolingHealth.Degraded
      else CoolingHealth.Bad
    publishTemperature()

  // =========================================================================
  // Command validation + dispatch
  // =========================================================================

  override def validateCommand(runId: Id, cmd: ControlCommand): ValidateCommandResponse =
    cmd match
      case s: Setup =>
        val name = s.commandName.name
        if detectorState == DetectorState.Faulted && !faultRecoveryCommands.contains(name) then
          Invalid(runId, CommandIssue.WrongInternalStateIssue(
            s"$assemblyPrefix is Faulted; only ${faultRecoveryCommands.mkString("/")} accepted"))
        else
          val streamingOrBusy = cameraState == CameraAcqState.Busy || cameraState == CameraAcqState.Streaming
          if streamingOrBusy && !busyExemptCommands.contains(name) then
            Invalid(runId, CommandIssue.WrongInternalStateIssue(
              s"$assemblyPrefix camera is $cameraState; '$name' not accepted now"))
          else validateSpecificCommand(runId, s)
      case _ =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue("Only Setup commands are supported"))

  override def onSubmit(runId: Id, cmd: ControlCommand): SubmitResponse =
    cmd match
      case s: Setup =>
        handleSpecificCommand(runId, s).onComplete { tryResp =>
          val resp = tryResp.getOrElse(Error(runId, s"internal error: ${tryResp.failed.map(_.getMessage).getOrElse("")}"))
          val stamped: SubmitResponse = resp match
            case _: Completed => Completed(runId)
            case e: Error     => publishCommandFailure(e.message); Error(runId, e.message)
            case i: Invalid   => Invalid(runId, i.issue)
            case c: Cancelled => Cancelled(runId)
            case other        => Error(runId, s"unexpected response: $other")
          commandResponseManager.updateCommand(stamped)
          publishStatus()
        }
        Started(runId)
      case _ =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue("Only Setup commands are supported"))

  // =========================================================================
  // Base helpers the concrete handlers call
  // =========================================================================

  protected def nextFrameIndex(): Long = frameCounter.getAndIncrement()
  protected def framesProduced: Long   = frameCounter.get()

  private def setCamera(s: CameraAcqState): Unit =
    cameraState = s
    publishStatus()

  /** A one-shot delay that completes after `d` without blocking a thread. */
  private def afterDelay(d: FiniteDuration): Future[Unit] =
    after(d, ctx.system.classicSystem.scheduler)(Future.successful(()))

  /**
   * The single-exposure choreography shared by takeAndPublishExposure /
   * takeAndStoreExposure / takeExposure. Goes BUSY, waits the integration time,
   * manufactures a frame, optionally publishes it (stub VBDS) and/or "stores" it
   * (synthetic filename + exposureStoreCompleted), then returns to IDLE.
   */
  protected def runExposure(
      runId: Id,
      integrationTime: Double,
      publishImage: Boolean,
      store: Boolean
  ): Future[SubmitResponse] =
    if cameraState != CameraAcqState.Idle && cameraState != CameraAcqState.Paused then
      Future.successful(Error(runId, s"$assemblyPrefix: cannot start exposure while camera is $cameraState"))
    else
      setCamera(CameraAcqState.Busy)
      val waitMs = math.max(0L, (integrationTime * 1000.0).toLong)
      log.info(s"$assemblyPrefix: exposure start (integration ${integrationTime}s, publish=$publishImage, store=$store)")
      afterDelay(waitMs.millis).map { _ =>
        val frame = SyntheticFrameSource.generate(roi.width, roi.height, nextFrameIndex())
        if publishImage then
          val _ = imagePublisher.publish(cfg.vbdsStream, frame)
        publishExposureMetrics(frame, integrationTime)
        if store then
          val filename = syntheticFilename()
          publishExposureStoreCompleted(filename)
          log.info(s"$assemblyPrefix: stored exposure -> $filename")
        setCamera(CameraAcqState.Idle)
        log.info(s"$assemblyPrefix: exposure complete (frame #${framesProduced - 1})")
        Completed(runId)
      }

  /** Synthetic FITS filename on the configured APS Shared Disk mount. */
  protected def syntheticFilename(): String =
    val ts = System.currentTimeMillis()
    s"${cfg.apsSharedDiskMountPoint}/${assemblyPrefix.toString.replace('.', '_')}_$ts.fits"

  // ---- Guiding loop (APT) -------------------------------------------------

  /** Start the software-triggered guiding loop: STREAMING; a frame is generated
   *  and (stub) published every 1/rate seconds. */
  protected def startLoop(runId: Id, integrationTime: Double, rate: Float): Future[SubmitResponse] =
    if cameraState == CameraAcqState.Streaming then
      Future.successful(Error(runId, s"$assemblyPrefix: exposure loop already running"))
    else
      loopIntegrationTime = integrationTime
      loopRateHz          = rate
      acquisitionMode     = "LOOP"
      bufferModel         = "RING"
      publishSetupStatus()
      armLoopTimer(rate)
      setCamera(CameraAcqState.Streaming)
      log.info(s"$assemblyPrefix: exposure loop started (rate ${rate} Hz, integration ${integrationTime}s)")
      Future.successful(Completed(runId))

  protected def stopLoop(runId: Id): Future[SubmitResponse] =
    loopTimer.foreach(_.cancel()); loopTimer = None
    acquisitionMode = "SINGLE"
    bufferModel     = "SINGLE"
    publishSetupStatus()
    setCamera(CameraAcqState.Idle)
    log.info(s"$assemblyPrefix: exposure loop stopped")
    Future.successful(Completed(runId))

  protected def pauseLoop(runId: Id): Future[SubmitResponse] =
    loopTimer.foreach(_.cancel()); loopTimer = None
    setCamera(CameraAcqState.Paused)
    log.info(s"$assemblyPrefix: exposure loop paused")
    Future.successful(Completed(runId))

  protected def restartLoop(runId: Id): Future[SubmitResponse] =
    if cameraState != CameraAcqState.Paused then
      Future.successful(Error(runId, s"$assemblyPrefix: exposure loop is not paused"))
    else
      armLoopTimer(loopRateHz)
      setCamera(CameraAcqState.Streaming)
      log.info(s"$assemblyPrefix: exposure loop restarted")
      Future.successful(Completed(runId))

  private def armLoopTimer(rate: Float): Unit =
    loopTimer.foreach(_.cancel())
    val periodMs = if rate > 0.0f then math.max(1L, (1000.0 / rate).toLong) else 1000L
    loopTimer = Some(
      ctx.system.scheduler.scheduleWithFixedDelay(periodMs.millis, periodMs.millis) { () =>
        val frame = SyntheticFrameSource.generate(roi.width, roi.height, nextFrameIndex())
        val _     = imagePublisher.publish(cfg.vbdsStream, frame)
      }
    )

  // ---- Config / cooling / recovery ---------------------------------------

  /** Apply a configDetector command: override ROI/binning/gain where supplied,
   *  then republish setupStatus + configStatus. */
  protected def applyConfig(
      newRoi: Option[Roi],
      newHBin: Option[Int],
      newVBin: Option[Int],
      newGain: Option[String]
  ): Future[SubmitResponse] =
    newRoi.foreach(r => roi = r)
    newHBin.foreach(b => hBin = b)
    newVBin.foreach(b => vBin = b)
    newGain.foreach(g => gainMode = g)
    publishSetupStatus()
    publishConfigStatus()
    log.info(s"$assemblyPrefix: configured ROI=${roi.width}x${roi.height}@(${roi.startCol},${roi.startRow}) " +
      s"bin=${hBin}x${vBin} gain=$gainMode")
    Future.successful(Completed(Id()))

  /** Apply a cooling command: set point (+ optional fan); temperature drifts via the timer. */
  protected def applyCooling(setPoint: Float, fan: Option[String]): Future[SubmitResponse] =
    temperatureSetPoint = setPoint
    fan.foreach(f => fanSpeed = f)
    publishTemperature()
    log.info(s"$assemblyPrefix: cooling set point -> $setPoint degC, fan=$fanSpeed")
    Future.successful(Completed(Id()))

  /** setDefaultConfiguration: restore every live value from the configured defaults. */
  protected def applyDefaults(): Future[SubmitResponse] =
    roi                 = cfg.defaultRoi
    hBin                = cfg.hBin
    vBin                = cfg.vBin
    gainMode            = cfg.gainMode
    acquisitionMode     = cfg.acquisitionMode
    bufferModel         = cfg.bufferModel
    temperatureSetPoint = cfg.temperatureSetPoint
    fanSpeed            = cfg.fanSpeed
    publishSetupStatus()
    publishConfigStatus()
    log.info(s"$assemblyPrefix: restored default configuration")
    Future.successful(Completed(Id()))

  /** recover(mode, autoResume): mock recovery — clears Faulted back to Ready/Idle. */
  protected def recoverFromFault(runId: Id, mode: String, autoResume: Boolean): Future[SubmitResponse] =
    log.warn(s"$assemblyPrefix: recover mode=$mode autoResume=$autoResume")
    detectorState = DetectorState.Ready
    cameraState   = if autoResume then CameraAcqState.Streaming else CameraAcqState.Idle
    if autoResume then armLoopTimer(if loopRateHz > 0 then loopRateHz else cfg.frameRate)
    publishStatus()
    Future.successful(Completed(runId))

  /** resetCamera: re-initialise the (mock) camera — back to Idle/Ready, present. */
  protected def resetCameraMock(runId: Id): Future[SubmitResponse] =
    loopTimer.foreach(_.cancel()); loopTimer = None
    cameraPresent = true
    cameraState   = CameraAcqState.Idle
    detectorState = DetectorState.Ready
    publishStatus()
    log.info(s"$assemblyPrefix: camera reset")
    Future.successful(Completed(runId))

  /** abortHighSpeedExposure / abortExposure: stop an in-progress exposure quickly. */
  protected def abortExposureMock(runId: Id): Future[SubmitResponse] =
    loopTimer.foreach(_.cancel()); loopTimer = None
    setCamera(CameraAcqState.Idle)
    log.info(s"$assemblyPrefix: exposure aborted")
    Future.successful(Completed(runId))