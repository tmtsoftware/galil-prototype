package csw.proto.galil.simulator

import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.util.ByteString
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState, Header}

import scala.concurrent.duration._

/**
 * Actor that simulates a Galil DMC-500x0 controller with full motion emulation.
 *
 * Supports the complete HCD command flow including:
 *   - Embedded program execution (XQ/HX) with thread management via _NO bitmask
 *   - Motion emulation with speed-limited position stepping for QR DataRecord fidelity
 *   - Embedded variable storage (speed[], accel[], decel[], dmd[], etc.)
 *   - QR DataRecord with proper status word bits, switches byte, positions, velocity
 *   - Program download (UL) returning stored program text
 *   - MG queries for _NO, TIME, and embedded array variables
 *
 * This enables the full HardwareIntegrationTest suite to run without hardware,
 * supporting CI testing and Assembly/Sequencer development with a simulated HCD.
 *
 * Architecture follows SDD Section 4.7 — Simulation Strategy:
 *   "The simulation logic maintains internal 'simulated' states for each axis.
 *    When the HCD calls methods like getDataRecordRaw or sendToGalil, it receives
 *    synthesized data that mimics the real controller's responses."
 */
object GalilSimulatorActor {

  // ========================================
  // Galil command constants
  // ========================================

  val ExecuteProgram       = "XQ"
  val HaltExecution        = "HX"
  val Identify             = "ID"
  val SetBit               = "SB"
  val ClearBit             = "CB"
  val AnalogOutput         = "AO"
  val MessageGet           = "MG"
  val AbsTarget            = "PA"
  val Acceleration         = "AC"
  val AmplifierGain        = "AG"
  val AnalogFeedbackSelect = "AF"
  val BeginMotion          = "BG"
  val BrushlessModulus     = "BM"
  val BrushlessZero        = "BZ"
  val Deceleration         = "DC"
  val ErrorCode            = "TC"
  val GetDataRecord        = "QR"
  val GetMotorPosition     = "RP"
  val JogSpeed             = "JG"
  val LowCurrent           = "LC"
  val MotorOff             = "MO"
  val MotorOn              = "SH"
  val MotorSmoothing       = "KS"
  val MotorSpeed           = "SP"
  val MotorType            = "MT"
  val PositionTracking     = "PT"
  val RelTarget            = "PR"
  val SetMotorPosition     = "DP"
  val StepDriveResolution  = "YA"
  val StepMotorResolution  = "YB"
  val StopMotion           = "ST"
  val UploadProgram        = "UL"
  val DownloadProgram      = "DL"
  val BurnProgram          = "BP"
  val ResetController      = "RS"
  val LimitDisable         = "LD"
  val ConfigureInputs      = "CN"
  val HomeVelocity         = "HV"

  // Commands that follow the generic axis set/get pattern: XXA=value or XXA=?
  private val axisCmds = Set(
    AbsTarget, Acceleration, AmplifierGain, AnalogFeedbackSelect,
    BrushlessModulus, BrushlessZero, Deceleration, JogSpeed,
    LowCurrent, MotorSmoothing, MotorSpeed, MotorType,
    PositionTracking, RelTarget, SetMotorPosition,
    StepDriveResolution, StepMotorResolution, HomeVelocity,
    LimitDisable
  )

  // ========================================
  // Protocol
  // ========================================

  sealed trait GalilSimulatorCommand

  /** A Galil command string with a reply destination */
  case class Command(cmd: String, replyTo: ActorRef[ByteString]) extends GalilSimulatorCommand

  /** Periodic timer tick to advance motion simulation */
  private case object MotionTick extends GalilSimulatorCommand

  /** Delayed thread completion — program finished executing */
  private case class ThreadComplete(thread: Int) extends GalilSimulatorCommand

  // ========================================
  // Per-axis simulated state
  // ========================================

  /**
   * One Galil PVT (Position-Velocity-Time) segment as queued in the per-axis FIFO.
   *
   * Wire form: `PV<axis>=deltaP,vEnd,tSamples`
   *   - `deltaP`    : signed position change in counts over this segment
   *   - `vEnd`      : signed velocity in counts/sec to be reached at segment END
   *   - `tSamples`  : segment duration measured in controller sample periods (`_TM`)
   *
   * The active end-of-trajectory marker is `PvtSegment(0, 0, 0)` — when dequeued for
   * execution it stops the trajectory and discards any trailing queued segments.
   */
  case class PvtSegment(deltaP: Long, vEnd: Long, tSamples: Long)

  /**
   * Simulated state for one axis.
   *
   * @param motorOn       SH has been issued (motor enabled)
   * @param position      Current position in encoder/step counts (auxiliaryPosition for steppers)
   * @param demand        Target position from dmd[] variable (set by HCD before XQ #MoveX)
   * @param velocity      Current velocity in counts/sec (signed; reported as 64x in QR)
   * @param maxSpeed      SP value (counts/sec)
   * @param acceleration  AC value (counts/sec^2)
   * @param deceleration  DC value (counts/sec^2)
   * @param moving        Motor is in motion (status bit 15)
   * @param jogging       Motor is in JG mode (tracking)
   * @param motorType     MT value (2.0 = stepper with active low)
   * @param stopCode      Last stop code (0=running, 1=normal stop)
   * @param homed         Axis has been homed (TS bit 1)
   * @param settings      Catch-all for other axis settings (legacy generic commands)
   *
   * --- PVT tracking fields (Session 65) ---
   * @param pvtFifo         Queued PVT segments waiting to execute (FIFO).  Max capacity 255
   *                        segments (matches hw `_PV<x>` reporting free slots 0..255).
   *                        `_PV<x>` reports `255 - pvtFifo.size`; the active segment is
   *                        NOT counted as queued.
   * @param pvtActive       Segment currently executing (Some) or none. Distinct from the queue
   *                        so `_PV<x>` reports queued-only depth, matching hw convention.
   * @param pvtSegStartPos  Position at the moment `pvtActive` began executing — used to compute
   *                        instantaneous position during linear-ramp interpolation.
   * @param pvtSegStartVel  Velocity at the moment `pvtActive` began executing — feeds the linear
   *                        velocity ramp from `pvtSegStartVel` to `pvtActive.vEnd`.
   * @param pvtSamplesElapsed  Samples elapsed since `pvtActive` began (0 .. pvtActive.tSamples).
   * @param btCounter        `_BT<x>` value: count of segments executed since the last `BT<x>`.
   *                        Reset to 0 on each new `BT<x>` (matches hw, NOT cumulative across BTs).
   * @param tracking         Axis is in PVT execution mode (BT has been issued since last drain).
   *                        Distinct from `moving`/`jogging` so the QR status bit and motion
   *                        branch are unambiguous.
   */
  case class SimAxis(
    motorOn: Boolean = false,
    position: Double = 0.0,
    demand: Double = 0.0,
    velocity: Double = 0.0,
    maxSpeed: Double = 10000.0,
    acceleration: Double = 256000.0,
    deceleration: Double = 256000.0,
    moving: Boolean = false,
    jogging: Boolean = false,
    motorType: Double = 2.0,  // stepper
    stopCode: Byte = 0,
    homed: Boolean = false,
    forwardLimitHit: Boolean = false,  // simulated physical limit; reflected in switches byte
    reverseLimitHit: Boolean = false,
    settings: Map[String, Double] = Map.empty,
    // PVT (Session 65)
    pvtFifo: Vector[PvtSegment] = Vector.empty,
    pvtActive: Option[PvtSegment] = None,
    pvtSegStartPos: Double = 0.0,
    pvtSegStartVel: Double = 0.0,
    pvtSamplesElapsed: Long = 0L,
    btCounter: Int = 0,
    tracking: Boolean = false
  )

  /**
   * Global simulator state.
   *
   * @param axes              Per-axis state, keyed by axis char ('A'-'H')
   * @param threadStatus      Bitmask of active threads (bit N = thread N running)
   * @param embeddedVars      Named embedded variables: "speed[0]" -> 10000.0, etc.
   * @param sampleNumber      Incrementing QR sample counter
   * @param errorStatus       Last error code (for TC command)
   * @param digitalOutputs    Simulated digital output bits
   * @param programText       Stored program text (for UL download)
   * @param dlBuffer          When Some, simulator is in DL receive mode and is
   *                          accumulating program lines into this StringBuilder.
   *                          On `\` terminator the buffer is committed to
   *                          programText and reset to None.  None outside DL.
   */
  case class SimState(
    axes: Map[Char, SimAxis] = Map.empty,
    threadStatus: Int = 0,
    embeddedVars: Map[String, Double] = Map.empty,
    sampleNumber: Short = 0,
    errorStatus: Int = 0,
    digitalOutputs: Array[Byte] = Array.fill(10)(0.toByte),
    digitalInputs: Array[Byte] = Array.fill(10)(0.toByte),
    programText: String = "",
    dlBuffer: Option[StringBuilder] = None
  )

  // ========================================
  // Motion simulation constants
  // ========================================

  /** How often the motion simulation ticks (ms) */
  private val MotionTickIntervalMs = 10
  private val MotionTickInterval = MotionTickIntervalMs.milliseconds
  private val MotionTickKey = "motion-tick"

  // Minimum simulated program-completion delay for axis-affecting embedded
  // programs (#HomeX/#StopX/#SetupX/#TrackX, and the arrival→EN tail of
  // #MoveX/#SelectX).
  //
  // Chosen to exceed the HCD's action-rate QR scan interval (100ms at 10Hz)
  // by a comfortable margin, so every simulated program is observed at least
  // once in the "thread active" state before it completes. The previous
  // 50-100ms delays let programs start AND finish entirely between two scans,
  // which is unrealistically fast for homes/moves and accidentally made the
  // simulator a stress test of the sub-scan-completion corner (S82). That
  // corner is still handled correctly by the HCD (the CI actor's thread
  // reservation gate), but the simulator's default timing should reflect
  // realistic program durations, not exercise the corner on every command.
  private val ProgramCompleteDelay = 250.millis

