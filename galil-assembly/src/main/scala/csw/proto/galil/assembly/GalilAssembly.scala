package csw.proto.galil.assembly

import akka.actor.Scheduler
import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.apps.containercmd.ContainerCmd
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.messages.CommandResponseManagerMessage.{AddOrUpdateCommand, AddSubCommand, UpdateSubCommand}
import csw.messages.RunningMessage.DomainMessage
import csw.messages._
import csw.messages.ccs.CommandIssue._
import csw.messages.ccs.commands.CommandResponse._
import csw.messages.ccs.commands._
import csw.messages.framework.ComponentInfo
import csw.messages.location.Connection.AkkaConnection
import csw.messages.location.ConnectionType.AkkaType
import csw.messages.location._
import csw.messages.models.PubSub.PublisherMessage
import csw.messages.params.generics.KeyType._
import csw.messages.params.generics.{KeyType, Parameter}
import csw.messages.params.states.CurrentState
import csw.proto.models.ComponentModels
import csw.proto.models.ComponentModels.{AttributeModel, CommandModel, ReceiveCommandModel}
import csw.services.location.scaladsl.LocationService
import csw.services.logging.scaladsl.LoggerFactory

import scala.async.Async._
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

// Base trait for Galil Assembly domain messages
sealed trait GalilAssemblyDomainMessage extends DomainMessage

// Add messages here...

private class GalilAssemblyBehaviorFactory extends ComponentBehaviorFactory[GalilAssemblyDomainMessage] {
  override def handlers(
                ctx: ActorContext[TopLevelActorMessage],
                componentInfo: ComponentInfo,
                commandResponseManager: ActorRef[CommandResponseManagerMessage],
                pubSubRef: ActorRef[PublisherMessage[CurrentState]],
                locationService: LocationService,
                loggerFactory: LoggerFactory
              ): ComponentHandlers[GalilAssemblyDomainMessage] =
    new GalilAssemblyHandlers(ctx, componentInfo, commandResponseManager, pubSubRef, locationService, loggerFactory)
}

