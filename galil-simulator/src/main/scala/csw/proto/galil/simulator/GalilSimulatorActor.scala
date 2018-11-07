package csw.proto.galil.simulator

import akka.actor.Cancellable
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors

import scala.concurrent.duration._

/**
  * An actor that simulates a Galil device (in a very simplified way).
  */
object GalilSimulatorActor {

  sealed trait Axis
  case object S extends Axis
  case object T extends Axis
  case object I extends Axis
  case object A extends Axis
  case object B extends Axis
  case object C extends Axis
  case object D extends Axis
  case object E extends Axis
  case object F extends Axis
  case object G extends Axis
  case object H extends Axis

  // Commands received by this actor
  sealed trait GalilSimulatorCommand

  // Commands corresponding to the ones defined in GalilCommands.conf
  case class MotorOff(axis: Axis) extends GalilSimulatorCommand
  case class MotorOn(axis: Axis) extends GalilSimulatorCommand
  case class SetMotorPosition(axis: Axis, value: Int) extends GalilSimulatorCommand
  case class GetMotorPosition(axis: Axis) extends GalilSimulatorCommand
  case class SetPositionTracking(axis: Axis, value: Int) extends GalilSimulatorCommand
  case class GetPositionTracking(axis: Axis) extends GalilSimulatorCommand
  case class SetMotorType(axis: Axis, value: Int) extends GalilSimulatorCommand
  case class GetMotorType(axis: Axis) extends GalilSimulatorCommand
  case class SetAmplifierGain(axis: Axis, value: Int) extends GalilSimulatorCommand
  case class GetAmplifierGain(axis: Axis) extends GalilSimulatorCommand
  val StepMotorResolution = "YB"
  val MotorSmoothing = "KS"
  val Acceleration = "AC"
  val Deceleration = "DC"
  val LowCurrent = "LC"
  val MotorSpeed = "SP"

  val AbsTarget = "PA"
  val BeginMotion = "BG"



//  // A Galil command to set a value for a given axis
//  case class AxisSetCommand(cmd: String, axis: Char, value: Int)
//    extends GalilSimulatorCommand
//
//  // A Galil command for a given axis
//  case class AxisCommand(cmd: String, axis: Char) extends GalilSimulatorCommand
//
//  // A Galil command with no args
//  case class Command(cmd: String) extends GalilSimulatorCommand

  // Called periodically to simulate the motor motion for the given axis
  case class SimulateMotion(axis: Char) extends GalilSimulatorCommand

  // Holds the timer and current state for an axis
  case class AxisContext(motorOnTimer: Option[Cancellable], motorPos: Int)

  // Holds the current state of each axis
  case class SimulatorContext(map: Map[Char, AxisContext])

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
//  val MotorOff = "MO"
//  val MotorOn = "SH"
//  val MotorPosition = "DP"
//  val PositionTracking = "PT"
//  val MotorType = "MT"
//  val AmplifierGain = "AG"
//  val StepMotorResolution = "YB"
//  val MotorSmoothing = "KS"
//  val Acceleration = "AC"
//  val Deceleration = "DC"
//  val LowCurrent = "LC"
//  val MotorSpeed = "SP"
//  val AbsTarget = "PA"
//  val BeginMotion = "BG"

  def simulate(simCtx: SimulatorContext = SimulatorContext(Map.empty))
  : Behavior[GalilSimulatorCommand] = Behaviors.receive { (ctx, msg) =>
    // Simulate what happens when the Galil device receives the given command for the given axis and value
    def handleCommand(cmd: String,
                      axis: Char,
                      value: Int): Behavior[GalilSimulatorCommand] = {
      cmd match {
        case `MotorOn` => motorOn(axis)
        case `MotorOff` => motorOff(axis)
        case `MotorPosition` => motorPosition(axis, value)
        case x =>
          println(s"Unsupported Galil simulator actor command: $x")
          Behaviors.same
      }
    }

    // Simulate the motor motion based on the given commands
    def simulateMotion(axis: Char): Behavior[GalilSimulatorCommand] = {
      simulate(simCtx)
    }

    // Gets the context for the given Galil axis
    def getAxisContext(axis: Char): AxisContext = {
      simCtx.map.getOrElse(axis, AxisContext(None, 0))
    }

    // Turn the motor for the give axis on
    def motorOn(axis: Char): Behavior[GalilSimulatorCommand] = {
      val ac = getAxisContext(axis)
      ac.motorOnTimer.foreach(_.cancel())
      val timer = ctx.schedule(100.millis, ctx.self, SimulateMotion(axis))
      val newAc = ac.copy(motorOnTimer = Some(timer))
      SimulatorContext(simCtx.map + (axis -> newAc))
    }

    // Turn the motor for the give axis off
    def motorOff(axis: Char): Behavior[GalilSimulatorCommand] = {
      val ac = getAxisContext(axis)
      ac.motorOnTimer.foreach(_.cancel())
      val newAc = ac.copy(motorOnTimer = None)
      SimulatorContext(simCtx.map + (axis -> newAc))
    }

    // Turn the motor for the give axis off
    def motorPosition(axis: Char,
                      value: Int): Behavior[GalilSimulatorCommand] = {
      val ac = getAxisContext(axis)
      val newAc = ac.copy(motorPos = value)
      SimulatorContext(simCtx.map + (axis -> newAc))
    }

    // --- Start actor behavior ---
    msg match {
      case SimulateMotion(axis) =>
        simulateMotion(axis)

      case SetCommand(cmd, axis, value) =>
        handleCommand(cmd, axis, value)

    }
  }

}