  // Stop is the ONE embedded program that legitimately finishes within a single
  // QR scan on real hardware (STx / #StopX): a stop on an idle axis starts AND
  // completes between two action-rate scans (S82). The simulator models that
  // here so a wave of stops reproduces the sub-scan-completion regime that
  // surfaced the S82 thread-reservation race — deliberately, for stop ONLY (all
  // other programs keep the >=1-scan ProgramCompleteDelay above, which is
  // realistic for homes/moves/setups/tracks). The HCD's thread-reservation gate
  // (S82) is what must hold under this; see AssemblyLoadApp's stop-idle scenario.
  private val StopCompleteDelay = 30.millis

  /** Threshold below which we snap to target (counts) */
  private val SnapThreshold = 0.5

  /**
   * PVT per-axis FIFO capacity. Matches hardware: `_PV<x>` reports free slots
   * with maximum 255 when empty (per Galil REPL characterization, S63 design).
   * Capacity-in-flight (queued + active) is 255; `_PV<x> = 255 - queuedDepth`
   * where the active segment is NOT counted as queued (it has already been
   * dequeued for execution).
   */
  private val PvtFifoCapacity = 255

  /** Axes modeled by the simulator — always 8 regardless of how many are active in HCD config */
  private val SimulatedAxes = Seq('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H')

  // ========================================
  // Entry point
  // ========================================

  def simulate(
    timer: TimerScheduler[GalilSimulatorCommand],
    state: SimState = SimState()
  ): Behavior[GalilSimulatorCommand] =
    Behaviors.receive { (ctx, msg) =>
      msg match {
        case Command(cmd, replyTo) => processCommand(ctx, state, timer, cmd, replyTo)
        case MotionTick            => advanceMotion(state, timer)
        case ThreadComplete(t)     => completeThread(state, timer, t)
      }
    }

  // ========================================
  // Command dispatch
  // ========================================

