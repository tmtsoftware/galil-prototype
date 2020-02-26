package csw.proto.galil.simulator

import akka.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import akka.util.ByteString
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState, Header}

import scala.concurrent.duration._

/**
  * An actor that simulates a Galil device (in a very simplified way).
  */
object GalilSimulatorActor {

  // Commands corresponding to the ones defined in GalilCommands.conf
  // (XXX TODO: Add missing commands)
  val AbsTarget = "PA"
  val Acceleration = "AC"
  val AmplifierGain = "AG"
  val AnalogFeedbackSelect = "AF"
  val BeginMotion = "BG"
  val BrushlessModulus = "BM"
  val BrushlessZero = "BZ"
  val Deceleration = "DC"
  val ErrorCode = "TC"
  val GetDataRecord = "QR"
  val GetMotorPosition = "RP"
  val JogSpeed = "JG"
  val LowCurrent = "LC"
  val MotorOff = "MO"
  val MotorOn = "SH"
  val MotorSmoothing = "KS"
  val MotorSpeed = "SP"
  val MotorType = "MT"
  val PositionTracking = "PT"
  val RelTarget = "PR"
  val SetMotorPosition = "DP"
  val StepDriveResolution = "YA"
  val StepMotorResolution = "YB"

  // Some commands that set a value for an axis (XXX TODO: Add to this list)
  private val axixCmds = Set(
    AbsTarget,
    Acceleration,
    AmplifierGain,
    AnalogFeedbackSelect,
    BrushlessModulus,
    BrushlessZero,
    Deceleration,
    JogSpeed,
    LowCurrent,
    MotorSmoothing,
    MotorSpeed,
    MotorType,
    PositionTracking,
    RelTarget,
    SetMotorPosition,
    StepDriveResolution,
    StepMotorResolution
  )

  // Commands received by this actor
  sealed trait GalilSimulatorCommand

  // A Galil command - response goes to the replyTo actor
  case class Command(cmd: String, replyTo: ActorRef[ByteString])
      extends GalilSimulatorCommand

  // Called periodically to simulate the motor motion for the given axis
  case class SimulateMotion(axis: Char) extends GalilSimulatorCommand

  // Holds the timer and current state for an axis
  case class AxisContext(motorOn: Boolean,
                         referencePosition: Int,
                         settings: Map[String, Double])

  // Holds the current state of each axis
  case class SimulatorContext(motionTimerKey: String, map: Map[Char, AxisContext])

  // For TC command
  private var errorStatus = 0
  private val errorMessage = "Unrecognized command"

  // Defines the actor behavior
  def simulate(timer: TimerScheduler[GalilSimulatorCommand], simCtx: SimulatorContext = SimulatorContext("motion-timer", Map.empty))
    : Behavior[GalilSimulatorCommand] = Behaviors.receive { (ctx, msg) =>
    msg match {
      case SimulateMotion(axis)  => simulateMotion(simCtx, timer, axis)
      case Command(cmd, replyTo) => processCommand(ctx, simCtx, timer, cmd, replyTo)
    }
  }

  // Process the Galil command and return the reply
  private def processCommand(
      ctx: ActorContext[GalilSimulatorCommand],
      simCtx: SimulatorContext,
      timer: TimerScheduler[GalilSimulatorCommand],
      cmdString: String,
      replyTo: ActorRef[ByteString]): Behavior[GalilSimulatorCommand] = {

    try {
      val (response, maybeNewBehavior) =
        cmdString.take(2) match {
          case `GetDataRecord` =>
            (ByteString(getDataRecord(simCtx).toByteBuffer), None)
          case `ErrorCode` => (formatReply(tcCmd(cmdString)), None)
          case `BeginMotion` =>
            (formatReply(None), Some(beginMotion(ctx, simCtx, timer, cmdString)))
          case `MotorOn` =>
            (formatReply(None), Some(motorOn(ctx, simCtx, timer, cmdString)))
          case `MotorOff` =>
            (formatReply(None), Some(motorOff(simCtx, timer, cmdString)))
          case `GetMotorPosition` =>
            (formatReply(getMotorPosition(simCtx, cmdString)), None)
          case `SetMotorPosition` =>
            (formatReply(None), Some(setMotorPosition(simCtx, timer, cmdString)))
          case cmd if axixCmds.contains(cmd) => genericCmd(simCtx, timer, cmdString)
          case _                             => (formatReply(None), None)
        }
      replyTo ! response
      maybeNewBehavior.getOrElse(Behaviors.same)
    } catch {
      case e: Exception =>
        e.printStackTrace()
        replyTo ! formatReply(None, isError = true)
        Behaviors.same
    }
  }

