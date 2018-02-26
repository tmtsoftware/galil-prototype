package csw.proto.galil.assembly

import akka.actor.Scheduler
import akka.typed.ActorSystem
import akka.typed.scaladsl.ActorContext
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.apps.containercmd.ContainerCmd
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers, CurrentStatePublisher}
import csw.messages._
import csw.messages.ccs.CommandIssue._
import csw.messages.ccs.commands.CommandResponse.{Error, _}
import csw.messages.ccs.commands.{CommandResponse, ControlCommand, Setup}
import csw.messages.framework.ComponentInfo
import csw.messages.location.Connection.AkkaConnection
import csw.messages.location.ConnectionType.AkkaType
import csw.messages.location.{AkkaLocation, LocationRemoved, LocationUpdated, TrackingEvent}
import csw.messages.params.generics.KeyType._
import csw.messages.params.generics.{KeyType, Parameter}
import icd.web.shared.IcdModels.{AttributeModel, CommandModel, ReceiveCommandModel, SendCommandModel}
import csw.services.ccs.scaladsl.{CommandResponseManager, CommandService}
import csw.services.icd.model.CommandModelParser
import csw.services.location.scaladsl.LocationService
import csw.services.logging.scaladsl.LoggerFactory

import scala.async.Async._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

// Add messages here...

private class GalilAssemblyBehaviorFactory extends ComponentBehaviorFactory {
  override def handlers(
                ctx: ActorContext[TopLevelActorMessage],
                componentInfo: ComponentInfo,
                commandResponseManager: CommandResponseManager,
                currentStatePublisher: CurrentStatePublisher,
                locationService: LocationService,
                loggerFactory: LoggerFactory
              ): ComponentHandlers =
    new GalilAssemblyHandlers(ctx, componentInfo, commandResponseManager, currentStatePublisher, locationService, loggerFactory)
}