  private def processCommand(
    ctx: ActorContext[GalilSimulatorCommand],
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand],
    cmdString: String,
    replyTo: ActorRef[ByteString]
  ): Behavior[GalilSimulatorCommand] = {
    try {
      // Log every command except QR (too noisy) and empty (just prompts)
      if (cmdString.nonEmpty && !cmdString.startsWith("QR")) {
        println(s"[SIM] CMD: '$cmdString'")
      }
      val (response, newState) = dispatch(ctx, state, timer, cmdString)
      // Log response for non-QR commands (show first 60 chars)
      if (cmdString.nonEmpty && !cmdString.startsWith("QR")) {
        val respStr = response.utf8String.take(60).replace("\r", "\\r").replace("\n", "\\n")
        println(s"[SIM] RSP: '$respStr'")
      }
      replyTo ! response
      if (newState ne state) simulate(timer, newState) else Behaviors.same
    } catch {
      case e: Exception =>
        println(s"[SIM] ERROR processing '$cmdString': ${e.getMessage}")
        e.printStackTrace()
        replyTo ! formatReply(None, isError = true)
        Behaviors.same
    }
  }

  /**
   * Dispatch a Galil command string to the appropriate handler.
   * Returns (response bytes, possibly-updated state).
   */
  private def dispatch(
    ctx: ActorContext[GalilSimulatorCommand],
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand],
    cmdString: String
  ): (ByteString, SimState) = {

    if (cmdString.isEmpty) {
      return (formatReply(None), state)
    }

    // ---- DL receive mode (Session 58) ----
    // When state.dlBuffer is Some(...), the simulator is mid-DL upload — every
    // incoming line is program text until we see the "\" terminator.  This is
    // checked before the 2-char cmd dispatch so program lines like "JG 100"
    // (which would normally match JogSpeed) don't get mis-dispatched.
    state.dlBuffer match {
      case Some(buf) =>
        if (cmdString == "\\") {
          // Terminator — commit accumulated text and exit DL mode.  Real Galil
          // responds with ":" only on terminator (DL itself is silent).
          val text = buf.toString
          println(s"[SIM] DL complete: ${text.length} chars, ${text.linesIterator.size} lines")
          return (formatReply(None), state.copy(programText = text, dlBuffer = None))
        } else {
          // Append this line + "\r\n" to the buffer.  No response — DL only
          // ack's on terminator, not on intermediate lines.
          buf.append(cmdString).append("\r\n")
          // Returning empty bytes (no ":" prompt) so the IO layer doesn't see
          // an unexpected ack between writeRaw program data writes.
          return (ByteString.empty, state)
        }
      case None => // not in DL mode, fall through to normal dispatch
    }

    val cmd2 = cmdString.take(2)

    cmd2 match {
      // ---- Identity ----
      case `Identify` =>
        // Report as DMC-50080 (8-axis) matching the 8-axis simulation capability (axes A-H).
        // The firmware model digit encodes axis count: DMC500x0 → x axes.
        val idResponse = "FW, DMC50080 Rev 1.2sim\r\nDMC, 50000, Rev 0\r\nCMB, 00000, 0.0v, Rev 0\r\nAMP1, 00000, Rev 0"
        (formatReply(idResponse), state)

      // ---- QR DataRecord (binary) ----
      case `GetDataRecord` =>
        // Log QR state when anything interesting is happening
        val anyMoving = state.axes.exists { case (_, ax) => ax.moving || ax.jogging || ax.tracking }
        if (anyMoving || state.threadStatus != 0) {
          val axSummary = state.axes.toSeq.sortBy(_._1).map { case (c, ax) =>
            val pvt = if (ax.tracking) s",trk(act=${ax.pvtActive.isDefined},q=${ax.pvtFifo.size},bt=${ax.btCounter})" else ""
            s"$c:pos=${ax.position.toInt},mov=${ax.moving},jog=${ax.jogging},mot=${ax.motorOn}$pvt"
          }.mkString(" ")
          println(s"[SIM] QR: threads=0x${state.threadStatus.toHexString} $axSummary")
        }
        val dr = buildDataRecord(state)
        val bytes = ByteString(dr.toByteBuffer) ++ ByteString(":")
        (bytes, state.copy(sampleNumber = (state.sampleNumber + 1).toShort))

      // ---- Error code ----
      case `ErrorCode` =>
        (formatReply(handleTC(state, cmdString)), state)

      // ---- Program execution ----
      case `ExecuteProgram` =>
        handleXQ(ctx, state, timer, cmdString)

      case `HaltExecution` =>
        handleHX(state, timer, cmdString)

      // ---- MG (message/query) — handles _NO, TIME, embedded vars ----
      case `MessageGet` =>
        (formatReply(handleMG(state, cmdString)), state)

      // ---- Motor on/off ----
      case `MotorOn` =>
        handleSH(state, cmdString)

      case `MotorOff` =>
        handleMO(state, timer, cmdString)

      // ---- Stop ----
      case `StopMotion` =>
        handleST(state, timer, cmdString)

      // ---- Begin motion ----
      case `BeginMotion` =>
        handleBG(state, timer, cmdString)

      // ---- Set/Define position ----
      case "DP" =>
        handleDP(state, cmdString)

      // ---- Tell position ----
      case `GetMotorPosition` =>
        val axis = cmdString.drop(2).headOption.getOrElse('A')
        val pos = getAxis(state, axis).position.toInt
        (formatReply(pos), state)

      // ---- TP: Tell Position (encoder/motor position) ----
      case "TP" =>
        handleTellQuery(state, cmdString, ax => ax.position.toInt.toString)

      // ---- TD: Tell Dual/Step position (auxiliary/step count) ----
      case "TD" =>
        handleTellQuery(state, cmdString, ax => ax.position.toInt.toString)

      // ---- TV: Tell Velocity ----
      case "TV" =>
        handleTellQuery(state, cmdString, ax => f"${ax.velocity}%.4f")

      // ---- SC: Stop Code ----
      case "SC" =>
        handleTellQuery(state, cmdString, ax => ax.stopCode.toInt.toString)

      // ---- TS: Tell Switches ----
      case "TS" =>
        handleTellQuery(state, cmdString, ax => switchesByte(ax).toString)

      // ---- TA: Tell Amplifier (not a real Galil command, but useful for inspecting axis state) ----

      // ---- LV: List Variables (names and values) ----
      case "LV" =>
        handleLV(state)

      // ---- LA: List Arrays (names and dimensions) ----
      case "LA" =>
        handleLA(state)

      // ---- Digital I/O ----
      case `SetBit` =>
        // SB n  — set digital output bit n (1-based per Galil convention)
        val bit1 = cmdString.drop(2).trim.toInt
        val idx  = (bit1 - 1) / 8          // which byte (0 = bits 1-8, 1 = bits 9-16)
        val mask = (1 << ((bit1 - 1) % 8)).toByte
        val newOutputs = state.digitalOutputs.clone()
        if idx < newOutputs.length then newOutputs(idx) = (newOutputs(idx) | mask).toByte
        (formatReply(None), state.copy(digitalOutputs = newOutputs))

      case `ClearBit` =>
        // CB n  — clear digital output bit n (1-based per Galil convention)
        val bit1 = cmdString.drop(2).trim.toInt
        val idx  = (bit1 - 1) / 8
        val mask = (1 << ((bit1 - 1) % 8)).toByte
        val newOutputs = state.digitalOutputs.clone()
        if idx < newOutputs.length then newOutputs(idx) = (newOutputs(idx) & ~mask).toByte
        (formatReply(None), state.copy(digitalOutputs = newOutputs))
      case `AnalogOutput` => (formatReply(None), state)

      // ---- Upload program (download FROM controller, confusingly) ----
      case `UploadProgram` =>
        val programResponse = state.programText + "\\\r\n:"
        (ByteString(programResponse), state)

      // ---- Download program (upload TO controller) ----
      // Enter DL receive mode.  No ":" ack from DL itself — real Galil only
      // ack's after the "\" terminator, by which point we'll have accumulated
      // all program lines into dlBuffer (handled at the top of dispatch).
      case `DownloadProgram` =>
        (ByteString.empty, state.copy(dlBuffer = Some(new StringBuilder)))

      // ---- Burn program to EEPROM (Session 58) ----
      // Real hardware: writes the volatile program (loaded via DL) to flash
      // and ack's with ":".  Simulator has no flash to write to, so we just
      // ack — programText is already persisted in SimState, which mirrors
      // "burnt" state for the lifetime of the simulator process.
      case `BurnProgram` =>
        println("[SIM] BP: program (already in memory) treated as burnt")
        (formatReply(None), state)

      // ---- Reset controller (Session 58) ----
      // Real hardware: RS resets the controller and drops all TCP sessions.
      // Simulator: reset state to fresh (re-init embedded vars, clear axes,
      // clear thread status, clear errors) BUT preserve programText — the
      // burnt program survives an RS because it lives in EEPROM, not RAM.
      // We do NOT drop the TCP connection — the recovery code's reconnect
      // path (MG 0 test) will simply succeed without a fresh socket, which
      // is acceptable for simulator-mode tests.  Real-hardware reconnect
      // behaviour is exercised on STB.
      case `ResetController` =>
        println("[SIM] RS: resetting simulator state (programText preserved)")
        val freshState = SimState(
          axes        = state.axes.map { case (c, _) => c -> SimAxis() },
          programText = state.programText
        )
        val resetState = initializeEmbeddedVars(freshState)
        (formatReply(None), resetState)

      // ---- Jog speed (used by tracking) ----
      case `JogSpeed` =>
        handleJG(state, cmdString)

      // ---- PVT segment write: PV<axis>=ΔP,V,T  (Session 65) ----
      // The third letter of the wire command IS the axis designator — `PVA=...` is
      // a PVT segment for axis A, `PVB=...` for axis B, etc.  This was the key
      // wire-format lesson from S64 (initial HCD code wrote `PVAA=...,B=...,...`
      // treating PVA as the command name).  The simulator must accept the same
      // form to be a faithful target for `TrackInjectorApp`.  Match `cmd2 == "PV"`
      // and parse the third char as the axis, leaving the rest to handlePvtSegment.
      case "PV" if cmdString.length >= 4 && cmdString(2).isLetter && cmdString(3) == '=' =>
        handlePvtSegment(state, cmdString)

      // ---- BT<axis>: Begin Trajectory (Session 65) ----
      // Starts execution of any queued PVT segments on the named axis.  `BT` with
      // no axis suffix would begin all axes with pending segments — not used in
      // this project (HCD always operates per-axis).  Reset `_BT<x>` counter to 0
      // and mark the axis tracking; motion advance moves the first queued segment
      // to `pvtActive` on the next tick.
      case "BT" if cmdString.length >= 3 && cmdString(2).isLetter =>
        handleBT(state, timer, cmdString)

      // ---- Configure inputs (CN) — just acknowledge ----
      case `ConfigureInputs` =>
        (formatReply(None), state)

      // ---- Generic axis commands (SP, AC, DC, MT, etc.) ----
      case cmd if axisCmds.contains(cmd) && cmdString.length > 2 && cmdString(2).isLetter =>
        handleGenericAxisCmd(state, cmdString)

      // ---- Embedded variable assignment: name[idx]=value (array) ----
      case _ if cmdString.contains('[') && cmdString.contains('=') && !cmdString.contains("=?") =>
        handleVarAssignment(state, cmdString)

      // ---- Embedded scalar variable assignment: name=value ----
      // Matches lowercase names like "tcon=1000", "version=20260302"
      // Does NOT match axis commands (uppercase 2-char prefix + axis letter, e.g. "SPA=20000")
      case _ if cmdString.contains('=') && !cmdString.contains("=?") && cmdString.head.isLower =>
        handleVarAssignment(state, cmdString)

      // ---- Unknown — return error response matching real Galil behavior ----
      // Real DMC-500x0 returns "?" for any unrecognized command.
      case _ =>
        println(s"[SIM] Unrecognized command (returning ?): '$cmdString'")
        (formatReply(None, isError = true), state)
    }
  }

  // ========================================
  // XQ — Execute embedded program
  // ========================================

  /**
   * Handle XQ #Label,thread
   *
   * Parses the label and thread, sets the thread bit in _NO,
   * and schedules simulated program behavior based on the label.
   */
  private def handleXQ(
    ctx: ActorContext[GalilSimulatorCommand],
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand],
    cmdString: String
  ): (ByteString, SimState) = {
    // Parse: "XQ #Label,thread" or "XQ #Label, thread"
    val args = cmdString.drop(3).trim
    val parts = args.split(",").map(_.trim)
    val label = parts(0).stripPrefix("#")
    val thread = if (parts.length > 1) parts(1).toInt else 0

    println(s"[SIM] XQ #$label on thread $thread")

    // Set thread bit active
    val newThreadStatus = state.threadStatus | (1 << thread)
    var newState = state.copy(threadStatus = newThreadStatus)

    // Set ae[idx]=1 at program entry for axis-affecting labels (mirrors the
    // embedded code convention: each #MoveX/#HomeX/#StopX/#SetupX/#TrackX/
    // #SelectX program writes ae[idx]=1 at entry and clears it on success).
    // ae[idx] is cleared back to 0 in completeThread (or in advanceMotion for
    // motion-arrival completions) when the corresponding thread terminates.
    // The HCD's per-axis attribution logic in ControllerStatusActor relies
    // on this signal.
    //
    // _threadAxis[N]=axisIdx is a thread→axis map kept distinct from
    // _axisThread[idx]=thread (which is owned by the existing motion logic
    // for Move/Select). Keeping them separate avoids overwriting Move's
    // _axisThread when a later Stop is XQ'd on the same axis.
    val axisAffecting: Option[Char] =
      if (label.startsWith("Move") || label.startsWith("Home") ||
          label.startsWith("Stop") || label.startsWith("Setup") ||
          label.startsWith("Track") || label.startsWith("Select"))
        Some(label.last)
      else None
    axisAffecting.foreach { axis =>
      val idx = axis - 'A'
      newState = newState.copy(
        embeddedVars = newState.embeddedVars
          + (s"ae[$idx]" -> 1.0)
          + (s"_threadAxis[$thread]" -> idx.toDouble)
      )
    }

    label match {
      case "Init" =>
        newState = initializeEmbeddedVars(newState)
        scheduleThreadComplete(timer, thread, 50.millis)

      case s if s.startsWith("Setup") =>
        val axis = s.last
        val ax = getAxis(newState, axis).copy(
          motorType = 2.0,
          motorOn = false
        )
        newState = newState.copy(axes = newState.axes + (axis -> ax))
        scheduleThreadComplete(timer, thread, ProgramCompleteDelay)

      case s if s.startsWith("Home") =>
        val axis = s.last
        val ax = getAxis(newState, axis).copy(
          position = 0.0,
          motorOn = true,
          homed = true,
          stopCode = 1
        )
        newState = newState.copy(axes = newState.axes + (axis -> ax))
        scheduleThreadComplete(timer, thread, ProgramCompleteDelay)

      case s if s.startsWith("Move") =>
        val axis = s.last
        val idx = axis - 'A'
        val demand = newState.embeddedVars.getOrElse(s"dmd[$idx]", 0.0)
        val speed = newState.embeddedVars.getOrElse(s"speed[$idx]", 10000.0)
        val accel = newState.embeddedVars.getOrElse(s"accel[$idx]", 256000.0)
        val decel = newState.embeddedVars.getOrElse(s"decel[$idx]", 256000.0)
        val currentPos = getAxis(newState, axis).position
        println(s"[SIM] #Move$axis: pos=$currentPos → demand=$demand, speed=$speed, thread=$thread")

        val ax = getAxis(newState, axis).copy(
          demand = demand,
          maxSpeed = speed,
          acceleration = accel,
          deceleration = decel,
          moving = true,
          jogging = false,
          motorOn = true,
          stopCode = 0
        )
        newState = newState.copy(axes = newState.axes + (axis -> ax))
        // Track which thread drives this axis — advanceMotion will clear it on arrival
        newState = newState.copy(
          embeddedVars = newState.embeddedVars + (s"_axisThread[$idx]" -> thread.toDouble)
        )
        ensureMotionTicking(timer)

      case s if s.startsWith("Track") =>
        val axis = s.last
        val idx = axis - 'A'
        val targetPos = newState.embeddedVars.getOrElse(s"${axis}target[0]", 0.0)
        val targetVel = newState.embeddedVars.getOrElse(s"${axis}target[1]", 0.0)

        val ax = getAxis(newState, axis).copy(
          demand = targetPos,
          velocity = targetVel,
          moving = true,
          jogging = true,
          motorOn = true,
          stopCode = 0
        )
        newState = newState.copy(axes = newState.axes + (axis -> ax))
        ensureMotionTicking(timer)
        // Track program ENDs quickly — motor keeps jogging
        scheduleThreadComplete(timer, thread, ProgramCompleteDelay)

      case s if s.startsWith("Stop") =>
        val axis = s.last
        val idx = axis - 'A'
        // The embedded `#StopX` program begins with `STx`, which on real hw drains
        // any active PVT FIFO and stops the motor.  Mirror that here: clear PVT
        // state alongside the PA/JG moving flags.  This is the only end-of-tracking
        // path the HCD currently uses (its handleStopAxis Tracking branch bypasses
        // checkAndInterrupt and goes straight to executeProgramAndWatch(#StopX)).
        val curAx = getAxis(newState, axis)
        val wasTracking = curAx.tracking
        val ax = curAx.copy(
          moving             = false,
          jogging            = false,
          tracking           = false,
          velocity           = 0.0,
          stopCode           = 4,
          pvtActive          = None,
          pvtFifo            = Vector.empty,
          pvtSamplesElapsed  = 0L
        )
        newState = newState.copy(axes = newState.axes + (axis -> ax))
        if (wasTracking) {
          println(s"[SIM] #Stop$axis: drained PVT FIFO (was tracking)")
        }

        // Clear the thread that was driving motion on this axis (if any).
        // Without this, the move thread leaks forever since advanceMotion
        // will never reach the target to clear it naturally.
        // Also clear ae[idx]=0 (forcibly-terminated, no success path) and
        // remove _threadAxis[mt] for the same reason as in handleMO.
        val moveThreadKey = s"_axisThread[$idx]"
        newState.embeddedVars.get(moveThreadKey).foreach { moveThreadNum =>
          val mt = moveThreadNum.toInt
          println(s"[SIM] #Stop$axis: clearing leaked move thread $mt")
          val clearedStatus = newState.threadStatus & ~(1 << mt)
          newState = newState.copy(
            threadStatus = clearedStatus,
            embeddedVars = newState.embeddedVars
              - moveThreadKey
              - s"_threadAxis[$mt]"
              + (s"ae[$idx]" -> 0.0)
          )
        }
        scheduleThreadComplete(timer, thread, StopCompleteDelay)

      case s if s.startsWith("Select") =>
        // #SelectX: positions a rotating mechanism (e.g. filter wheel) to one of its slots.
        // Mirrors the embedded DMC logic: PAX = (dmd[idx] - 1) * (cpr[idx] / 8)
        //   dmd[idx]  — 1-based wheel position number, set by the HCD selectWheel command
        //   cpr[idx]  — counts per revolution (integer, set by #SetupX / writeMotionConfig)
        //   /8        — 8 equally-spaced slots; the (dmd-1) makes position #1 = home angle 0
        // If cpr is 0 or unset the target falls back to dmd directly (linear/unconfigured axis).
        // We invalidate whlpos[idx] now (the wheel is moving, not at a confirmed slot) and
        // stash the target in _selectSlot[idx]; the arrival handler publishes whlpos[idx] on
        // completion. This mirrors the embedded setting whlpos after MCx (with detent gating
        // for pupil masks) and makes the HCD's slot-based inPosition go true only on arrival,
        // not at receipt. The 1 Hz whlpos poll then reflects whlpos[idx] = dmd[idx].
        val axis = s.last
        val idx = axis - 'A'
        val wheelPos = newState.embeddedVars.getOrElse(s"dmd[$idx]", 0.0)
        val cpr      = newState.embeddedVars.getOrElse(s"cpr[$idx]", 0.0)
        val speed    = newState.embeddedVars.getOrElse(s"speed[$idx]", 10000.0)
        val demand   = if cpr > 0.0 then (wheelPos - 1.0) * (cpr / 8.0) else wheelPos

        println(s"[SIM] #Select$axis: wheel=$wheelPos, cpr=$cpr, target=$demand")

        val ax = getAxis(newState, axis).copy(
          demand   = demand,
          maxSpeed = speed,
          moving   = true,
          jogging  = false,
          motorOn  = true,
          stopCode = 0
        )
        newState = newState.copy(axes = newState.axes + (axis -> ax))
        newState = newState.copy(
          embeddedVars = newState.embeddedVars
            + (s"_axisThread[$idx]" -> thread.toDouble)
            + (s"whlpos[$idx]" -> -1.0)
            + (s"_selectSlot[$idx]" -> wheelPos)
        )
        ensureMotionTicking(timer)

      case _ =>
        println(s"[SIM] Unknown program label: $label — completing immediately")
        scheduleThreadComplete(timer, thread, 30.millis)
    }

    (formatReply(None), newState)
  }

  // ========================================
  // HX — Halt execution
  // ========================================

  private def handleHX(
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand],
    cmdString: String
  ): (ByteString, SimState) = {
    val threadStr = cmdString.drop(2).trim
    val thread = if (threadStr.nonEmpty) threadStr.toInt else 0
    val newThreadStatus = state.threadStatus & ~(1 << thread)

    // HX is a forcible halt — the embedded program never reaches its success
    // path. Clear the associated axis's ae[idx] (mirrors the embedded
    // convention of "ae cleared on success only" — a halted program leaves
    // ae=1 on real hardware, but the HCD's checkAndInterrupt path follows up
    // with state changes that recover from this anyway, so it's reasonable
    // to clear here for cleaner simulator state).
    val threadAxisKey = s"_threadAxis[$thread]"
    val updatedVars = state.embeddedVars.get(threadAxisKey) match {
      case Some(idxDouble) =>
        val idx = idxDouble.toInt
        state.embeddedVars - threadAxisKey + (s"ae[$idx]" -> 0.0)
      case None =>
        state.embeddedVars
    }

    (formatReply(None), state.copy(
      threadStatus = newThreadStatus,
      embeddedVars = updatedVars
    ))
  }

  // ========================================
  // MG — Message/Query handler
  // ========================================

  /**
   * Handle MG command for querying system variables and embedded arrays.
   *
   * Supported patterns:
   *   MG _NO        → thread bitmask
   *   MG TIME       → system time counter
   *   MG _TDA       → axis A step position
   *   MG speed[0]   → embedded array variable
   *   MG @AN[n]     → analog input (simulated 2.5V)
   */
  private def handleMG(state: SimState, cmdString: String): String = {
    val args = cmdString.drop(3).trim

    // Compound: "MG arg1,arg2,arg3" returns space-separated values on one line.
    // Each arg goes through resolveMgArg independently. If a single arg contains
    // no comma, this still works — split returns a singleton sequence.
    if (args.contains(',')) {
      args.split(',').map(_.trim).map(arg => resolveMgArg(state, arg)).mkString(" ")
    } else {
      resolveMgArg(state, args)
    }
  }

  /**
   * Resolve a single MG sub-argument (not containing commas) to its formatted value.
   *
   * Supported patterns:
   *   _NO         → thread bitmask
   *   _XQ<n>      → per-thread state (1.0 if running, -1.0 if stopped)
   *   _TDx        → axis x step position
   *   _TPx        → axis x step position
   *   TIME        → system time counter
   *   _TM         → sample period (constant 1000.0)
   *   @AN[n]      → analog input (simulated 2.5V)
   *   ae[i], speed[i], etc. → embedded array variable
   *   <scalar>    → embedded scalar variable
   */
  private def resolveMgArg(state: SimState, arg: String): String = {
    arg match {
      case "_NO" =>
        f"${state.threadStatus.toDouble}%.4f"

      case s if s.startsWith("_XQ") && s.length > 3 =>
        // _XQ<n>: per-thread state. Real controller returns the line number
        // currently executing, or -1 if the thread is stopped. The HCD only
        // distinguishes "running" (>=0) from "stopped" (-1), so 1.0 is a fine
        // placeholder for "running" — we don't simulate per-line position.
        val threadStr = s.drop(3)
        scala.util.Try(threadStr.toInt).toOption match {
          case Some(thread) if thread >= 0 && thread <= 7 =>
            val active = (state.threadStatus & (1 << thread)) != 0
            if (active) "1.0000" else "-1.0000"
          case _ =>
            println(s"[SIM] MG: malformed _XQ query '$s'")
            "-1.0000"
        }

      case s if s.startsWith("_TD") =>
        val axis = s.last
        f"${getAxis(state, axis).position}%.4f"

      case s if s.startsWith("_TP") =>
        val axis = s.last
        f"${getAxis(state, axis).position}%.4f"

      case "TIME" =>
        f"${(System.currentTimeMillis() % 1000000).toDouble}%.4f"

      case s if s.startsWith("_TM") =>
        "1000.0000"

      case s if s.length == 4 && s.startsWith("_LD") =>
        // _LDx: per-axis Limit Disable setting.
        // 0 = both enabled, 1 = forward disabled, 2 = reverse disabled, 3 = both disabled.
        // The HCD reads this once at init via `readLimitConfig()` to seed
        // forwardLimitEnabled/reverseLimitEnabled on AxisState.
        // Default is 0 (both enabled). In normal operation, the embedded .dmc
        // explicitly writes `LDx=N` per axis at #Setup, so this default only
        // applies to degenerate test paths that bypass embedded setup.
        // Tests can override per-axis via `LDx=N` (handled by handleGenericAxisCmd).
        val axis = s.last
        val ld = getAxis(state, axis).settings.getOrElse("LD", 0.0)
        f"$ld%.4f"

      case s if s.length == 4 && s.startsWith("_PV") =>
        // _PV<x>: per-axis PVT FIFO free-slots count.  255 when empty, 0 when full.
        // The active (executing) segment is NOT counted as queued — only the
        // pending segments in `pvtFifo`.  Matches hw: `BT<x>` dequeues the head
        // into execution immediately, so a single segment + BT shows _PV=255 (queue
        // empty), not 254 (queue has one waiting to dequeue).  This is the value
        // the IS-side underrun detector compares against when freshSlots == 255
        // and the segment is still active.
        val axis = s.last
        val ax = getAxis(state, axis)
        f"${(PvtFifoCapacity - ax.pvtFifo.size).toDouble}%.4f"

      case s if s.length == 4 && s.startsWith("_BT") =>
        // _BT<x>: count of PVT segments executed since the most recent `BT<x>`.
        // Resets to 0 on each new `BT<x>` (matches hw — NOT cumulative across
        // BT invocations).  Drives the CS-side polling and is forwarded to IS
        // for diagnostic correlation with `TrackingSession.segmentsSubmitted`.
        val axis = s.last
        f"${getAxis(state, axis).btCounter.toDouble}%.4f"

      case s if s.startsWith("@AN") =>
        // Single channel (compound is now handled by the top-level split in handleMG)
        "2.5000"

      case s if s.contains('[') =>
        val value = state.embeddedVars.getOrElse(s, 0.0)
        f"$value%.4f"

      // Scalar variable lookup (e.g., MG tcon, MG version)
      case s if state.embeddedVars.contains(s) =>
        f"${state.embeddedVars(s)}%.4f"

      case other =>
        println(s"[SIM] MG: unknown query '$other'")
        "0.0000"
    }
  }

  // ========================================
  // Motor on/off, stop
  // ========================================

  private def handleSH(state: SimState, cmdString: String): (ByteString, SimState) = {
    val axis = cmdString.drop(2).headOption.getOrElse('A')
    val ax = getAxis(state, axis).copy(motorOn = true)
    (formatReply(None), state.copy(axes = state.axes + (axis -> ax)))
  }

  private def handleMO(
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand],
    cmdString: String
  ): (ByteString, SimState) = {
    val axis = cmdString.drop(2).headOption.getOrElse('A')
    // MO de-energizes the motor and aborts any motion in progress, including
    // any active PVT trajectory.  Drain the FIFO and clear tracking state in
    // the same operation — matches hw behavior of MO during PVT execution.
    val ax = getAxis(state, axis).copy(
      motorOn            = false,
      moving             = false,
      jogging            = false,
      tracking           = false,
      velocity           = 0.0,
      pvtActive          = None,
      pvtFifo            = Vector.empty,
      pvtSamplesElapsed  = 0L
    )
    var newState = state.copy(axes = state.axes + (axis -> ax))
    // Clear any move thread driving this axis
    val idx = axis - 'A'
    val moveThreadKey = s"_axisThread[$idx]"
    newState.embeddedVars.get(moveThreadKey).foreach { moveThreadNum =>
      val mt = moveThreadNum.toInt
      val clearedStatus = newState.threadStatus & ~(1 << mt)
      // Also clear ae[idx] — the leaked motion thread is being terminated
      // by MO; the embedded program never reaches its success path so we
      // mirror that by clearing ae here. Also remove _threadAxis[mt] so
      // it doesn't leak (completeThread won't run for this forcibly-killed
      // thread).
      newState = newState.copy(
        threadStatus = clearedStatus,
        embeddedVars = newState.embeddedVars
          - moveThreadKey
          - s"_threadAxis[$mt]"
          + (s"ae[$idx]" -> 0.0)
      )
    }
    (formatReply(None), newState)
  }

  private def handleST(
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand],
    cmdString: String
  ): (ByteString, SimState) = {
    val axisChars = cmdString.drop(2).trim
    val axesToStop = if (axisChars.isEmpty) ('A' to 'H').toSeq else axisChars.toSeq
    var newAxes = state.axes
    var newVars = state.embeddedVars
    var newThreadStatus = state.threadStatus
    for (axis <- axesToStop) {
      val ax = getAxis(state, axis)
      if (ax.moving || ax.jogging || ax.tracking) {
        // ST drains the PVT FIFO immediately and leaves the controller "ready
        // for next trajectory" (S64).  We clear pvtActive and pvtFifo, reset
        // the BT counter (next BT will start fresh), and exit tracking mode.
        // The motor stops with stopCode=4 to mirror the ST-initiated stop, same
        // as for PA/JG motion.
        val wasTracking = ax.tracking
        newAxes = newAxes + (axis -> ax.copy(
          moving             = false,
          jogging            = false,
          tracking           = false,
          velocity           = 0.0,
          stopCode           = 4,
          pvtActive          = None,
          pvtFifo            = Vector.empty,
          pvtSamplesElapsed  = 0L
        ))
        if (wasTracking) {
          println(s"[SIM] ST$axis: drained PVT FIFO (active=${ax.pvtActive.isDefined}, " +
                  s"queued=${ax.pvtFifo.size})")
        }
        // Clear the thread that was driving motion on this axis (only relevant
        // for PA/JG motion; PVT has no embedded thread)
        val idx = axis - 'A'
        val moveThreadKey = s"_axisThread[$idx]"
        newVars.get(moveThreadKey).foreach { moveThreadNum =>
          val mt = moveThreadNum.toInt
          println(s"[SIM] ST$axis: clearing leaked move thread $mt")
          newThreadStatus = newThreadStatus & ~(1 << mt)
          newVars = newVars - moveThreadKey
        }
      }
    }
    (formatReply(None), state.copy(axes = newAxes, embeddedVars = newVars, threadStatus = newThreadStatus))
  }

  // ========================================
  // BG — Begin motion (legacy direct command)
  // ========================================

  private def handleBG(
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand],
    cmdString: String
  ): (ByteString, SimState) = {
    val axis = cmdString.drop(2).headOption.getOrElse('A')
    val ax = getAxis(state, axis)

    val jogSpeed = ax.settings.getOrElse(JogSpeed, 0.0)
    if (jogSpeed != 0.0) {
      val newAx = ax.copy(
        moving = true, jogging = true, velocity = jogSpeed, stopCode = 0
      )
      ensureMotionTicking(timer)
      (formatReply(None), state.copy(axes = state.axes + (axis -> newAx)))
    } else {
      val target = ax.settings.getOrElse(AbsTarget, 0.0)
      val newAx = ax.copy(
        moving = true, jogging = false, demand = target, stopCode = 0
      )
      ensureMotionTicking(timer)
      (formatReply(None), state.copy(axes = state.axes + (axis -> newAx)))
    }
  }

  // ========================================
  // JG — Jog speed
  // ========================================

  private def handleJG(state: SimState, cmdString: String): (ByteString, SimState) = {
    if (cmdString.length < 3) return (formatReply(None), state)
    val axis = cmdString(2)
    val rest = cmdString.drop(3)
    if (rest.startsWith("=?") || rest == "?") {
      val speed = getAxis(state, axis).settings.getOrElse(JogSpeed, 0.0)
      (formatReply(f"$speed%.4f"), state)
    } else {
      val value = rest.stripPrefix("=").toDouble
      val ax = getAxis(state, axis)
      val newAx = ax.copy(settings = ax.settings + (JogSpeed -> value))
      (formatReply(None), state.copy(axes = state.axes + (axis -> newAx)))
    }
  }

  // ========================================
  // PV<axis>=ΔP,V,T  — PVT segment write (Session 65)
  // ========================================

  /**
   * Append one PVT segment to the per-axis FIFO.
   *
   * Wire form: `PV<axis>=ΔP,V,T` — the third letter is the axis designator (the
   * key wire-format lesson from S64).  ΔP and V are signed integers; T is a
   * positive integer count of `_TM` sample periods.
   *
   * Behavior:
   *   - `(0, 0, 0)` is the active end-of-trajectory marker.  Real hw accepts it
   *     into the FIFO and, when dequeued for execution, stops the trajectory and
   *     discards any trailing queued segments.  We honor this by queuing it
   *     normally; `advancePvtAxis` handles the truncation when it dequeues
   *     the terminator.
   *   - FIFO overflow (depth already == capacity) is rejected with `?`.  Real
   *     hw behavior is to drop the segment; we treat it as a command error so
   *     misbehaving clients are surfaced rather than silently misbehaving.
   *   - Parse failure (non-numeric or wrong arity) also returns `?`.
   *
   * Storage only — does NOT begin motion.  `BT<axis>` is the trigger.  This
   * matches hw: segments accumulate in the FIFO until BT is issued.
   */
  private def handlePvtSegment(state: SimState, cmdString: String): (ByteString, SimState) = {
    val axis = cmdString(2)
    val args = cmdString.drop(4).trim   // skip "PV<x>="
    val parts = args.split(",").map(_.trim)
    if (parts.length != 3) {
      println(s"[SIM] PV$axis: malformed segment (expected 3 args, got ${parts.length}): '$args'")
      return (formatReply(None, isError = true), state)
    }
    val parsed = try {
      Some(PvtSegment(parts(0).toLong, parts(1).toLong, parts(2).toLong))
    } catch {
      case _: NumberFormatException =>
        println(s"[SIM] PV$axis: non-numeric segment args: '$args'")
        None
    }
    parsed match {
      case None =>
        (formatReply(None, isError = true), state)
      case Some(seg) =>
        val ax = getAxis(state, axis)
        if (ax.pvtFifo.size >= PvtFifoCapacity) {
          println(s"[SIM] PV$axis: FIFO full (${ax.pvtFifo.size}/$PvtFifoCapacity) — rejecting")
          return (formatReply(None, isError = true), state)
        }
        // Guard: T must be non-negative (T==0 only valid as part of the 0,0,0 terminator).
        // The HCD's handleTrackAxis guards against tSamples < 1 except for the terminator
        // case, so any (0, 0, 0) reaching us is intentional.
        if (seg.tSamples < 0) {
          println(s"[SIM] PV$axis: negative T=${seg.tSamples} rejected")
          return (formatReply(None, isError = true), state)
        }
        val newAx = ax.copy(pvtFifo = ax.pvtFifo :+ seg)
        println(s"[SIM] PV$axis: queued segment ΔP=${seg.deltaP} V=${seg.vEnd} T=${seg.tSamples} " +
                s"(depth ${newAx.pvtFifo.size}/$PvtFifoCapacity, free ${PvtFifoCapacity - newAx.pvtFifo.size})")
        (formatReply(None), state.copy(axes = state.axes + (axis -> newAx)))
    }
  }

  // ========================================
  // BT<axis> — Begin Trajectory (Session 65)
  // ========================================

  /**
   * Begin PVT trajectory execution for the named axis.
   *
   * Real hw: `BT<x>` starts consuming queued segments on axis x.  Bare `BT`
   * (no suffix) begins all axes with pending segments — we don't currently
   * support that form since the HCD never uses it.
   *
   * Semantics on receipt:
   *   - Reset `_BT<x>` counter to 0 (segments-since-last-BT).
   *   - Mark axis `tracking = true` and `moving = true` (so QR status word
   *     reflects motion in progress).
   *   - Turn motor on implicitly (matches hw — PVT motion does not require
   *     a separate SH; the FIFO segments drive the servo loop).
   *   - Dequeue the first segment into `pvtActive` and stamp the start
   *     position/velocity so the velocity ramp has a reference.
   *   - Start the motion timer.
   *
   * BT issued while already tracking is permitted but unusual; we re-arm
   * (reset counter, re-dequeue if FIFO has segments) without disrupting any
   * currently-executing segment.  Matches hw's "BT begins execution of
   * whatever's in the FIFO" semantics.
   */
  private def handleBT(
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand],
    cmdString: String
  ): (ByteString, SimState) = {
    val axis = cmdString(2)
    val ax = getAxis(state, axis)
    println(s"[SIM] BT$axis: begin trajectory (FIFO depth=${ax.pvtFifo.size}, " +
            s"active=${ax.pvtActive.isDefined}, motorOn=${ax.motorOn})")

    // If a segment is already actively executing (re-BT during a session), leave
    // it alone; just reset the counter.  Otherwise dequeue the first segment.
    val (newActive, newFifo, newStartPos, newStartVel, newSamplesElapsed) =
      if (ax.pvtActive.isDefined) {
        (ax.pvtActive, ax.pvtFifo, ax.pvtSegStartPos, ax.pvtSegStartVel, ax.pvtSamplesElapsed)
      } else if (ax.pvtFifo.nonEmpty) {
        val (head, tail) = (ax.pvtFifo.head, ax.pvtFifo.tail)
        (Some(head), tail, ax.position, ax.velocity, 0L)
      } else {
        // BT with empty FIFO — hw still arms the system but motion only begins
        // once a segment arrives.  We mirror by marking tracking and waiting.
        (None, ax.pvtFifo, ax.position, ax.velocity, 0L)
      }

    val newAx = ax.copy(
      pvtActive          = newActive,
      pvtFifo            = newFifo,
      pvtSegStartPos     = newStartPos,
      pvtSegStartVel     = newStartVel,
      pvtSamplesElapsed  = newSamplesElapsed,
      btCounter          = 0,
      tracking           = true,
      moving             = newActive.isDefined,
      jogging            = false,
      motorOn            = true,
      stopCode           = 0
    )
    // Start the motion timer.  Even if FIFO is empty at the moment of BT, we
    // want the tick to fire so a segment arriving later via PV<x>= can be
    // dequeued by advancePvtAxis on the next tick.  ensureMotionTicking is
    // idempotent (startTimerAtFixedRate with the same key replaces).
    ensureMotionTicking(timer)
    (formatReply(None), state.copy(axes = state.axes + (axis -> newAx)))
  }

  // ========================================
  // DP — Define position
  // ========================================

  private def handleDP(state: SimState, cmdString: String): (ByteString, SimState) = {
    if (cmdString.length < 4) return (formatReply(None), state)
    val axis = cmdString(2)
    val value = cmdString.drop(3).stripPrefix("=").toDouble
    val ax = getAxis(state, axis).copy(position = value)
    (formatReply(None), state.copy(axes = state.axes + (axis -> ax)))
  }

  // ========================================
  // Generic axis commands (SP, AC, DC, MT, etc.)
  // ========================================

  private def handleGenericAxisCmd(state: SimState, cmdString: String): (ByteString, SimState) = {
    val cmd = cmdString.take(2)
    val axis = cmdString(2)
    val rest = cmdString.drop(3)

    if (rest == "?" || rest == "=?") {
      val ax = getAxis(state, axis)
      val value = cmd match {
        case `MotorSpeed`   => ax.maxSpeed
        case `Acceleration` => ax.acceleration
        case `Deceleration` => ax.deceleration
        case `MotorType`    => ax.motorType
        case _              => ax.settings.getOrElse(cmd, 0.0)
      }
      (formatReply(f"$value%.4f"), state)
    } else {
      val value = rest.stripPrefix("=").toDouble
      val ax = getAxis(state, axis)
      val newAx = cmd match {
        case `MotorSpeed`   => ax.copy(maxSpeed = value)
        case `Acceleration` => ax.copy(acceleration = value)
        case `Deceleration` => ax.copy(deceleration = value)
        case `MotorType`    => ax.copy(motorType = value)
        case _              => ax.copy(settings = ax.settings + (cmd -> value))
      }
      (formatReply(None), state.copy(axes = state.axes + (axis -> newAx)))
    }
  }

  // ========================================
  // Embedded variable assignment: name[idx]=value
  // ========================================

  private def handleVarAssignment(state: SimState, cmdString: String): (ByteString, SimState) = {
    val eqIdx = cmdString.indexOf('=')
    val varName = cmdString.substring(0, eqIdx).trim
    val value = cmdString.substring(eqIdx + 1).trim.toDouble
    val newVars = state.embeddedVars + (varName -> value)
    (formatReply(None), state.copy(embeddedVars = newVars))
  }

  // ========================================
  // TC — Error code
  // ========================================

  private def handleTC(state: SimState, cmdString: String): String = {
    val n = cmdString.drop(2).trim
    if (n == "0" || n.isEmpty) s"${state.errorStatus}"
    else if (state.errorStatus == 0) "0"
    else s"${state.errorStatus} Unrecognized command"
  }

  // ========================================
  // Tell-query helpers (TP, TD, TV, SC, TS)
  // ========================================

  /**
   * Handle a "tell" query command (TP, TD, TV, SC, TS).
   *
   * If an axis letter follows the command (e.g. "TPA"), returns a single value.
   * If no axis letter follows (e.g. "TP"), returns comma-separated values for all
   * simulated axes — matching real Galil controller behavior.
   */
  private def handleTellQuery(
    state: SimState,
    cmdString: String,
    extract: SimAxis => String
  ): (ByteString, SimState) = {
    val rest = cmdString.drop(2).trim
    if (rest.nonEmpty && rest.head.isLetter) {
      // Single axis: TPA, TDB, TVA, etc.
      val axis = rest.head
      (formatReply(extract(getAxis(state, axis))), state)
    } else {
      // All axes: TP, TD, TV, etc. → "val, val, val, val"
      val values = SimulatedAxes.map(c => extract(getAxis(state, c)))
      (formatReply(values.mkString(", ")), state)
    }
  }

  /**
   * Compute the switches byte for an axis (used by TS and QR DataRecord).
   *
   * Per DMC-500x0 / DMC-4080 manual (QR DataRecord layout):
   *   Bit 3: Forward Limit switch INACTIVE (1 = OK to move +, 0 = limit hit)
   *   Bit 2: Reverse Limit switch INACTIVE (1 = OK to move -, 0 = limit hit)
   *   Bit 1: State of Home Input — repurposed here as "axis has been homed"
   *   Bit 0: Stepper Mode (1 = stepper motor, motorType >= 2.0)
   *
   * Bits 3 and 2 default to 1 (limit clear) — the simulator does not model a
   * physical limit by default. Tests can set forwardLimitHit/reverseLimitHit
   * on a SimAxis to flip the corresponding bit to 0 ("limit hit").
   */
  private def switchesByte(ax: SimAxis): Int = {
    var sw: Int = 0
    if (ax.motorType >= 2.0) sw |= (1 << 0)
    if (ax.homed) sw |= (1 << 1)
    if (!ax.reverseLimitHit) sw |= (1 << 2)  // bit set = limit clear
    if (!ax.forwardLimitHit) sw |= (1 << 3)  // bit set = limit clear
    sw
  }

  // ========================================
  // LV — List Variables (names and values)
  // ========================================

  /**
   * List all embedded variables with their current values.
   * Output format matches real Galil controller:
   *   name= value
   */
  private def handleLV(state: SimState): (ByteString, SimState) = {
    // LV lists only scalar (non-array) variables — exclude anything with "["
    val scalars = state.embeddedVars.filter { case (name, _) => !name.contains('[') }
    if (scalars.isEmpty) {
      (formatReply(None), state)
    } else {
      val lines = scalars.toSeq.sortBy(_._1).map { case (name, value) =>
        f"$name= $value%.4f"
      }
      (formatReply(lines.mkString("\r\n")), state)
    }
  }

  // ========================================
  // LA — List Arrays (names and dimensions)
  // ========================================

  /**
   * List all embedded array names with their dimensions.
   * Groups variables by base name and reports the max index + 1 as dimension.
   * Output format matches real Galil controller:
   *   basename[dim]
   */
  private def handleLA(state: SimState): (ByteString, SimState) = {
    // Parse "name[idx]" patterns, group by base name, find max index
    val arrayPattern = """^(.+)\[(\d+)\]$""".r
    val arrays = state.embeddedVars.keys.collect {
      case arrayPattern(base, idx) => (base, idx.toInt)
    }.groupBy(_._1).map { case (base, entries) =>
      val maxIdx = entries.map(_._2).max
      (base, maxIdx + 1)
    }

    if (arrays.isEmpty) {
      (formatReply(None), state)
    } else {
      val lines = arrays.toSeq.sortBy(_._1).map { case (name, dim) =>
        s"$name[$dim]"
      }
      (formatReply(lines.mkString("\r\n")), state)
    }
  }

  // ========================================
  // Motion simulation
  // ========================================

  /**
   * Advance all moving axes by one tick interval.
   *
   * For position moves (PA/dmd), moves toward demand at configured speed.
   * For jog moves (JG/tracking), moves continuously at jog velocity.
   * For PVT (tracking via `BT<x>`), executes the active segment with a linear
   * velocity ramp from segment-start velocity to segment-end velocity, dequeuing
   * the next FIFO segment when the active one completes.
   *
   * When a position move reaches its target:
   *   - Sets moving=false, velocity=0, stopCode=1
   *   - Clears the axis's thread (if one was assigned via _axisThread)
   */
  private def advanceMotion(
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand]
  ): Behavior[GalilSimulatorCommand] = {
    val dt = MotionTickIntervalMs / 1000.0
    var newState = state
    var anyMoving = false

    for ((axisChar, ax) <- state.axes if ax.moving || ax.jogging || ax.tracking) {
      if (ax.tracking) {
        // PVT execution branch — independent of PA/JG paths.  The `moving` flag
        // is true while a segment is active so QR status word bit 15 is correct,
        // but the motion math is driven entirely by the segment ramp, not by
        // demand-vs-position.
        val (updatedAx, stillMoving) = advancePvtAxis(ax)
        newState = newState.copy(axes = newState.axes + (axisChar -> updatedAx))
        if (stillMoving) anyMoving = true
      } else if (ax.jogging) {
        // Jog mode — move at constant velocity
        val newPos = ax.position + ax.velocity * dt
        val newAx = ax.copy(position = newPos)
        newState = newState.copy(axes = newState.axes + (axisChar -> newAx))
        anyMoving = true
      } else if (ax.moving) {
        // Position mode — move toward demand
        val distance = ax.demand - ax.position
        val absDistance = Math.abs(distance)

        if (absDistance <= SnapThreshold) {
          // Arrived at target
          val idx = axisChar - 'A'
          val threadKey = s"_axisThread[$idx]"
          val maybeThread = newState.embeddedVars.get(threadKey).map(_.toInt)

          println(s"[SIM] ARRIVED: axis $axisChar at ${ax.demand} (was ${ax.position}), thread=${maybeThread.getOrElse("none")}")

          val newAx = ax.copy(
            position = ax.demand,
            velocity = 0.0,
            moving = false,
            stopCode = 1
          )
          newState = newState.copy(axes = newState.axes + (axisChar -> newAx))
          // Clear ae[idx]=0 here (success path) and remove _axisThread.
          // Both happen here rather than in completeThread because advanceMotion
          // removes _axisThread before completeThread runs, so the reverse
          // lookup in completeThread would otherwise miss this case.
          newState = newState.copy(embeddedVars =
            newState.embeddedVars - threadKey + (s"ae[$idx]" -> 0.0)
          )

          // If this completed move was a #Select, publish the achieved slot into
          // whlpos[idx] now (mirrors the embedded setting whlpos after MCx + detent).
          // The #Select handler leaves a _selectSlot[idx] marker; non-select moves have
          // none and leave whlpos untouched. This makes the HCD's slot-based inPosition
          // (wheelPosition == commandedWheelPosition) go true only on arrival.
          newState.embeddedVars.get(s"_selectSlot[$idx]").foreach { slot =>
            newState = newState.copy(embeddedVars =
              newState.embeddedVars - s"_selectSlot[$idx]" + (s"whlpos[$idx]" -> slot)
            )
          }

          // Schedule thread completion — simulates MC + EN + optional mdelay
          maybeThread.foreach { thread =>
            val mdelay = newState.embeddedVars.getOrElse(s"mdelay[$idx]", 0.0)
            scheduleThreadComplete(timer, thread, ProgramCompleteDelay + mdelay.toLong.millis)
          }
        } else {
          // Move toward target, limited by maxSpeed
          val maxStep = ax.maxSpeed * dt
          val step = Math.min(maxStep, absDistance)
          val direction = Math.signum(distance)
          val newPos = ax.position + direction * step
          val currentVelocity = direction * Math.min(ax.maxSpeed, absDistance / dt)

          val newAx = ax.copy(position = newPos, velocity = currentVelocity)
          newState = newState.copy(axes = newState.axes + (axisChar -> newAx))
          anyMoving = true
        }
      }
    }

    if (!anyMoving) {
      timer.cancel(MotionTickKey)
    }

    simulate(timer, newState)
  }

  /**
   * Advance a single PVT-tracking axis by one motion tick.
   *
   * Time accounting: one motion tick is `MotionTickIntervalMs` of wall time.
   * In controller-sample units that's `(MotionTickIntervalMs * 1000) / _TM`
   * samples per tick.  We use the hardcoded `_TM = 1000 µs` (matches what `MG _TM`
   * returns above; both lab and STB run at 1 kHz servo) so one tick = 10 samples.
   *
   * Linear-velocity-ramp model: within an active segment of duration `T_samples`
   * the velocity ramps linearly from `pvtSegStartVel` to `segment.vEnd`.  Position
   * is the integral, evaluated at the current samples-elapsed.  The math:
   *
   *   frac        = pvtSamplesElapsed / segment.tSamples           ∈ [0, 1]
   *   v(frac)     = pvtSegStartVel + (segment.vEnd - pvtSegStartVel) * frac     (counts/sec)
   *   ∫₀^frac     = pvtSegStartVel * (frac * T_sec)
   *                 + (segment.vEnd - pvtSegStartVel) * (frac² / 2) * T_sec    (counts)
   *
   * where `T_sec = segment.tSamples * _TM / 1e6`.  When frac == 1, the integral
   * equals `((pvtSegStartVel + segment.vEnd) / 2) * T_sec` which matches the
   * caller-supplied segment ΔP only if the HCD's PVA write picked V consistent
   * with ΔP/T.  We do NOT try to force the integral to exactly ΔP — that would
   * mask HCD bugs where V and ΔP disagree.  Position is computed from the ramp;
   * segment-end position is committed at completion.
   *
   * Returns (updated SimAxis, stillMoving) where stillMoving=true while either a
   * segment is active OR the FIFO has more segments to dequeue.
   */
  private def advancePvtAxis(ax: SimAxis): (SimAxis, Boolean) = {
    val samplePeriodMicros = 1000L  // _TM = 1000 µs — matches resolveMgArg's "_TM" branch
    val samplesPerTick = (MotionTickIntervalMs.toLong * 1000L) / samplePeriodMicros

    ax.pvtActive match {
      case None =>
        // Tracking-armed but no active segment.  Try to dequeue from FIFO; if
        // empty, we're underrun (silent — no error code, just stop moving).
        if (ax.pvtFifo.isEmpty) {
          // Silent underrun: motor stops cleanly.  Real hw stays "tracking-armed"
          // (ready for a fresh PV+BT), but for the simulator we clear `tracking`
          // here so the motion-tick loop doesn't re-fire this branch every tick
          // (avoiding log spam and wasted work).  Re-issuing PV<x>+BT<x> after
          // underrun still works because BT unconditionally re-arms tracking.
          println(s"[SIM] PVT underrun (no segments, no terminator) — motor stopped")
          (ax.copy(moving = false, tracking = false, velocity = 0.0, stopCode = 1), false)
        } else {
          // Dequeue and start the next segment without advancing time this tick —
          // simpler than partial-tick handling.  The first sample lands next tick.
          val (head, tail) = (ax.pvtFifo.head, ax.pvtFifo.tail)
          val started = ax.copy(
            pvtActive          = Some(head),
            pvtFifo            = tail,
            pvtSegStartPos     = ax.position,
            pvtSegStartVel     = ax.velocity,
            pvtSamplesElapsed  = 0L,
            moving             = true,    // segment now executing — surface in QR
            stopCode           = 0
          )
          (started, true)
        }

      case Some(seg) =>
        // Terminator (0, 0, 0): on dequeue, drain trailing FIFO and stop the
        // trajectory.  Real hw: PVA=0,0,0 is the active end-of-trajectory marker
        // — segments AFTER it are discarded (`_BT` counts the terminator as one
        // executed segment per S64 line 1036).
        if (seg.deltaP == 0L && seg.vEnd == 0L && seg.tSamples == 0L) {
          println(s"[SIM] PVT terminator dequeued — stopping trajectory, " +
                  s"discarding ${ax.pvtFifo.size} trailing segments")
          return (ax.copy(
            pvtActive    = None,
            pvtFifo      = Vector.empty,  // discard trailing segments
            btCounter    = ax.btCounter + 1,  // terminator counts as executed
            tracking     = false,
            moving       = false,
            velocity     = 0.0,
            stopCode     = 1
          ), false)
        }

        // Advance within the active segment by `samplesPerTick` (or fewer if we
        // would overshoot the segment boundary).
        val remainingInSeg = seg.tSamples - ax.pvtSamplesElapsed
        val advance = math.min(samplesPerTick, remainingInSeg)
        val newElapsed = ax.pvtSamplesElapsed + advance

        if (newElapsed >= seg.tSamples) {
          // Segment completes this tick.  Commit segment-end position/velocity
          // exactly so accumulated rounding error doesn't drift.  segmentEndPos
          // = pvtSegStartPos + deltaP.
          //
          // Inter-segment gap: we clear pvtActive here and let the NEXT tick
          // dequeue the next segment (via the `None` branch above).  This
          // introduces a one-tick (10 ms) gap between segments where velocity
          // holds at endVel and position holds at endPos.  For the HCD's typical
          // ~1 Hz cadence with ~1000 ms segments this is negligible (1% of segment
          // duration); we don't try to handle the cross-segment continuity
          // partial-tick case here because doing so doesn't change correctness
          // of the FIFO accounting (which is what IS-side underrun detection and
          // _BT monitoring care about).
          val endPos = ax.pvtSegStartPos + seg.deltaP.toDouble
          val endVel = seg.vEnd.toDouble
          val nextBt = ax.btCounter + 1
          println(s"[SIM] PVT seg complete: ΔP=${seg.deltaP} V=${seg.vEnd} T=${seg.tSamples} " +
                  s"end pos=${endPos.toLong} vel=${endVel.toLong}, _BT=$nextBt, " +
                  s"FIFO depth=${ax.pvtFifo.size}")
          // Snap to the segment endpoint and clear active.  Next tick will try
          // to dequeue the next FIFO segment via the None branch above.
          val updated = ax.copy(
            position           = endPos,
            velocity           = endVel,
            pvtActive          = None,
            pvtSegStartPos     = endPos,
            pvtSegStartVel     = endVel,
            pvtSamplesElapsed  = 0L,
            btCounter          = nextBt
          )
          (updated, true)  // stillMoving — next tick will check FIFO for more
        } else {
          // Mid-segment advance: linear velocity ramp, position from integral.
          val frac = newElapsed.toDouble / seg.tSamples.toDouble
          val v0 = ax.pvtSegStartVel
          val v1 = seg.vEnd.toDouble
          val tSec = seg.tSamples.toDouble * samplePeriodMicros.toDouble / 1_000_000.0
          val currentVel = v0 + (v1 - v0) * frac
          // Position integral from segment start:
          //   ∫₀^frac (v0 + (v1-v0)·u) du · tSec = (v0·frac + (v1-v0)·frac²/2) · tSec
          val posDelta = (v0 * frac + (v1 - v0) * frac * frac / 2.0) * tSec
          val newPos = ax.pvtSegStartPos + posDelta
          (ax.copy(
            position           = newPos,
            velocity           = currentVel,
            pvtSamplesElapsed  = newElapsed
          ), true)
        }
    }
  }


  // ========================================
  // Thread completion
  // ========================================

  private def completeThread(
    state: SimState,
    timer: TimerScheduler[GalilSimulatorCommand],
    thread: Int
  ): Behavior[GalilSimulatorCommand] = {
    val newThreadStatus = state.threadStatus & ~(1 << thread)
    println(s"[SIM] Thread $thread completed (_NO: 0x${state.threadStatus.toHexString} → 0x${newThreadStatus.toHexString})")

    // Direct lookup: _threadAxis[N] tells us which axis this thread was
    // associated with (set in handleXQ for axis-affecting labels). Clear
    // ae[idx] back to 0 to mirror the embedded program success path, and
    // remove the now-stale _threadAxis entry.
    val threadAxisKey = s"_threadAxis[$thread]"
    val updatedVars = state.embeddedVars.get(threadAxisKey) match {
      case Some(idxDouble) =>
        val idx = idxDouble.toInt
        state.embeddedVars - threadAxisKey + (s"ae[$idx]" -> 0.0)
      case None =>
        state.embeddedVars
    }

    simulate(timer, state.copy(
      threadStatus = newThreadStatus,
      embeddedVars = updatedVars
    ))
  }

  // ========================================
  // Embedded variable initialization (#Init)
  // ========================================

  /**
   * Initialize default embedded variable values, mimicking what #Init does
   * on real hardware. These are the EEPROM defaults that the HCD reads
   * via readMotionConfig() during initialization.
   */
  private def initializeEmbeddedVars(state: SimState): SimState = {
    val defaults = Map(
      "speed[0]"  -> 10000.0,
      "accel[0]"  -> 256000.0,
      "decel[0]"  -> 256000.0,
      "hspd[0]"   -> 5000.0,
      "hoff[0]"   -> 0.0,
      "mdelay[0]" -> 0.0,
      "dmd[0]"    -> 0.0,
      "speed[1]"  -> 10000.0,
      "accel[1]"  -> 256000.0,
      "decel[1]"  -> 256000.0,
      "hspd[1]"   -> 5000.0,
      "hoff[1]"   -> 0.0,
      "mdelay[1]" -> 0.0,
      "dmd[1]"    -> 0.0,
      "Atarget[0]" -> 0.0,
      "Atarget[1]" -> 0.0,
      "Btarget[0]" -> 0.0,
      "Btarget[1]" -> 0.0,
      // Achieved wheel slot per axis; -1 = unknown (no successful select yet). The HCD
      // polls MG whlpos[idx] for its configured axes, and the generic MG array path
      // defaults unseeded vars to 0.0 — so seed all 8 to -1.0 to preserve the -1 sentinel
      // (matches AxisState.wheelPosition). A #Select overwrites whlpos[idx] with its slot.
      "whlpos[0]" -> -1.0, "whlpos[1]" -> -1.0, "whlpos[2]" -> -1.0, "whlpos[3]" -> -1.0,
      "whlpos[4]" -> -1.0, "whlpos[5]" -> -1.0, "whlpos[6]" -> -1.0, "whlpos[7]" -> -1.0
    )
    state.copy(embeddedVars = state.embeddedVars ++ defaults)
  }

  // ========================================
  // QR DataRecord generation
  // ========================================

  /**
   * Build a complete QR DataRecord from current simulated state.
   *
   * The DataRecord includes:
   *   - Header with blocks present (S, T, I, A, B, C, D for 4-axis)
   *   - GeneralState with threadStatus, I/O, error code
   *   - Per-axis GalilAxisStatus with proper bit fields:
   *     - status word bit 15 = moving, bit 0 = motorOff, bit 7 = negativeDir
   *     - switches byte bit 0 = stepperMode (1 for stepper)
   *     - auxiliaryPosition = current position (for steppers)
   *     - velocity = currentVelocity * 64 (Galil encoding)
   */
  private def buildDataRecord(state: SimState): DataRecord = {
    val blocksPresent = List('S', 'T', 'I') ++ SimulatedAxes
    val header = Header(blocksPresent.map(_.toString))

    val threadStatusByte = (state.threadStatus & 0xFF).toByte

    val generalState = GeneralState(
      sampleNumber = state.sampleNumber,
      inputs = state.digitalInputs,
      outputs = state.digitalOutputs,
      ethernetHandleStatus = Array.fill(8)(0.toByte),
      errorCode = state.errorStatus.toByte,
      threadStatus = threadStatusByte,
      amplifierStatus = 0,
      contourModeSegmentCount = 0,
      contourModeBufferSpaceRemaining = 0.toShort,
      sPlaneSegmentCount = 0.toShort,
      sPlaneMoveStatus = 0.toShort,
      sPlaneDistanceTraveled = 0,
      sPlaneBufferSpaceRemaining = 0.toShort,
      tPlaneSegmentCount = 0.toShort,
      tPlaneMoveStatus = 0.toShort,
      tPlaneDistanceTraveled = 0,
      tPlaneBufferSpaceRemaining = 0.toShort
    )

    val axisChars = blocksPresent.filter(c => DataRecord.axes.contains(c))
    val axisStatuses = axisChars.map(buildAxisStatus(state, _)).toArray

    DataRecord(header, generalState, axisStatuses)
  }

  /**
   * Build GalilAxisStatus for one axis from simulated state.
   *
   * Status word bits (per DMC-500x0 User Manual):
   *   bit 15: Move in Progress
   *   bit 14: Mode of Motion PA or PR
   *   bit  7: Negative Direction Move
   *   bit  0: Motor Off
   *
   * Switches byte bits:
   *   bit 0: Stepper Mode (1 = stepper motor)
   */
  private def buildAxisStatus(state: SimState, axisChar: Char): GalilAxisStatus = {
    val ax = getAxis(state, axisChar)

    var statusWord: Int = 0
    // Bit 15 = Move in Progress.  For PVT we use `moving` (set by BT when a
    // segment is dequeued for execution and cleared on terminator/underrun/ST);
    // we do NOT use `tracking` alone, because tracking-armed-but-FIFO-empty is
    // not motion.  `moving || jogging` covers all motion sources.
    if (ax.moving || ax.jogging) statusWord |= (1 << 15)
    // Bit 14 = PA/PR mode of motion.  Only set for profiled position moves —
    // NOT for JG (`jogging`) and NOT for PVT (`tracking`).
    if (!ax.jogging && !ax.tracking && ax.moving) statusWord |= (1 << 14)
    if (ax.velocity < 0) statusWord |= (1 << 7)
    if (!ax.motorOn) statusWord |= (1 << 0)

    val sw = switchesByte(ax)

    val qrVelocity = (ax.velocity * 64.0).toInt
    val pos = ax.position.toInt

    GalilAxisStatus(
      status = statusWord.toShort,
      switches = sw.toByte,
      stopCode = ax.stopCode,
      referencePosition = pos,
      motorPosition = 0,
      positionError = 0,
      auxiliaryPosition = pos,
      velocity = qrVelocity,
      torque = 0,
      analogInput = 0,
      hallInputStatus = 0,
      reservedByte = 0,
      userDefinedVariable = 0
    )
  }

  // ========================================
  // Utilities
  // ========================================

  private def getAxis(state: SimState, axis: Char): SimAxis =
    state.axes.getOrElse(axis, SimAxis())

  private def ensureMotionTicking(timer: TimerScheduler[GalilSimulatorCommand]): Unit = {
    timer.startTimerAtFixedRate(MotionTickKey, MotionTick, MotionTickInterval)
  }

  /**
   * Schedule the completion of an embedded program on the given thread.
   *
   * CAUTION — timer key is per-thread: startSingleTimer with the same key
   * REPLACES a pending timer. If a second XQ lands on the same thread before
   * the previous program's ThreadComplete fires, that completion is silently
   * dropped (its ae[]/_threadAxis cleanup in completeThread never runs).
   * This cannot happen from HCD-driven traffic: the HCD's thread-reservation
   * gate (ControllerCommandActor.unobservedThreads) never re-XQs a thread
   * before its completion has been observed, which requires the ThreadComplete
   * to have fired. Direct/manual XQ traffic (REPL, tests bypassing the HCD)
   * could still trigger it.
   */
  private def scheduleThreadComplete(
    timer: TimerScheduler[GalilSimulatorCommand],
    thread: Int,
    delay: FiniteDuration
  ): Unit = {
    timer.startSingleTimer(s"thread-complete-$thread", ThreadComplete(thread), delay)
  }

  // ========================================
  // Reply formatting (Galil protocol)
  // ========================================

  def formatReply(reply: Option[String], isError: Boolean = false): ByteString = {
    val s =
      if (isError) "?"
      else reply match {
        case Some(msg) => s"$msg\r\n:"
        case None      => ":"
      }
    ByteString(s)
  }

  def formatReply(reply: String): ByteString = formatReply(Some(reply))
  def formatReply(reply: Int): ByteString = formatReply(Some(reply.toString))
}