private class GalilAssemblyHandlers(ctx: ActorContext[TopLevelActorMessage],
                                    componentInfo: ComponentInfo,
                                    commandResponseManager: ActorRef[CommandResponseManagerMessage],
                                    pubSubRef: ActorRef[PublisherMessage[CurrentState]],
                                    locationService: LocationService,
                                    loggerFactory: LoggerFactory)
  extends ComponentHandlers[GalilAssemblyDomainMessage](ctx, componentInfo, commandResponseManager, pubSubRef,
    locationService, loggerFactory) {

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log = loggerFactory.getLogger
  private val connectionsMap = mutable.HashMap[String, ComponentRef]() // TODO correct type?  Synchronization needed?
  private val receiveCommandModels = mutable.ListBuffer[ComponentModels.ReceiveCommandModel]()
  private val sendCommandModel = List[ComponentModels.SendCommandModel]()

  receiveCommandModels += ReceiveCommandModel("SingleFilterMove", "Move a single filter", List[String](),
    List("wheelNum", "target"),
    List(
      AttributeModel("wheelNum",
        "Number of wheel to move",
        Some("integer"), None,
        "none",
        None, None,
        Some("1"), Some("8"),
        exclusiveMinimum = false, exclusiveMaximum = false,
        "", "integer (1 ≤ x ≤ 8)"),
      AttributeModel("target",
        "Target location",
        Some("integer"), None,
        "ct",
        None, None,
        None, None,
        exclusiveMinimum = false, exclusiveMaximum = false,
        "", "integer"),
      AttributeModel("speed",
        "Move Speed",
        Some("double"), None,
        "ms",   // For now, until speed units are added
        None, None,
        None, None,
        exclusiveMinimum = false, exclusiveMaximum = false,
        "", "double")
    )
  )

  val componentCommandModel = CommandModel("csw", "GalilAssembly", "Prototype Assembly", receiveCommandModels.toList, sendCommandModel)

  override def initialize(): Future[Unit] = {
    log.debug("Initialize called")
    // resolve references to dependencies
    componentInfo.connections.foreach {
      case connection: AkkaConnection =>
        val location = Await.result(locationService.resolve(connection, 10.seconds), 10.seconds)
        location.foreach(l => connectionsMap += (connection.name -> l.component))
      case _ => // TODO
    }
    Future.unit
  }

  override def validateCommand(controlCommand: ControlCommand): CommandResponse = {
    if (isOnline) {
      val matchingCommands = receiveCommandModels.filter(_.name == controlCommand.commandName.name).toList
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
      case "SingleFilterMove" => commandResponseManager ! AddOrUpdateCommand(controlCommand.runId, Completed(controlCommand.runId))
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

  override def onDomainMsg(galilMessage: GalilAssemblyDomainMessage): Unit = galilMessage match {
    case x => log.debug(s"onDomainMsg called: $x")
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")
    // dependencies are tracked via a locations service subscription to when TLA starts up
    // if there is an event, this is called.
    trackingEvent match {
      case LocationUpdated(loc) =>
        if (loc.connection.connectionType == AkkaType) {
          connectionsMap += (trackingEvent.connection.name -> loc.asInstanceOf[AkkaLocation].component)
          // TODO other types?
        }
      case LocationRemoved(connection) =>
        if (connection.connectionType == AkkaType) {
          connectionsMap -= trackingEvent.connection.name
          // TODO other types?
        }
    }
  }

  private def validateCommandWithModelOrig(controlCommand: ControlCommand, commandModel: ReceiveCommandModel): CommandResponse = {
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
        /*
                for (arg <- commandModel.requiredArgs) {
                  if (!params.exists(_.keyName == arg)) {
                    CommandResponse.Invalid(controlCommand.runId, MissingKeyIssue(s"Missing required argument $arg"))
                    // TODO
                  }
                }
                */
        if (!commandModel.requiredArgs.forall(p => params.map(_.keyName).contains(p))) {
          //        if (!params.exists(p => commandModel.requiredArgs.contains(p.keyName))) {
          Invalid(controlCommand.runId, MissingKeyIssue(s"Missing required argument"))
        } else {

          for (arg <- params) {
            val argModels = commandModel.args.filter(_.name == arg.keyName)
            if (argModels.isEmpty) {
              return Invalid(controlCommand.runId, OtherIssue(s"Parameter ${arg.keyName} not supported by command ${controlCommand.commandName.name}"))
            } else {
              val argModel = argModels.head
              if (keyTypeToString(arg.keyType) != argModel.typeStr.takeWhile(_ != ' ')) {
                return Invalid(controlCommand.runId,
                  WrongParameterTypeIssue(s"Type ${keyTypeToString(arg.keyType)} does not match model ${argModel.typeStr}"))
              } else {
                if (arg.units.getName != s"[${argModel.units}]") {
                  return Invalid(controlCommand.runId,
                    WrongUnitsIssue(s"Units passed in ${arg.units.getName} does not match model s[${argModel.units}]"))
                } else {
                  if (!validateRange(arg, argModel)) {
                    return Invalid(controlCommand.runId,
                      ParameterValueOutOfRangeIssue(s"Parameter value ${arg.items.head} out of range: ${argModel.typeStr}"))
                  }
                }
              }
            }
          }
          Accepted(controlCommand.runId)
        }
      }
    } else {
      Invalid(controlCommand.runId, RequiredHCDUnavailableIssue("Hcd(s) not found"))
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


  private def validateRange(value: Parameter[_], model: ComponentModels.AttributeModel): Boolean = {
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
      commandResponseManager ! AddSubCommand(controlCommand.runId, setup.runId)

      val f = for {
        response <- hcd.submitAndSubscribe(setup)
      } yield {
        log.info(s"response = $response")
        commandResponseManager ! UpdateSubCommand(setup.runId, response)
      }
      f.recover {
        case ex =>
          commandResponseManager ! UpdateSubCommand(setup.runId, Error(setup.runId, ex.toString))
      }
    }
  }
}


// Start assembly from the command line using GalilAssembly.conf resource file
object GalilAssemblyApp extends App {
  val defaultConfig = ConfigFactory.load("GalilAssembly.conf")
  ContainerCmd.start("GalilAssembly", args, Some(defaultConfig))
}
