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
    settings: Map[String, Double] = Map.empty
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
   */
  case class SimState(
    axes: Map[Char, SimAxis] = Map.empty,
    threadStatus: Int = 0,
    embeddedVars: Map[String, Double] = Map.empty,
    sampleNumber: Short = 0,
    errorStatus: Int = 0,
    digitalOutputs: Array[Byte] = Array.fill(10)(0.toByte),
    digitalInputs: Array[Byte] = Array.fill(10)(0.toByte),
    programText: String = ""
  )

  // ========================================
  // Motion simulation constants
  // ========================================

  /** How often the motion simulation ticks (ms) */
  private val MotionTickIntervalMs = 10
  private val MotionTickInterval = MotionTickIntervalMs.milliseconds
  private val MotionTickKey = "motion-tick"

  /** Threshold below which we snap to target (counts) */
  private val SnapThreshold = 0.5

  /** Axes modeled by the simulator (4-axis DMC-500x0) */
  private val SimulatedAxes = Seq('A', 'B', 'C', 'D')

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

    val cmd2 = cmdString.take(2)

    cmd2 match {
      // ---- Identity ----
      case `Identify` =>
        val idResponse = "FW, DMC50040 Rev 1.2sim\r\nDMC, 50000, Rev 0\r\nCMB, 00000, 0.0v, Rev 0\r\nAMP1, 00000, Rev 0"
        (formatReply(idResponse), state)

      // ---- QR DataRecord (binary) ----
      case `GetDataRecord` =>
        // Log QR state when anything interesting is happening
        val anyMoving = state.axes.exists { case (_, ax) => ax.moving || ax.jogging }
        if (anyMoving || state.threadStatus != 0) {
          val axSummary = state.axes.toSeq.sortBy(_._1).map { case (c, ax) =>
            s"$c:pos=${ax.position.toInt},mov=${ax.moving},jog=${ax.jogging},mot=${ax.motorOn}"
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
      case `SetBit` => (formatReply(None), state)
      case `ClearBit` => (formatReply(None), state)
      case `AnalogOutput` => (formatReply(None), state)

      // ---- Upload program (download FROM controller, confusingly) ----
      case `UploadProgram` =>
        val programResponse = state.programText + "\\\r\n:"
        (ByteString(programResponse), state)

      // ---- Download program (upload TO controller) ----
      case `DownloadProgram` =>
        (formatReply(None), state)

      // ---- Jog speed (used by tracking) ----
      case `JogSpeed` =>
        handleJG(state, cmdString)

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
        scheduleThreadComplete(timer, thread, 50.millis)

      case s if s.startsWith("Home") =>
        val axis = s.last
        val ax = getAxis(newState, axis).copy(
          position = 0.0,
          motorOn = true,
          homed = true,
          stopCode = 1
        )
        newState = newState.copy(axes = newState.axes + (axis -> ax))
        scheduleThreadComplete(timer, thread, 80.millis)

      case s if s.startsWith("Move") =>
        val axis = s.last
        val idx = axis - 'A'
        val demand = newState.embeddedVars.getOrElse(s"dmd[$idx]", 0.0)
        val speed = newState.embeddedVars.getOrElse(s"speed[$idx]", 10000.0)
        val accel = newState.embeddedVars.getOrElse(s"accel[$idx]", 256000.0)
        val decel = newState.embeddedVars.getOrElse(s"decel[$idx]", 256000.0)

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
        scheduleThreadComplete(timer, thread, 100.millis)

      case s if s.startsWith("Stop") =>
        val axis = s.last
        val idx = axis - 'A'
        val ax = getAxis(newState, axis).copy(
          moving = false,
          jogging = false,
          velocity = 0.0,
          stopCode = 4
        )
        newState = newState.copy(axes = newState.axes + (axis -> ax))

        // Clear the thread that was driving motion on this axis (if any).
        // Without this, the move thread leaks forever since advanceMotion
        // will never reach the target to clear it naturally.
        val moveThreadKey = s"_axisThread[$idx]"
        newState.embeddedVars.get(moveThreadKey).foreach { moveThreadNum =>
          val mt = moveThreadNum.toInt
          println(s"[SIM] #Stop$axis: clearing leaked move thread $mt")
          val clearedStatus = newState.threadStatus & ~(1 << mt)
          newState = newState.copy(
            threadStatus = clearedStatus,
            embeddedVars = newState.embeddedVars - moveThreadKey
          )
        }
        scheduleThreadComplete(timer, thread, 50.millis)

      case s if s.startsWith("Select") =>
        // #SelectX: same as move — position to demand
        val axis = s.last
        val idx = axis - 'A'
        val demand = newState.embeddedVars.getOrElse(s"dmd[$idx]", 0.0)
        val speed = newState.embeddedVars.getOrElse(s"speed[$idx]", 10000.0)

        val ax = getAxis(newState, axis).copy(
          demand = demand,
          maxSpeed = speed,
          moving = true,
          jogging = false,
          motorOn = true,
          stopCode = 0
        )
        newState = newState.copy(axes = newState.axes + (axis -> ax))
        newState = newState.copy(
          embeddedVars = newState.embeddedVars + (s"_axisThread[$idx]" -> thread.toDouble)
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
    (formatReply(None), state.copy(threadStatus = newThreadStatus))
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

    args match {
      case "_NO" =>
        f"${state.threadStatus.toDouble}%.4f"

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

      case s if s.startsWith("@AN") =>
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
    val ax = getAxis(state, axis).copy(
      motorOn = false, moving = false, jogging = false, velocity = 0.0
    )
    var newState = state.copy(axes = state.axes + (axis -> ax))
    // Clear any move thread driving this axis
    val idx = axis - 'A'
    val moveThreadKey = s"_axisThread[$idx]"
    newState.embeddedVars.get(moveThreadKey).foreach { moveThreadNum =>
      val mt = moveThreadNum.toInt
      val clearedStatus = newState.threadStatus & ~(1 << mt)
      newState = newState.copy(
        threadStatus = clearedStatus,
        embeddedVars = newState.embeddedVars - moveThreadKey
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
      if (ax.moving || ax.jogging) {
        newAxes = newAxes + (axis -> ax.copy(
          moving = false, jogging = false, velocity = 0.0, stopCode = 4
        ))
        // Clear the thread that was driving motion on this axis
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
   * Bit 0: Stepper Mode (1 = stepper motor, motorType >= 2.0)
   * Bit 1: Home complete (1 = axis has been homed)
   */
  private def switchesByte(ax: SimAxis): Int = {
    var sw: Int = 0
    if (ax.motorType >= 2.0) sw |= (1 << 0)
    if (ax.homed) sw |= (1 << 1)
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

    for ((axisChar, ax) <- state.axes if ax.moving || ax.jogging) {
      if (ax.jogging) {
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
          newState = newState.copy(embeddedVars = newState.embeddedVars - threadKey)

          // Schedule thread completion — simulates MC + EN + optional mdelay
          maybeThread.foreach { thread =>
            val mdelay = newState.embeddedVars.getOrElse(s"mdelay[$idx]", 0.0)
            scheduleThreadComplete(timer, thread, (20 + mdelay.toLong).millis)
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
    simulate(timer, state.copy(threadStatus = newThreadStatus))
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
      "Btarget[1]" -> 0.0
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
    if (ax.moving || ax.jogging) statusWord |= (1 << 15)
    if (!ax.jogging && ax.moving) statusWord |= (1 << 14)
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