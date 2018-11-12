package csw.proto.galil.simulator

import akka.actor.Cancellable
import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.Behaviors
import csw.proto.galil.io.DataRecord
import csw.proto.galil.io.DataRecord.{GalilAxisStatus, GeneralState, Header, axes}

import scala.concurrent.duration._

/**
  * An actor that simulates a Galil device (in a very simplified way).
  */
object GalilSimulatorActor {

  //  sealed trait Axis
  //  case object S extends Axis
  //  case object T extends Axis
  //  case object I extends Axis
  //  case object A extends Axis
  //  case object B extends Axis
  //  case object C extends Axis
  //  case object D extends Axis
  //  case object E extends Axis
  //  case object F extends Axis
  //  case object G extends Axis
  //  case object H extends Axis
  //
  //  def axis(c: Char): Axis = c match {
  //    case '
  //  }

  type Axis = Char

  // Commands received by this actor
  sealed trait GalilSimulatorCommand

  // Commands corresponding to the ones defined in GalilCommands.conf
  // (XXX TODO: Add missing commands)
  val MotorOff = "MO"
  val MotorOn = "SH"
  val MotorPosition = "DP"
  val PositionTracking = "PT"
  val MotorType = "MT"
  val AmplifierGain = "AG"
  val StepMotorResolution = "YB"
  val MotorSmoothing = "KS"
  val Acceleration = "AC"
  val Deceleration = "DC"
  val LowCurrent = "LC"
  val MotorSpeed = "SP"
  val AbsTarget = "PA"
  val BeginMotion = "BG"

  // A Galil command to set a value for a given axis
  case class AxisSetCommand(cmd: String, axis: Axis, value: Int)
    extends GalilSimulatorCommand

  // A Galil command to get a value for a given axis
  case class AxisGetCommand(cmd: String, axis: Axis, sender: ActorRef[Int])
    extends GalilSimulatorCommand

  // A Galil command for a given axis
  case class AxisCommand(cmd: String, axis: Axis) extends GalilSimulatorCommand

  // A Galil command with no args
  case class Command(cmd: String) extends GalilSimulatorCommand

  // Returns the current data record (for QR command)
  case class GetDataRecord(sender: ActorRef[DataRecord]) extends GalilSimulatorCommand

  // Called periodically to simulate the motor motion for the given axis
  case class SimulateMotion(axis: Axis) extends GalilSimulatorCommand

  // Holds the timer and current state for an axis
  case class AxisContext(motorOnTimer: Option[Cancellable], settings: Map[String, Int])

  // Holds the current state of each axis
  case class SimulatorContext(map: Map[Axis, AxisContext])

  //      // MO
  //      Setup(prefix, CommandName("motorOff"), None).add(axisKey.set(axis)),
  //      // DP
  //      Setup(prefix, CommandName("setMotorPosition"), None).add(axisKey.set(axis)).add(countsKey.set(0)),
  //      // PT
  //      Setup(prefix, CommandName("setPositionTracking"), None).add(axisKey.set(axis)).add(countsKey.set(0)),
  //      // MT - Motor Type (stepper)
  //      Setup(prefix, CommandName("setMotorType"), None).add(axisKey.set(axis)).add(countsKey.set(2)),
  //      // AG - Amplifier Gain: Maximum current 1.4A
  //      Setup(prefix, CommandName("setAmplifierGain"), None).add(axisKey.set(axis)).add(countsKey.set(2)),
  //      // YB
  //      Setup(prefix, CommandName("setStepMotorResolution"), None).add(axisKey.set(axis)).add(countsKey.set(200)),
  //      // KS
  //      Setup(prefix, CommandName("setMotorSmoothing"), None).add(axisKey.set(axis)).add(smoothKey.set(8)),
  //      // AC
  //      Setup(prefix, CommandName("setAcceleration"), None).add(axisKey.set(axis)).add(countsKey.set(1024)),
  //      // DC
  //      Setup(prefix, CommandName("setDeceleration"), None).add(axisKey.set(axis)).add(countsKey.set(1024)),
  //      // LC - Low current mode.  setting is a guess.
  //      Setup(prefix, CommandName("setLowCurrent"), None).add(axisKey.set(axis)).add(lcParamKey.set(2)),
  //      // SH
  //      Setup(prefix, CommandName("motorOn"), None).add(axisKey.set(axis)),
  //      // SP - set speed in steps per second
  //      Setup(prefix, CommandName("setMotorSpeed"), None).add(axisKey.set(axis)).add(speedKey.set(25)),

