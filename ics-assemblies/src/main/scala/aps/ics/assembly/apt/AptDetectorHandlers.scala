package aps.ics.assembly.apt

import org.apache.pekko.actor.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.command.client.messages.TopLevelActorMessage
import csw.params.commands.CommandResponse._
import csw.params.commands.{CommandIssue, Setup}
import csw.params.core.models.{Choice, Id}
import csw.params.events.SystemEvent
import csw.prefix.models.Subsystem
import csw.time.core.models.TAITime

import aps.ics.assembly.common.{
  DetectorAssemblyHandlers,
  DetectorImagePublisher,
  DetectorState,
  Frame,
  Roi,
  VbdsImagePublisher
}
import aps.ics.assembly.icd.AptDetectorKeys.`ICS.APT.Detector` as K

import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.{Failure, Success}

/**
 * APT Detector Assembly MOCK (APS.ICS.APT.Detector), SDD §5.1.
 *
 * The Andor acquisition/guiding camera — the one detector whose primary output is
 * an image PUBLISHED over VBDS (acquisition single frames + the continuous
 * guiding loop), so it drives the real [[aps.ics.assembly.common.VbdsImagePublisher]]
 * (FITS over the VBDS transfer route). Single exposures use the base exposure
 * choreography; the guiding loop uses the base STREAMING loop. High-speed taking
 * is mocked as a burst that stores a representative frame.
 *
 * APT is the SOLE VBDS publisher: it creates its stream at initialize() and comes
 * up FAULTED until the vbds-server confirms the stream (then READY). PIT/PSH store
 * to disk/DMS and keep the logging stub.
 *
 * Command names follow AptDetectorKeys EXACTLY — note APT uses `configDetector` /
 * `configDetectorCooling` (the PIT/PSH detectors use `configure*`).
 */
class AptDetectorHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends DetectorAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String = "AptDetector.conf"

  override protected def faultRecoveryCommands: Set[String] = Set("recover", "resetCamera")
  override protected def busyExemptCommands: Set[String] =
    Set("stopExposureLoop", "pauseExposureLoop", "restartExposureLoop", "abortHighSpeedExposure", "recover", "resetCamera")

  // ---- VBDS image publisher (APT is the sole VBDS publisher) --------------

  /** The real VBDS publisher, from config (host/port/stream/contentType). Built
   *  lazily so [[cfg]] is populated (base.initialize loads it before first use). */
  private lazy val vbds: VbdsImagePublisher =
    new VbdsImagePublisher(cfg.vbdsHost, cfg.vbdsPort, cfg.vbdsStream, cfg.vbdsContentType, ctx.system, log)

  /** Override the base seam: APT publishes real FITS frames over VBDS. */
  override protected def imagePublisher: DetectorImagePublisher = vbds

  private val VbdsRetryInterval = 5.seconds

  /**
   * APT cannot serve images without VBDS, so it comes up FAULTED and only goes
   * READY once the VBDS stream is confirmed created (idempotent — 200 or 409).
   * On failure it stays FAULTED, publishes a failure event, and retries on a
   * timer until the stream is ready (survives startup ordering vs the vbds-server).
   */
  override def initialize(): Unit =
    super.initialize() // loads cfg (builds `vbds`, logs kind=vbds), seeds Ready, starts telemetry
    detectorState = DetectorState.Faulted
    publishStatus()
    log.info(s"$assemblyPrefix: VBDS required — ensuring stream '${cfg.vbdsStream}' at " +
      s"${cfg.vbdsHost}:${cfg.vbdsPort} before going READY")
    ensureVbdsReady()

  private def ensureVbdsReady(): Unit =
    vbds.ensureStream().onComplete {
      case Success(_) =>
        detectorState = DetectorState.Ready
        publishStatus()
        log.info(s"$assemblyPrefix: VBDS stream '${cfg.vbdsStream}' ready — detector READY")
      case Failure(ex) =>
        detectorState = DetectorState.Faulted
        publishCommandFailure(s"VBDS not ready: ${ex.getMessage}")
        publishStatus()
        log.warn(s"$assemblyPrefix: VBDS not ready (${ex.getMessage}); " +
          s"retrying in ${VbdsRetryInterval.toSeconds}s")
        val _ = ctx.system.scheduler.scheduleOnce(VbdsRetryInterval, () => ensureVbdsReady())
    }

  // ---- validation --------------------------------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "configDetectorCooling" =>
        if s.exists(K.ConfigDetectorCoolingCommand.temperatureSetPointKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("configDetectorCooling requires temperatureSetPoint"))
      case "configDetector"        => Accepted(runId) // all parameters optional
      case "setDefaultConfiguration" => Accepted(runId)
      case "takeAndPublishExposure" | "takeAndStoreExposure" =>
        if s.exists(K.TakeAndPublishExposureCommand.integrationTimeKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue(s"${s.commandName.name} requires integrationTime"))
      case "startExposureLoop" =>
        if s.exists(K.StartExposureLoopCommand.integrationTimeKey) && s.exists(K.StartExposureLoopCommand.rateKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("startExposureLoop requires integrationTime and rate"))
      case "takeHighSpeedExposures" =>
        if s.exists(K.TakeHighSpeedExposuresCommand.integrationTimeKey) && s.exists(K.TakeHighSpeedExposuresCommand.durationKey)
        then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("takeHighSpeedExposures requires integrationTime and duration"))
      case "stopExposureLoop" | "pauseExposureLoop" | "restartExposureLoop" | "abortHighSpeedExposure" | "resetCamera" =>
        Accepted(runId)
      case "recover" =>
        if s.exists(K.RecoverCommand.modeKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("recover requires mode"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- dispatch ----------------------------------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): Future[SubmitResponse] =
    s.commandName.name match
      case "configDetectorCooling" =>
        val sp  = s(K.ConfigDetectorCoolingCommand.temperatureSetPointKey).head
        val fan = if s.exists(K.ConfigDetectorCoolingCommand.fanSpeedKey)
          then Some(s(K.ConfigDetectorCoolingCommand.fanSpeedKey).head.name) else None
        applyCooling(sp, fan)

      case "configDetector" =>
        val newRoi = Roi(
          startRow = optInt(s, K.ConfigDetectorCommand.roistartRowKey).getOrElse(roi.startRow),
          startCol = optInt(s, K.ConfigDetectorCommand.roistartColKey).getOrElse(roi.startCol),
          width    = optInt(s, K.ConfigDetectorCommand.roiwidthKey).getOrElse(roi.width),
          height   = optInt(s, K.ConfigDetectorCommand.roiheightKey).getOrElse(roi.height)
        )
        val h = optInt(s, K.ConfigDetectorCommand.hBinKey)
        val v = optInt(s, K.ConfigDetectorCommand.vBinKey)
        val g = if s.exists(K.ConfigDetectorCommand.gainModeKey) then Some(s(K.ConfigDetectorCommand.gainModeKey).head.name) else None
        applyConfig(Some(newRoi), h, v, g)

      case "setDefaultConfiguration" => applyDefaults()

      case "takeAndPublishExposure" =>
        s.get(K.TakeAndPublishExposureCommand.gainModeKey).foreach(p => gainMode = p.head.name)
        runExposure(runId, s(K.TakeAndPublishExposureCommand.integrationTimeKey).head.toDouble, publishImage = true, store = false)

      case "takeAndStoreExposure" =>
        s.get(K.TakeAndStoreExposureCommand.gainModeKey).foreach(p => gainMode = p.head.name)
        runExposure(runId, s(K.TakeAndStoreExposureCommand.integrationTimeKey).head.toDouble, publishImage = false, store = true)

      case "startExposureLoop" =>
        s.get(K.StartExposureLoopCommand.gainModeKey).foreach(p => gainMode = p.head.name)
        val it   = s(K.StartExposureLoopCommand.integrationTimeKey).head.toDouble
        val rate = s(K.StartExposureLoopCommand.rateKey).head
        publishGuidingStatus(it)
        startLoop(runId, it, rate)

      case "stopExposureLoop"    => stopLoop(runId)
      case "pauseExposureLoop"   => pauseLoop(runId)
      case "restartExposureLoop" => restartLoop(runId)

      case "takeHighSpeedExposures" =>
        acquisitionMode = "BURST"; bufferModel = "CONTAINER"; publishSetupStatus()
        val dur = s(K.TakeHighSpeedExposuresCommand.durationKey).head.toDouble
        runExposure(runId, dur, publishImage = false, store = true)
          .map { r => acquisitionMode = "SINGLE"; bufferModel = "SINGLE"; publishSetupStatus(); r }

      case "abortHighSpeedExposure" => abortExposureMock(runId)

      case "recover" =>
        val mode = s(K.RecoverCommand.modeKey).head.name
        val auto = s.get(K.RecoverCommand.autoResumeKey).exists(_.head)
        recoverFromFault(runId, mode, auto)

      case "resetCamera" => resetCameraMock(runId)

      case other => Future.successful(Error(runId, s"unsupported command: $other"))

  private def optInt(s: Setup, k: csw.params.core.generics.Key[Int]): Option[Int] =
    if s.exists(k) then Some(s(k).head) else None

  // ---- telemetry builders (AptDetectorKeys) ------------------------------

  override protected def buildStatusEvent(): SystemEvent =
    SystemEvent(assemblyPrefix, K.StatusEvent.eventKey.eventName).madd(
      K.StatusEvent.assemblyStateKey.set(Choice(detectorState.choice)),
      K.StatusEvent.coolingHealthKey.set(Choice(coolingHealth.choice)),
      K.StatusEvent.cameraPresentKey.set(cameraPresent),
      K.StatusEvent.cameraAcquisitionStateKey.set(Choice(cameraState.choice))
    )

  override protected def buildTemperatureEvent(): SystemEvent =
    SystemEvent(assemblyPrefix, K.TemperatureStatusEvent.eventKey.eventName).madd(
      K.TemperatureStatusEvent.detectorTemperatureKey.set(currentTemperature),
      K.TemperatureStatusEvent.temperatureSetPointKey.set(temperatureSetPoint)
    )

  override protected def publishSetupStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, K.SetupStatusEvent.eventKey.eventName).madd(
      K.SetupStatusEvent.imageSizeKey.set(cfg.imageSizeBytes),
      K.SetupStatusEvent.acquisitionModeKey.set(Choice(acquisitionMode)),
      K.SetupStatusEvent.bufferModelKey.set(Choice(bufferModel)),
      K.SetupStatusEvent.frameRateKey.set(if loopRateHz > 0 then loopRateHz else cfg.frameRate),
      K.SetupStatusEvent.pathKey.set(cfg.bufferPath),
      K.SetupStatusEvent.hBinKey.set(hBin),
      K.SetupStatusEvent.vBinKey.set(vBin)
    )
    val _ = eventService.defaultPublisher.publish(ev)

  override protected def publishConfigStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, K.ConfigStatusEvent.eventKey.eventName).madd(
      K.ConfigStatusEvent.pixelEncodingKey.set(Choice(cfg.pixelEncoding)),
      K.ConfigStatusEvent.pixelReadoutRateKey.set(Choice(cfg.pixelReadoutRate)),
      K.ConfigStatusEvent.spuriousNoiseFilterKey.set(cfg.spuriousNoiseFilter)
    )
    val _ = eventService.defaultPublisher.publish(ev)

  private def publishGuidingStatus(integrationTime: Double): Unit =
    val gm = if gainMode.startsWith("16") then "16-bit" else "12=bit" // matches the generated (quirky) domain
    val ev = SystemEvent(assemblyPrefix, K.GuidingStatusEvent.eventKey.eventName).madd(
      K.GuidingStatusEvent.gainModeKey.set(Choice(gm)),
      K.GuidingStatusEvent.integrationTimeKey.set(integrationTime.toFloat),
      K.GuidingStatusEvent.roiStartRowKey.set(roi.startRow),
      K.GuidingStatusEvent.roiStartColKey.set(roi.startCol),
      K.GuidingStatusEvent.roiHeightKey.set(roi.height),
      K.GuidingStatusEvent.roiWidthKey.set(roi.width)
    )
    val _ = eventService.defaultPublisher.publish(ev)

  override protected def publishExposureMetrics(frame: Frame, integrationTime: Double): Unit =
    val now = TAITime.now()
    val ev = SystemEvent(assemblyPrefix, K.DetectorExposureMetricsEvent.eventKey.eventName).madd(
      K.DetectorExposureMetricsEvent.imageSizeBytesKey.set(frame.sizeBytes.toFloat),
      K.DetectorExposureMetricsEvent.integrationTimeKey.set(integrationTime.toFloat),
      K.DetectorExposureMetricsEvent.exposureReadoutTimeKey.set(now),
      K.DetectorExposureMetricsEvent.imageCorrectionsStartTimeKey.set(now),
      K.DetectorExposureMetricsEvent.imageCorrectionsCompletedTimeKey.set(now),
      K.DetectorExposureMetricsEvent.imagePublishingStartTimeKey.set(now),
      K.DetectorExposureMetricsEvent.imagePublishingCompletedTimeKey.set(now)
    )
    val _ = eventService.defaultPublisher.publish(ev)

  override protected def publishExposureStoreCompleted(filename: String): Unit =
    val ev = SystemEvent(assemblyPrefix, K.ExposureStoreCompletedEvent.eventKey.eventName).madd(
      K.ExposureStoreCompletedEvent.filenameKey.set(filename)
    )
    val _ = eventService.defaultPublisher.publish(ev)

  override protected def publishCommandFailure(message: String): Unit =
    val ev = SystemEvent(assemblyPrefix, K.ApsCommandFailureEventEvent.eventKey.eventName).madd(
      K.ApsCommandFailureEventEvent.sourceAssemblyKey.set(assemblyPrefix.toString),
      K.ApsCommandFailureEventEvent.messageKey.set(message),
      K.ApsCommandFailureEventEvent.recoveryStateKey.set(Choice("NOT_USED"))
    )
    val _ = eventService.defaultPublisher.publish(ev)

/** Start the APT Detector mock from its single-assembly container. */
object AptDetectorApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("AptDetectorContainer.conf")
    ContainerCmd.start("AptDetector", Subsystem.APS, args, Some(defaultConfig))
