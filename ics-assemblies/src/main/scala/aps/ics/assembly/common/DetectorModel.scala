package aps.ics.assembly.common

import com.typesafe.config.Config

/**
 * Common data model for the APS-ICS *detector* assemblies (APT / PIT / PSH).
 *
 * These mocks stand in for the real detector pipeline while the Detector HCD is
 * still to be built. Per the design clarification (superseding the SDD §5.1.6.8.1
 * "subscribes to VBDS from the HCD" language): in the real system the Detector
 * HCD delivers raw images to the assembly via memory-mapped files, and the
 * assembly owns image correction AND publishing (over VBDS for the APT
 * acquisition/guiding path). The mock collapses the HCD into the assembly — it
 * MANUFACTURES a synthetic frame in memory (no MM-file round-trip this cut) and
 * hands it to a [[DetectorImagePublisher]] whose default implementation is a
 * logging STUB (no real VBDS dependency yet).
 *
 * State choices below mirror the generated *DetectorKeys EXACTLY (incl. the
 * mixed-case coolingHealth domain), since the keys are the ICD source of truth.
 */

/** Assembly operational state — SDD §5.x.3.2 (RD18 §7.2). Detectors do not home,
 *  so the domain is simply READY / DEGRADED / FAULTED (the StatusEvent
 *  `assemblyState` choice domain in every *DetectorKeys). */
enum DetectorState(val choice: String):
  case Ready    extends DetectorState("READY")
  case Degraded extends DetectorState("DEGRADED")
  case Faulted  extends DetectorState("FAULTED")

/** Camera acquisition state — StatusEvent `cameraAcquisitionState` choice domain. */
enum CameraAcqState(val choice: String):
  case Idle       extends CameraAcqState("IDLE")
  case Busy       extends CameraAcqState("BUSY")
  case Streaming  extends CameraAcqState("STREAMING")
  case Paused     extends CameraAcqState("PAUSED")
  case Fault      extends CameraAcqState("FAULT")
  case Recovering extends CameraAcqState("RECOVERING")

/** Cooling subsystem health — StatusEvent `coolingHealth` choice domain.
 *  NOTE the mixed-case choice strings ("Good"/"Degraded"/"Bad") come straight
 *  from the generated keys; the Choice values published must match them. */
enum CoolingHealth(val choice: String):
  case Good     extends CoolingHealth("Good")
  case Degraded extends CoolingHealth("Degraded")
  case Bad      extends CoolingHealth("Bad")

/**
 * A synthetic detector frame held in memory. `data` is row-major, length
 * width*height, 32-bit float (the assembly's post-correction representation per
 * SDD §5.1.2.2.1). For the mock nothing reads the pixels except the stub
 * publisher (which logs the size); the field is here so the shape is faithful
 * and a future real VbdsImagePublisher has something to serialise.
 */
final case class Frame(width: Int, height: Int, data: Array[Float]):
  /** Bytes for one frame at 4 bytes/px (float32) — used for imageSize telemetry. */
  def sizeBytes: Int = width * height * 4

/**
 * Region of interest (pixels). Defaults come from the assembly configuration and
 * may be overridden by a configDetector command.
 */
final case class Roi(startRow: Int, startCol: Int, width: Int, height: Int)

/**
 * Detector assembly configuration (mock subset of SDD Table 5-3 / 5-9 / 5-14),
 * loaded once at initialize() from the Config Service active version or the
 * bundled resource. Only the fields the mock actually uses are parsed; missing
 * values fall back to safe defaults so a sparse .conf still starts.
 */
final case class DetectorConfig(
    defaultRoi: Roi,
    hBin: Int,
    vBin: Int,
    gainMode: String,            // "12-BIT" | "16-BIT" (APT) / "LOW"|"HIGH"|"HDR" (PIT/PSH analogGain)
    pixelEncoding: String,       // configStatus pixelEncoding choice
    pixelReadoutRate: String,    // "100MHz" | "200MHz"
    spuriousNoiseFilter: Boolean,
    temperatureSetPoint: Float,  // degC
    fanSpeed: String,            // "OFF"|"LOW"|"MEDIUM"|"HIGH"
    frameRate: Float,            // guiding-loop publish rate (Hz); APT only, harmless elsewhere
    acquisitionMode: String,     // setupStatus acquisitionMode choice: SINGLE|BURST|LOOP
    bufferModel: String,         // setupStatus bufferModel choice: SINGLE|CONTAINER|RING
    bufferPath: String,          // setupStatus path (shared-memory location label)
    apsSharedDiskMountPoint: String,
    vbdsStream: String,          // VBDS stream name the (stubbed) publisher posts to
    vbdsContentType: String      // VBDS content type label
):
  def imageSizeBytes: Int = defaultRoi.width * defaultRoi.height * 4

object DetectorConfig:
  def fromConfig(c: Config): DetectorConfig =
    def i(k: String, d: Int): Int          = if c.hasPath(k) then c.getInt(k) else d
    def f(k: String, d: Double): Float     = (if c.hasPath(k) then c.getDouble(k) else d).toFloat
    def s(k: String, d: String): String    = if c.hasPath(k) then c.getString(k) else d
    def b(k: String, d: Boolean): Boolean  = if c.hasPath(k) then c.getBoolean(k) else d
    DetectorConfig(
      defaultRoi = Roi(
        startRow = i("defaultRoi.startRow", 0),
        startCol = i("defaultRoi.startCol", 0),
        width    = i("defaultRoi.width", 128),
        height   = i("defaultRoi.height", 128)
      ),
      hBin                    = i("defaultHBin", 1),
      vBin                    = i("defaultVBin", 1),
      gainMode                = s("defaultGainMode", "12-BIT"),
      pixelEncoding           = s("defaultPixelEncoding", "Mono16"),
      pixelReadoutRate        = s("defaultPixelReadoutRate", "100MHz"),
      spuriousNoiseFilter     = b("defaultSpuriousNoiseFilter", false),
      temperatureSetPoint     = f("defaultTemperatureSetPoint", -40.0),
      fanSpeed                = s("defaultFanSpeed", "MEDIUM"),
      frameRate               = f("defaultFrameRate", 10.0),
      acquisitionMode         = s("defaultAcquisitionMode", "SINGLE"),
      bufferModel             = s("defaultBufferModel", "SINGLE"),
      bufferPath              = s("defaultBufferFilename", "/dev/shm/aps-detector-mock"),
      apsSharedDiskMountPoint = s("apsSharedDiskMountPoint", "/aps/shared"),
      vbdsStream              = s("vbds.stream", "APS-DETECTOR-RAW"),
      vbdsContentType         = s("vbds.contentType", "image/fits")
    )
