package csw.proto.galil.hcd.hmi

import csw.prefix.models.Prefix
import csw.proto.galil.hcd._
import play.api.libs.json._

/**
 * JSON protocol for the GalilMotion HCD HMI.
 *
 * Uses play-json (already available via galil-io dependency) for serialization.
 * Field names match the ICD (CurrentStateAxis, CommandStateAxis) so the frontend
 * data model is consistent with the production ESW Gateway format.
 */
object HmiJsonProtocol {

  // ── AxisState → JSON ──────────────────────────────────────────────────

  private def axisStateToJson(s: AxisState): JsObject = {
    // Tracking-session ledger: serialize as a nested object when present so the
    // HMI telemetry panel can render fields directly.  ISO-8601 timestamps are
    // formatted UTC; the HMI computes "age vs now" client-side.  Outside of
    // Tracking, this serializes as JsNull so the HMI can detect "no session".
    val trackingSessionJson: JsValue = s.trackingSession match {
      case Some(ts) => Json.obj(
        "lastTargetCounts"   -> ts.lastTargetCounts,
        "lastValidTime"      -> ts.lastValidTime.toString,
        "btFiredAt"          -> ts.btFiredAt.toString,
        "segmentsSubmitted"  -> ts.segmentsSubmitted
      )
      case None => JsNull
    }

    Json.obj(
      // Wrapped position in encoder counts [0, cpr) for rotating axes; equals raw position for linear.
      // Matches the demand space used by positionAxis/offsetAxis commands; no confusing wrap offsets.
      "position"           -> s.motorPosition,
      // Raw accumulated encoder counts from controller (accumulates across revolutions for rotating axes).
      // Useful for diagnostics and verifying the wrapping logic.
      "rawMotorPosition"   -> s.position,
      "velocity"     -> s.velocity,
      "axisState"    -> s.axisState.toString,
      "inPosition"   -> s.inPosition,
      // Wrapped demand [0, cpr) for rotating axes; matches motorPosition frame for HMI display.
      "demand"       -> s.motorDemand,
      "axisErrorMsg" -> s.axisError,
      "forwardLimit" -> s.forwardLimit,
      "reverseLimit" -> s.reverseLimit,
      "forwardLimitEnabled" -> s.forwardLimitEnabled,
      "reverseLimitEnabled" -> s.reverseLimitEnabled,
      "homeSwitch"   -> s.homeSwitch,
      "isStepper"    -> s.isStepper,
      "motorOff"     -> s.motorOff,
      // Mechanism configuration
      "mechanismType" -> s.mechanismType.toString,
      "algorithm"     -> s.algorithm.map(_.toString).getOrElse(""),
      // Angular position [0,360°); non-zero only for rotating axes with countsPerRevolution set
      "angularPosition"  -> s.angularPosition.getOrElse(0.0),
      "countsPerRevolution"  -> s.countsPerRevolution.getOrElse[Double](0.0),
      // Soft limits (linear axes only)
      "upperLimit" -> s.upperLimit.getOrElse[Double](0.0),
      "lowerLimit" -> s.lowerLimit.getOrElse[Double](0.0),
      "softLimitsEnabled" -> s.softLimitsEnabled,
      // Motion configuration (from readMotionConfig + configAxis updates)
      "maxSpeed"            -> s.maxSpeed.getOrElse[Double](0.0),
      "acceleration"        -> s.acceleration.getOrElse[Double](0.0),
      "deceleration"        -> s.deceleration.getOrElse[Double](0.0),
      "indexOffset"          -> s.indexOffset.getOrElse[Double](0.0),
      "indexSpeed"           -> s.indexSpeed.getOrElse[Double](0.0),
      "inPositionThreshold" -> s.inPositionThreshold,
      "axisName"            -> s.axisName.getOrElse(""),
      // PVT tracking telemetry; populated by IS from CS's _PV/_BT readings during
      // Tracking.  pvFreeSlots = 255 and btSegmentsExecuted = 0 outside of Tracking
      // (auto-reset on transition out).  trackingSession is null outside Tracking.
      "trackingSession"     -> trackingSessionJson,
      "pvFreeSlots"         -> s.pvFreeSlots,
      "btSegmentsExecuted"  -> s.btSegmentsExecuted
    )
  }

  // ── AxisCmdState → JSON ───────────────────────────────────────────────