  // Simulate the motor motion based on the given commands
  private def simulateMotion(simCtx: SimulatorContext,
                             timer: TimerScheduler[GalilSimulatorCommand],
                             axis: Char): Behavior[GalilSimulatorCommand] = {
    val ac = getAxisContext(simCtx, axis)
    if (ac.motorOn) {
      val absTarget = ac.settings.getOrElse(AbsTarget, 0.0).toInt
      if (absTarget != ac.referencePosition) {
        println(
          s"XXX absTarget = $absTarget, ref pos = ${ac.referencePosition}")
        val steps = if (absTarget > ac.referencePosition) 1 else -1
        moveMotor(simCtx, timer, axis, ac, steps)
      } else {
        // At reference position, so stop moving the motor
        timer.cancel(simCtx.motionTimerKey)
        Behaviors.same
      }
    } else {
      // motor is off
      Behaviors.same
    }
  }

  // Move the motor by the given number of steps
  private def moveMotor(simCtx: SimulatorContext,
                        timer: TimerScheduler[GalilSimulatorCommand],
                        axis: Char,
                        ac: AxisContext,
                        steps: Int): Behavior[GalilSimulatorCommand] = {

    val ac = getAxisContext(simCtx, axis)
    val newAc = ac.copy(referencePosition = ac.referencePosition + steps)
    simulate(timer, SimulatorContext(simCtx.motionTimerKey, simCtx.map + (axis -> newAc)))
  }

  // Gets the context for the given Galil axis
  private def getAxisContext(simCtx: SimulatorContext,
                             axis: Char): AxisContext = {
    simCtx.map.getOrElse(
      axis,
      AxisContext(motorOn = false, referencePosition = 0, Map.empty))
  }

  // Simulates the TC command:
  private def tcCmd(cmdString: String): String = {
    val n = cmdString.drop(2)
    if (n == "0")
      s"$errorStatus"
    else if (errorStatus == 0)
      s"$errorStatus"
    else s"$errorStatus $errorMessage"
  }

  // Formats a reply from the Galil device.
  // From the Galil doc:
  // 2) Sending a Command
  // Once a socket is established, the user will need to send a Galil command as a string to
  // the controller (via the opened socket) followed by a Carriage return (0x0D).
  // 3) Receiving a Response
  // "The controller will respond to that command with a string. The response of the
  //command depends on which command was sent. In general, if there is a
  //response expected such as the "TP" Tell Position command. The response will
  //be in the form of the expected value(s) followed by a Carriage return (0x0D), Line
  //Feed (0x0A), and a Colon (:). If the command was rejected, the response will be
  //just a question mark (?) and nothing else. If the command is not expected to
  //return a value, the response will be just the Colon (:)."
  def formatReply(reply: Option[String],
                  isError: Boolean = false): ByteString = {
    errorStatus = if (isError) 1 else 0
    val s =
      if (isError) "?"
      else
        reply match {
          case Some(msg) => s"$msg\r\n:"
          case None      => ":"
        }
    ByteString(s)
  }

  def formatReply(reply: String): ByteString = formatReply(Some(reply))

  def formatReply(reply: Int): ByteString = formatReply(Some(reply.toString))

  // Simulates commands that let you set and get values, for example:
  //
  // PRA=?
  // PRA=1
  //
  // Command is the first two chars, axis should be the third.
  // If the next two chars are "=?", get the value, otehrwise set it.
  // Return value is the Galil response.
  private def genericCmd(simCtx: SimulatorContext, timer: TimerScheduler[GalilSimulatorCommand], cmdString: String)
    : (ByteString, Option[Behavior[GalilSimulatorCommand]]) = {
    val cmd = cmdString.take(2)
    val axis = cmdString.drop(2).head
    val value = cmdString.drop(4)
    value match {
      case "?" =>
        (formatReply(getAxisValue(simCtx, axis, cmd).toString), None)
      case _ =>
        (formatReply(None),
         Some(setAxisValue(simCtx, timer, axis, cmd, value.toDouble)))
    }
  }

  // Gets the reference position for the given axis
  private def getMotorPosition(simCtx: SimulatorContext,
                               cmdString: String): Int = {
    val axis = cmdString.drop(2).head
    val ac = getAxisContext(simCtx, axis)
    ac.referencePosition
  }

  // Sets the reference position for the given axis
  private def setMotorPosition(
      simCtx: SimulatorContext,
      timer: TimerScheduler[GalilSimulatorCommand],
      cmdString: String): Behavior[GalilSimulatorCommand] = {
    val axis = cmdString.drop(2).head
    val ac = getAxisContext(simCtx, axis)
    val value = cmdString.drop(4).toInt
    val newSettings = ac.settings + (SetMotorPosition -> value.toDouble)
    val newAc = ac.copy(referencePosition = value, settings = newSettings)
    simulate(timer, SimulatorContext(simCtx.motionTimerKey, simCtx.map + (axis -> newAc)))
  }