  def simulate(simCtx: SimulatorContext = SimulatorContext(Map.empty))
  : Behavior[GalilSimulatorCommand] = Behaviors.receive { (ctx, msg) =>

    // --- Start actor behavior ---
    msg match {
      case SimulateMotion(axis) => simulateMotion(axis)

      case Command(cmd) => Behavior.same

      case AxisCommand(`MotorOn`, axis) => motorOn(axis)
      case AxisCommand(`MotorOff`, axis) => motorOff(axis)
      case AxisCommand(cmd, axis) => Behavior.same

      case AxisSetCommand(cmd, axis, value) => setAxisValue(axis, cmd, value)

      case AxisGetCommand(cmd, axis, sender) => getAxisValue(axis, cmd, sender)

      case GetDataRecord(sender) => getDataRecord(sender)
    }

    // Simulate the motor motion based on the given commands
    def simulateMotion(axis: Char): Behavior[GalilSimulatorCommand] = {
      // XXX TODO update values for motion
      simulate(simCtx)
    }

    // Gets the context for the given Galil axis
    def getAxisContext(axis: Char): AxisContext = {
      simCtx.map.getOrElse(axis, AxisContext(None, Map.empty))
    }

    // Turn the motor for the give axis on
    def motorOn(axis: Char): Behavior[GalilSimulatorCommand] = {
      val ac = getAxisContext(axis)
      ac.motorOnTimer.foreach(_.cancel())
      val timer = ctx.schedule(100.millis, ctx.self, SimulateMotion(axis))
      val newAc = ac.copy(motorOnTimer = Some(timer))
      simulate(SimulatorContext(simCtx.map + (axis -> newAc)))
    }

    // Turn the motor for the give axis off
    def motorOff(axis: Char): Behavior[GalilSimulatorCommand] = {
      val ac = getAxisContext(axis)
      ac.motorOnTimer.foreach(_.cancel())
      val newAc = ac.copy(motorOnTimer = None)
      simulate(SimulatorContext(simCtx.map + (axis -> newAc)))
    }

    // Set the value for a given command on the given axis
    def setAxisValue(axis: Char, name: String, value: Int): Behavior[GalilSimulatorCommand] = {
      val ac = getAxisContext(axis)
      val newSettings = ac.settings + (name -> value)
      val newAc = ac.copy(settings = newSettings)
      simulate(SimulatorContext(simCtx.map + (axis -> newAc)))
    }

    // Gets the value for a given command on the given axis
    def getAxisValue(axis: Char, name: String, replyTo: ActorRef[Int]): Behavior[GalilSimulatorCommand] = {
      val ac = getAxisContext(axis)
      replyTo ! ac.settings(name)
      Behaviors.same
    }

    // Sends the data record to the replyTo actor
    def getDataRecord(replyTo: ActorRef[DataRecord]): Behavior[GalilSimulatorCommand] = {
      val blocksPresent = List("S", "T", "I", "A", "B", "C", "D")

      val recordSize = 226
      val header = Header(blocksPresent, recordSize)

      val sampleNumber = 28114.toShort
      val inputs = (0 to 9).map(_ => 0.toByte).toArray
      val outputs = (0 to 9).map(_ => 0.toByte).toArray
      val ethernetHandleStatus = (0 to 8).map(_ => 0.toByte).toArray
      val errorCode = 0.toByte
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

      val axisStatuses = axes.map(_ => GalilAxisStatus()).toArray

      replyTo ! DataRecord(header, generalState, axisStatuses)
      Behaviors.same
    }

  }

}
