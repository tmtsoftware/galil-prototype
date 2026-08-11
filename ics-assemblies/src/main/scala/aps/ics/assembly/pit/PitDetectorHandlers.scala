package aps.ics.assembly.pit

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

import aps.ics.assembly.common.{DetectorAssemblyHandlers, Frame, Roi}
import aps.ics.assembly.icd.PitDetectorKeys.`ICS.PIT.Detector` as K

import scala.concurrent.Future

/**
 * PIT Detector Assembly MOCK (APS.ICS.PIT.Detector), SDD §5.2.
 *
 * The Teledyne single-exposure camera used by the PIT loop. In the real system
 * `takeExposure` hands a frame to the ICS Computation Assembly via a memory-mapped
 * file (no VBDS); the mock simply manufactures a frame in memory and publishes the
 * exposure metrics. `storeExposure` / `takeAndStoreExposure` write a FITS file to
 * the APS Shared Disk and emit `exposureStoreCompleted` (synthetic filename here).
 *
 * Command names follow PitDetectorKeys EXACTLY — PIT uses `configureDetector` /
 * `configureDetectorCooling` and `analogGainMode` (LOW/HIGH/HDR).
 */
class PitDetectorHandlers(ctx: ActorContext[TopLevelActorMessage], cswCtx: CswContext)
    extends DetectorAssemblyHandlers(ctx, cswCtx):

  import cswCtx._

  override protected def configResource: String = "PitDetector.conf"

  override protected def faultRecoveryCommands: Set[String] = Set("recover", "resetCamera")
  override protected def busyExemptCommands: Set[String]     = Set("abortExposure", "recover", "resetCamera")

  // PIT/PSH configStatus fields not modelled in the shared DetectorConfig; read
  // from the component config with safe defaults. `gainMode` (base) carries the
  // analogGain choice (LOW/HIGH/HDR).
  private var bitDepth: String          = "16-bit"
  private var shutterMode: String       = "Rolling"
  private var cms: String               = "OFF"
  private var integrationTimeSec: Float = 0.0f

  override def initialize(): Unit =
    super.initialize()
    val c = componentConfig
    if c.hasPath("defaultBitDepth") then bitDepth = c.getString("defaultBitDepth")
    if c.hasPath("defaultShutterMode") then shutterMode = c.getString("defaultShutterMode")
    if c.hasPath("defaultCms") then cms = c.getString("defaultCms")
    if c.hasPath("defaultIntegrationTime") then integrationTimeSec = c.getDouble("defaultIntegrationTime").toFloat
    publishSetupStatus()
    publishConfigStatus()

  // ---- validation --------------------------------------------------------

  override protected def validateSpecificCommand(runId: Id, s: Setup): ValidateCommandResponse =
    s.commandName.name match
      case "configureDetectorCooling" =>
        if s.exists(K.ConfigureDetectorCoolingCommand.temperatureSetPointKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("configureDetectorCooling requires temperatureSetPoint"))
      case "configureDetector"        => Accepted(runId)
      case "setDefaultConfiguration"  => Accepted(runId)
      case "takeExposure" | "takeAndStoreExposure" =>
        if s.exists(K.TakeExposureCommand.integrationTimeKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue(s"${s.commandName.name} requires integrationTime"))
      case "storeExposure" | "abortExposure" | "resetCamera" => Accepted(runId)
      case "recover" =>
        if s.exists(K.RecoverCommand.modeKey) then Accepted(runId)
        else Invalid(runId, CommandIssue.MissingKeyIssue("recover requires mode"))
      case other =>
        Invalid(runId, CommandIssue.UnsupportedCommandIssue(s"unsupported command: $other"))

  // ---- dispatch ----------------------------------------------------------

  override protected def handleSpecificCommand(runId: Id, s: Setup): Future[SubmitResponse] =
    s.commandName.name match
      case "configureDetectorCooling" =>
        applyCooling(s(K.ConfigureDetectorCoolingCommand.temperatureSetPointKey).head, None)

      case "configureDetector" =>
        val newRoi = Roi(
          startRow = optInt(s, K.ConfigureDetectorCommand.roiStartRowKey).getOrElse(roi.startRow),
          startCol = optInt(s, K.ConfigureDetectorCommand.rotStartColKey).getOrElse(roi.startCol), // ICD key name typo: rotStartCol
          width    = optInt(s, K.ConfigureDetectorCommand.roiWidthKey).getOrElse(roi.width),
          height   = optInt(s, K.ConfigureDetectorCommand.roiHeightKey).getOrElse(roi.height)
        )
        val h = optInt(s, K.ConfigureDetectorCommand.hBinKey)
        val v = optInt(s, K.ConfigureDetectorCommand.vBinKey)
        val g = if s.exists(K.ConfigureDetectorCommand.analogGainModeKey) then Some(s(K.ConfigureDetectorCommand.analogGainModeKey).head.name) else None
        s.get(K.ConfigureDetectorCommand.bitDepthKey).foreach(p => bitDepth = p.head.name)
        // command shutterMode domain is ROLLING/GLOBAL; configStatus domain is Rolling/Global.
        s.get(K.ConfigureDetectorCommand.shutterModeKey).foreach(p => shutterMode = normShutter(p.head.name))
        s.get(K.ConfigureDetectorCommand.cmsKey).foreach(p => cms = p.head.name)
        applyConfig(Some(newRoi), h, v, g)

      case "setDefaultConfiguration" => applyDefaults()

      case "takeExposure" =>
        s.get(K.TakeExposureCommand.analogGainModeKey).foreach(p => gainMode = p.head.name)
        integrationTimeSec = s(K.TakeExposureCommand.integrationTimeKey).head
        publishSetupStatus()
        // mmap hand-off to ICS Computation in the real system; mock just produces a frame.
        runExposure(runId, integrationTimeSec.toDouble, publishImage = false, store = false)

      case "takeAndStoreExposure" =>
        s.get(K.TakeAndStoreExposureCommand.analogGainModeKey).foreach(p => gainMode = p.head.name)
        integrationTimeSec = s(K.TakeAndStoreExposureCommand.integrationTimeKey).head
        publishSetupStatus()
        runExposure(runId, integrationTimeSec.toDouble, publishImage = false, store = true)

      case "storeExposure" =>
        val filename = syntheticFilename()
        publishExposureStoreCompleted(filename)
        log.info(s"$assemblyPrefix: stored last exposure -> $filename")
        Future.successful(Completed(runId))

      case "abortExposure" => abortExposureMock(runId)

      case "recover" =>
        val mode = s(K.RecoverCommand.modeKey).head.name
        val auto = s.get(K.RecoverCommand.autoResumeKey).exists(_.head)
        recoverFromFault(runId, mode, auto)

      case "resetCamera" => resetCameraMock(runId)

      case other => Future.successful(Error(runId, s"unsupported command: $other"))

  private def optInt(s: Setup, k: csw.params.core.generics.Key[Int]): Option[Int] =
    if s.exists(k) then Some(s(k).head) else None

  /** Map the command shutterMode domain (ROLLING/GLOBAL) to the configStatus domain (Rolling/Global). */
  private def normShutter(v: String): String = if v.equalsIgnoreCase("GLOBAL") then "Global" else "Rolling"

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
      K.SetupStatusEvent.frameRateKey.set(cfg.frameRate),
      K.SetupStatusEvent.pathKey.set(cfg.bufferPath),
      K.SetupStatusEvent.integrationTimeKey.set(integrationTimeSec),
      K.SetupStatusEvent.roiStartRowKey.set(roi.startRow),
      K.SetupStatusEvent.roiStartColKey.set(roi.startCol),
      K.SetupStatusEvent.roiHeightKey.set(roi.height),
      K.SetupStatusEvent.roiWidthKey.set(roi.width),
      K.SetupStatusEvent.hBinKey.set(hBin),
      K.SetupStatusEvent.vBinKey.set(vBin)
    )
    val _ = eventService.defaultPublisher.publish(ev)

  override protected def publishConfigStatus(): Unit =
    val ev = SystemEvent(assemblyPrefix, K.ConfigStatusEvent.eventKey.eventName).madd(
      K.ConfigStatusEvent.analogGainKey.set(Choice(gainMode)),
      K.ConfigStatusEvent.bitDepthKey.set(Choice(bitDepth)),
      K.ConfigStatusEvent.shutterModeKey.set(Choice(shutterMode)),
      K.ConfigStatusEvent.cmsKey.set(Choice(cms))
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

/** Start the PIT Detector mock from its single-assembly container. */
object PitDetectorApp:
  def main(args: Array[String]): Unit =
    val defaultConfig = ConfigFactory.load("PitDetectorContainer.conf")
    ContainerCmd.start("PitDetector", Subsystem.APS, args, Some(defaultConfig))