  // Turn the motor for the give axis on
  // ("... tells the controller to use the current motor position as the command position
  // and to enable servo control at the current position".)
  private def motorOn(ctx: ActorContext[GalilSimulatorCommand],
                      simCtx: SimulatorContext,
                      timer: TimerScheduler[GalilSimulatorCommand],
                      cmdString: String): Behavior[GalilSimulatorCommand] = {
    val axis = cmdString.drop(2).head
    val ac = getAxisContext(simCtx, axis)
    val newAc = ac.copy(motorOn = true)
    simulate(timer, SimulatorContext(simCtx.motionTimerKey, simCtx.map + (axis -> newAc)))
  }

  // Turn the motor for the give axis off
  private def motorOff(simCtx: SimulatorContext,
                       timer: TimerScheduler[GalilSimulatorCommand],
                       cmdString: String): Behavior[GalilSimulatorCommand] = {
    val axis = cmdString.drop(2).head
    val ac = getAxisContext(simCtx, axis)
    timer.cancel(simCtx.motionTimerKey)
    val newAc = ac.copy(motorOn = false)
    simulate(timer, SimulatorContext(simCtx.motionTimerKey, simCtx.map + (axis -> newAc)))
  }

  // Begin moving the motor for the give axis (Stop when it reaches the target)
  private def beginMotion(
      ctx: ActorContext[GalilSimulatorCommand],
      simCtx: SimulatorContext,
      timer: TimerScheduler[GalilSimulatorCommand],
      cmdString: String): Behavior[GalilSimulatorCommand] = {
    val axis = cmdString.drop(2).head
    val ac = getAxisContext(simCtx, axis)
    timer.cancel(simCtx.motionTimerKey)
    val motorSpeed =
      math.max(ac.settings.getOrElse(MotorSpeed, 25000.0), 1.0).toInt
    val delay = (1000000 / motorSpeed).microseconds
    timer.startTimerAtFixedRate(simCtx.motionTimerKey, SimulateMotion(axis), delay)
    simulate(timer, simCtx)
  }

  // Set the value for a given command on the given axis
  private def setAxisValue(simCtx: SimulatorContext,
                           timer: TimerScheduler[GalilSimulatorCommand],
                           axis: Char,
                           name: String,
                           value: Double): Behavior[GalilSimulatorCommand] = {
    val ac = getAxisContext(simCtx, axis)
    val newSettings = ac.settings + (name -> value)
    val newAc = ac.copy(settings = newSettings)
    simulate(timer, SimulatorContext(simCtx.motionTimerKey, simCtx.map + (axis -> newAc)))
  }

  // Gets the value for a given command on the given axis
  private def getAxisValue(simCtx: SimulatorContext,
                           axis: Char,
                           name: String): Double = {
    val ac = getAxisContext(simCtx, axis)
    ac.settings
      .getOrElse(name, 0) // XXX TODO: Use documented default values for each command
  }

  // Returns DataRecord based on current simulator context
  private def getDataRecord(simCtx: SimulatorContext): DataRecord = {
    val blocksPresent = DataRecord.allAxes.take(7)

    val header = Header(blocksPresent.map(_.toString))

    val sampleNumber = 28114.toShort
    val inputs = (0 to 9).map(_ => 0.toByte).toArray
    val outputs = (0 to 9).map(_ => 0.toByte).toArray
    val ethernetHandleStatus = DataRecord.axes.map(_ => 0.toByte).toArray
    val errorCode = this.errorStatus.toByte
    val threadStatus = 0.toByte
    val amplifierStatus = 0
    val contourModeSegmentCount = 0
    val contourModeBufferSpaceRemaining = 0.toShort
    val sPlaneSegmentCount = 0.toShort
    val sPlaneMoveStatus = 0.toShort
    val sPlaneDistanceTraveled = 0
    val sPlaneBufferSpaceRemaining = 0.toShort
    val tPlaneSegmentCount = 0.toShort
    val tPlaneMoveStatus = 0.toShort
    val tPlaneDistanceTraveled = 0
    val tPlaneBufferSpaceRemaining = 0.toShort

    val generalState = GeneralState(
      sampleNumber,
      inputs,
      outputs,
      ethernetHandleStatus,
      errorCode,
      threadStatus,
      amplifierStatus,
      contourModeSegmentCount,
      contourModeBufferSpaceRemaining,
      sPlaneSegmentCount,
      sPlaneMoveStatus,
      sPlaneDistanceTraveled,
      sPlaneBufferSpaceRemaining,
      tPlaneSegmentCount,
      tPlaneMoveStatus,
      tPlaneDistanceTraveled,
      tPlaneBufferSpaceRemaining
    )

    val axisStatuses = blocksPresent
      .drop(3)
      .map(
        axis =>
          GalilAxisStatus(
            referencePosition = getAxisContext(simCtx, axis).referencePosition))
      .toArray

    DataRecord(header, generalState, axisStatuses)
  }

}
