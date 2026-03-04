package csw.proto.galil.hcd.hmi

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

  private def axisStateToJson(s: AxisState): JsObject = Json.obj(
    "position"     -> s.position,
    "velocity"     -> s.velocity,
    "axisState"    -> s.axisState.toString,
    "inPosition"   -> s.inPosition,
    "demand"       -> s.demand,
    "axisErrorMsg" -> s.axisError,
    "forwardLimit" -> s.forwardLimit,
    "reverseLimit" -> s.reverseLimit,
    "homeSwitch"   -> s.homeSwitch,
    "isStepper"    -> s.isStepper,
    "motorOff"     -> s.motorOff,
    // Motion configuration (from readMotionConfig + configAxis updates)
    "maxSpeed"            -> s.maxSpeed.getOrElse[Double](0.0),
    "acceleration"        -> s.acceleration.getOrElse[Double](0.0),
    "deceleration"        -> s.deceleration.getOrElse[Double](0.0),
    "indexOffset"          -> s.indexOffset.getOrElse[Double](0.0),
    "indexSpeed"           -> s.indexSpeed.getOrElse[Double](0.0),
    "inPositionThreshold" -> s.inPositionThreshold
  )

  // ── AxisCmdState → JSON ───────────────────────────────────────────────

  private def axisCmdStateToJson(s: AxisCmdState): JsObject = Json.obj(
    "activeThread"  -> s.activeThread,
    "axisErrorMsg"  -> s.axisErrorMsg,
    "inPosition"    -> s.inPosition,
    "moving"        -> s.moving,
    "activeCommand" -> s.activeCommand.map(_.toString).getOrElse("")
  )

  // ── Full state snapshot → JSON ────────────────────────────────────────

  def stateToJson(hcdState: HcdState): String = {
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

    // Thread status as "0100110" string (bit per thread 0-6)
    val threadBits = (0).to(6).map(i =>
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
      "timestamp"          -> hcdState.lastPollingTime.toString,
      "hcdState"           -> hcdState.state.toString,
      "controllerErrorMsg" -> hcdState.controllerErrorMsg,
      "simulation"         -> hcdState.simulation,
      "pollingRateHz"      -> hcdState.currentPollingRateHz,
      "threadStatus"       -> threadBits,
      "activeAxes"         -> activeAxesList,
      "axes"               -> axesJson,
      "cmdState"           -> cmdStateJson
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
   * Build a console line JSON message for WebSocket broadcast.
   */
  def consoleLineToJson(timestamp: java.time.Instant, text: String): String =
    Json.obj(
      "type"      -> "consoleLine",
      "timestamp" -> timestamp.toString,
      "text"      -> text
    ).toString()

  /**
   * Build JSON array of buffered console messages for REST endpoint.
   */
  def consoleMessagesToJson(lines: Seq[ConsoleMessageReader.ConsoleLine]): String = {
    val arr = JsArray(lines.map(l => Json.obj(
      "timestamp" -> l.timestamp.toString,
      "text"      -> l.text
    )))
    Json.obj("lines" -> arr).toString()
  }
}