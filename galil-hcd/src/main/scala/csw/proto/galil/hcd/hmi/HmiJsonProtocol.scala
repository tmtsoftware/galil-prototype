package csw.proto.galil.hcd.hmi

import csw.prefix.models.Prefix
import csw.proto.galil.hcd._
import csw.time.core.models.TAITime
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
    // HMI telemetry panel can render fields directly.  The ledger stores TAI
    // instants (matching trackAxis validTime); the HMI computes "age vs now"
    // client-side against the browser's UTC clock, so convert TAI -> UTC here at
    // the display boundary (else age/run carry the ~37s TAI-UTC offset).  Outside
    // of Tracking, this serializes as JsNull so the HMI can detect "no session".
    val trackingSessionJson: JsValue = s.trackingSession match {
      case Some(ts) => Json.obj(
        "lastTargetCounts"   -> ts.lastTargetCounts,
        "lastValidTime"      -> TAITime(ts.lastValidTime).toUTC.value.toString,
        "btFiredAt"          -> TAITime(ts.btFiredAt).toUTC.value.toString,
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
      // Wheel readback (rotating mechanisms): achieved slot from the embedded #Select logic
      // and the slot currently commanded. -1 = unknown / none. When a select governs,
      // inPosition is (wheelPosition == commandedWheelPosition); equal values => in position.
      "wheelPosition"          -> s.wheelPosition,
      "commandedWheelPosition" -> s.commandedWheelPosition,
      // Soft limits (linear axes only)
      "upperLimit" -> s.upperLimit.getOrElse[Double](0.0),
      "lowerLimit" -> s.lowerLimit.getOrElse[Double](0.0),
      "softLimitsEnabled" -> s.softLimitsEnabled,
      // Motion configuration (seeded from config at init + configAxis updates)
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

  def stateToJson(hcdState: HcdState, prefix: Prefix, cpu: Option[CpuLoadMonitor.Sample] = None): String = {
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
      "analogInputs"       -> JsArray(hcdState.analogInputs.map(v => JsNumber(BigDecimal(v.toDouble))).toIndexedSeq),
      // Per-JVM CPU load (REQ-2-APS-0621); JsNull until the monitor's first sample.
      "cpuLoad"            -> (cpu match {
        case Some(s) => Json.obj(
          "processCpuLoad"      -> s.processCpuLoad,
          "systemCpuLoad"       -> s.systemCpuLoad,
          "availableProcessors" -> s.availableProcessors
        )
        case None => JsNull
      })
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

  // ── Position history (ADR-002) ────────────────────────────────────────

  /**
   * Encode a history snapshot columnar-style.
   *
   * Columnar rather than an array of per-sample objects because the field names would
   * otherwise be repeated once per sample per axis -- at 3000 samples x 8 axes that is
   * the difference between a compact payload and a few hundred KB of duplicated keys.
   *
   * Absent axes are `null`, never 0: `PositionHistoryBuffer` stores NaN for an axis that
   * was not present in a scan, and JSON has no NaN.  Rendering it as 0 would draw a
   * fictitious trace at the origin for every unconfigured axis.
   *
   * An axis column is emitted only if at least one of its samples is non-NaN, so a
   * 4-axis controller does not ship four all-null columns on every backfill.
   *
   * `nextSeq` is the caller's cursor for the next incremental read.  `gap` is true when
   * the requested `since` had already been evicted from the ring, so the consumer is
   * told it lost samples rather than being handed a trace with a hidden discontinuity.
   */
  def positionSamplesToJson(
    snapshot: PositionHistoryBuffer.Snapshot,
    capacity: Int,
    msgType: String = "positionSamples"
  ): String = {
    val axesJson = JsObject(
      (0 until PositionHistoryBuffer.AxisCount).flatMap { idx =>
        val col = snapshot.positions(idx)
        if (col.exists(v => !v.isNaN)) {
          val values: IndexedSeq[JsValue] =
            col.iterator.map(v => if (v.isNaN) JsNull else JsNumber(BigDecimal(v))).toIndexedSeq
          Some(('A' + idx).toChar.toString -> JsArray(values))
        } else None
      }
    )

    Json.obj(
      "type"     -> msgType,
      "firstSeq" -> snapshot.firstSeq,
      "nextSeq"  -> snapshot.nextSeq,
      "gap"      -> snapshot.gap,
      "capacity" -> capacity,
      "spanMs"   -> snapshot.spanMs,
      // Sample times are UTC epoch millis, taken at the QR scan (the acquisition
      // instant), so they align directly with the log panel's CSW timestamps.
      "t"        -> JsArray(snapshot.times.iterator.map(t => JsNumber(BigDecimal(t))).toIndexedSeq),
      "axes"     -> axesJson
    ).toString()
  }

  /**
   * Render a history snapshot as CSV: `timeMs,timeIso,<axis>...`, oldest sample first.
   *
   * Both a raw epoch column and an ISO column: the former for plotting tools, the latter
   * so a human can line a row up against a log message without converting anything.
   * Absent axes are empty cells rather than 0, for the same reason as the JSON encoder.
   */
  def positionHistoryCsv(snapshot: PositionHistoryBuffer.Snapshot): String = {
    val presentAxes: Seq[Int] =
      (0 until PositionHistoryBuffer.AxisCount).filter(idx => snapshot.positions(idx).exists(v => !v.isNaN))

    val sb = new StringBuilder(64 + snapshot.size * (24 + presentAxes.size * 12))
    sb.append("timeMs,timeIso")
    presentAxes.foreach(idx => sb.append(',').append(('A' + idx).toChar))
    sb.append('\n')

    var i = 0
    while (i < snapshot.size) {
      val t = snapshot.times(i)
      sb.append(t).append(',').append(java.time.Instant.ofEpochMilli(t).toString)
      presentAxes.foreach { idx =>
        sb.append(',')
        val v = snapshot.positions(idx)(i)
        if (!v.isNaN) sb.append(v)
      }
      sb.append('\n')
      i += 1
    }
    sb.toString()
  }

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