private class GalilAssemblyHandlers(ctx: ActorContext[TopLevelActorMessage],
                                    componentInfo: ComponentInfo,
                                    commandResponseManager: CommandResponseManager,
                                    currentStatePublisher: CurrentStatePublisher,
                                    locationService: LocationService,
                                    loggerFactory: LoggerFactory)
  extends ComponentHandlers(ctx, componentInfo, commandResponseManager, currentStatePublisher,
    locationService, loggerFactory) {

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  implicit val actorSystem: ActorSystem[_] = ctx.system
  private val log = loggerFactory.getLogger
  private val connectionsMap = mutable.HashMap[String, CommandService]() // TODO correct type?  Synchronization needed?
  val commandModel = CommandModelParser(ConfigFactory.load("GalilPrototypeAssembly_command-model.conf"))

  override def initialize(): Future[Unit] = {
    log.debug("Initialize called")
    // resolve references to dependencies
    componentInfo.connections.foreach {
      case connection: AkkaConnection =>
        val location = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds)
        location.foreach(l => connectionsMap += (connection.name -> new CommandService(l)))
      case _ => // TODO
    }
    Future.unit
  }

  override def validateCommand(controlCommand: ControlCommand): CommandResponse = {
    if (isOnline) {
      val matchingCommands = commandModel.receive.filter(_.name == controlCommand.commandName.name)
      if (matchingCommands.isEmpty) {
        Invalid(controlCommand.runId,
          UnsupportedCommandIssue(s"Unknown command: ${controlCommand.commandName.name}"))
      } else {
        validateCommandWithModel(controlCommand, matchingCommands.head)
      }
    } else {
      Invalid(controlCommand.runId, RequiredAssemblyUnavailableIssue("Assembly is offline"))
    }
  }

  override def onSubmit(controlCommand: ControlCommand): Unit = {
    log.debug(s"onSubmit called: $controlCommand")
    controlCommand.commandName.name match {
      case "SingleFilterMove" => commandResponseManager.addOrUpdateCommand(controlCommand.runId, Completed(controlCommand.runId))
      case _ => forwardCommandToHcd(controlCommand)
    }
  }

  override def onOneway(controlCommand: ControlCommand): Unit = {
    log.debug(s"onOneway called: $controlCommand")
  }

  override def onShutdown(): Future[Unit] = async {
    log.debug("onShutdown called")
  }

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")
    // dependencies are tracked via a locations service subscription to when TLA starts up
    // if there is an event, this is called.
    trackingEvent match {
      case LocationUpdated(loc) =>
        if (loc.connection.connectionType == AkkaType) {
          connectionsMap += (trackingEvent.connection.name -> new CommandService(loc.asInstanceOf[AkkaLocation]))
          // TODO other types?
        }
      case LocationRemoved(connection) =>
        if (connection.connectionType == AkkaType) {
          connectionsMap -= trackingEvent.connection.name
          // TODO other types?
        }
    }
  }

  private def validateCommandWithModel(controlCommand: ControlCommand, commandModel: ReceiveCommandModel): CommandResponse = {
    if (connectionsMap.contains("GalilHcd-hcd-akka")) {
      val params = controlCommand.paramSet

      if (params.size < commandModel.requiredArgs.size) {
        Invalid(controlCommand.runId,
          WrongNumberOfParametersIssue(s"Command requires ${commandModel.requiredArgs.size}, got ${params.size}"))
      } else if (params.size > commandModel.args.size) {
        Invalid(controlCommand.runId,
          WrongNumberOfParametersIssue(s"Maximum number of arguments for command is ${commandModel.args.size}, " +
            s"got ${params.size}"))
      } else {
        if (!commandModel.requiredArgs.forall(p => params.map(_.keyName).contains(p))) {
          Invalid(controlCommand.runId, MissingKeyIssue(s"Missing required argument"))  // TODO ideally this gives more info
        } else {
          validateParameterList(params, controlCommand, commandModel)
        }
      }
    } else {
      Invalid(controlCommand.runId, RequiredHCDUnavailableIssue("Hcd(s) not found"))
    }
  }

  // recursive method for checking argument list
  private def validateParameterList(params: Set[Parameter[_]], command: ControlCommand, model: ReceiveCommandModel): CommandResponse = {
    validateParameter(params.head, command, model) match {
      case a:Accepted =>
        if (params.tail.isEmpty) {
          a
        } else {
          validateParameterList(params.tail, command, model)
        }
      case x => x
    }

  }

  // method for validating individual parameter
  private def validateParameter(arg: Parameter[_], command: ControlCommand, model: ReceiveCommandModel): CommandResponse = {
    val argModels = model.args.filter(_.name == arg.keyName)
    if (argModels.isEmpty) {
      Invalid(command.runId, OtherIssue(s"Parameter ${arg.keyName} not supported by command ${command.commandName.name}"))
    } else {

      val argModel = argModels.head
      if (keyTypeToString(arg.keyType) != argModel.typeStr.takeWhile(_ != ' ')) {
        Invalid(command.runId,
          WrongParameterTypeIssue(s"Type ${keyTypeToString(arg.keyType)} does not match model ${argModel.typeStr}"))
      } else {
        if (arg.units.getName != s"[${argModel.units}]") {
          Invalid(command.runId,
            WrongUnitsIssue(s"Units passed in ${arg.units.getName} does not match model s[${argModel.units}]"))
        } else {
          if (!validateRange(arg, argModel)) {
            Invalid(command.runId, ParameterValueOutOfRangeIssue(s"Parameter value ${arg.items.head} out of range: ${argModel.typeStr}"))
          } else {
            Accepted(command.runId)
          }
        }
      }
    }
  }


  private def validateRange(value: Parameter[_], model: AttributeModel): Boolean = {
    // TODO
    true
  }
  private def keyTypeToString(t: KeyType[_]): String = t match {
    case RaDecKey     => "radec"
    case StringKey    => "string"
    case StructKey    => "struct"

    case BooleanKey => "boolean"
    case CharKey    => "char"

    case ByteKey   => "byte"
    case ShortKey  => "short"
    case LongKey   => "long"
    case IntKey    => "integer"
    case FloatKey  => "float"
    case DoubleKey => "double"

      // TODO array and matrix types
    case ByteArrayKey   => "array of byte"
    case ShortArrayKey  => "array of short"
    case LongArrayKey   => "array of long"
    case IntArrayKey    => "array of integer"
    case FloatArrayKey  => "array of float"
    case DoubleArrayKey => "array of double"

    case ByteMatrixKey   => "matrix of byte"
    case ShortMatrixKey  => "matrix of short"
    case LongMatrixKey   => "matrix of long"
    case IntMatrixKey    => "matrix of integer"
    case FloatMatrixKey  => "matrix of float"
    case DoubleMatrixKey => "matrix of double"

    case _  => ""
  }

  // For testing, forward command to HCD and complete this command when it completes
  private def forwardCommandToHcd(controlCommand: ControlCommand): Unit = {
    implicit val scheduler: Scheduler = ctx.system.scheduler
    implicit val timeout: Timeout = Timeout(3.seconds)
    val galilHcd = connectionsMap.get("GalilHcd")
    galilHcd.foreach { hcd =>
      val setup = Setup(controlCommand.source, controlCommand.commandName, controlCommand.maybeObsId, controlCommand.paramSet)
      commandResponseManager.addSubCommand(controlCommand.runId, setup.runId)

      val f = for {
        response <- hcd.submitAndSubscribe(setup)
      } yield {
        log.info(s"response = $response")
        commandResponseManager.updateSubCommand(setup.runId, response)
      }
      f.recover {
        case ex =>
          commandResponseManager.updateSubCommand(setup.runId, Error(setup.runId, ex.toString))
      }
    }
  }
}


// Start assembly from the command line using GalilAssembly.conf resource file
object GalilAssemblyApp extends App {
  val defaultConfig = ConfigFactory.load("GalilAssembly.conf")
  ContainerCmd.start("GalilAssembly", args, Some(defaultConfig))
}