  private def axisCmdStateToJson(s: AxisCmdState): JsObject = Json.obj(
    "activeThread"  -> s.activeThread,
    "axisErrorMsg"  -> s.axisErrorMsg,
    "inPosition"    -> s.inPosition,
    "moving"        -> s.moving,
    "activeCommand" -> s.activeCommand.map(_.toString).getOrElse("")
  )

  // ── Full state snapshot → JSON ────────────────────────────────────────

  def stateToJson(hcdState: HcdState, prefix: Prefix): String = {
    val axesJson = JsObject(
      hcdState.axes.map { case (axis, state) =>
        axis.toString -> axisStateToJson(state)
      }.toSeq
    )
    val cmdStateJson = JsObject(
      hcdState.cmdStates.map { case (axis, state) =>
        axis.toString -> axisCmdStateToJson(state)
      }.toSeq
    )

    // Thread status as "01001100" string (bit per thread 0-7, DMC-500x0 has 8 threads)
    val threadBits = (0 to 7).map(i =>
      if ((hcdState.threadStatus & (1 << i)) != 0) "1" else "0"
    ).mkString

    // Active axes as array of axis letters (e.g. ["A","B"])
    val activeAxesList = JsArray(
      hcdState.activeAxes.zipWithIndex.collect {
        case (true, idx) => JsString(('A' + idx).toChar.toString)
      }.toSeq
    )

    Json.obj(
      "type"               -> "stateUpdate",
      "prefix"             -> prefix.toString,
      "timestamp"          -> hcdState.lastPollingTime.toString,
      "hcdState"           -> hcdState.state.toString,
      "controllerErrorMsg" -> hcdState.controllerErrorMsg,
      "initializingReason" -> hcdState.initializingReason,
      "controllerAxisCount" -> hcdState.controllerAxisCount,
      "simulation"         -> hcdState.simulation,
      "pollingRateHz"      -> hcdState.currentPollingRateHz,
      "threadStatus"       -> threadBits,
      "activeAxes"         -> activeAxesList,
      // Per-connection status for the three TCP handles
      "commandConnection"  -> hcdState.commandConnection.toString,
      "statusConnection"   -> hcdState.statusConnection.toString,
      "consoleConnection"  -> hcdState.consoleConnection.toString,
      // True when both command and status connections are established
      "isOperational"      -> hcdState.isOperational,
      "axes"               -> axesJson,
      "cmdState"           -> cmdStateJson,
      "digitalInputs"      -> JsArray(hcdState.digitalInputs.map(b => JsBoolean(b)).toIndexedSeq),
      "digitalOutputs"     -> JsArray(hcdState.digitalOutputs.map(b => JsBoolean(b)).toIndexedSeq),
      "analogInputs"       -> JsArray(hcdState.analogInputs.map(v => JsNumber(BigDecimal(v.toDouble))).toIndexedSeq)
    ).toString()
  }

  // ── Command request parsing ───────────────────────────────────────────

  /**
   * Parsed command request from the frontend.
   */
  case class CommandRequest(
    commandName: String,
    params: Map[String, JsValue]
  )

  def parseCommandRequest(body: String): CommandRequest = {
    val json = Json.parse(body)
    CommandRequest(
      commandName = (json \ "commandName").as[String],
      params = (json \ "params").asOpt[JsObject].map(_.value.toMap).getOrElse(Map.empty)
    )
  }

  /**
   * Build a command response JSON string.
   */
  def commandResponseJson(runId: String, status: String, message: String = ""): String =
    Json.obj(
      "runId"   -> runId,
      "status"  -> status,
      "message" -> message
    ).toString()

  /**
   * Build a log line JSON message for WebSocket broadcast.
   *
   * Emitted by HmiLogAppender for every CSW log message that meets the
   * minimum level threshold. The frontend renders these in a unified log
   * panel alongside state updates, ordered by timestamp.
   *
   * @param severity  CSW severity string: "TRACE","DEBUG","INFO","WARN","ERROR","FATAL"
   * @param timestamp ISO-8601 timestamp string (set by CSW logger at call time)
   * @param message   Log message text extracted from the CSW log JsObject
   * @param actor     Last Pekko actor path segment, e.g. "ControllerStatusActor"; "Console" for non-actor loggers
   */
  def logLineToJson(severity: String, timestamp: String, message: String, actor: String): String =
    Json.obj(
      "type"      -> "logLine",
      "severity"  -> severity,
      "timestamp" -> timestamp,
      "message"   -> message,
      "actor"     -> actor
    ).toString()
}