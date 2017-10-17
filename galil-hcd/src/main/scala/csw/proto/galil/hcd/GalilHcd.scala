package csw.proto.galil.hcd

import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.apps.containercmd.ContainerCmd
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.messages.RunningMessage.DomainMessage
import csw.messages._
import csw.messages.ccs.commands.{CommandInfo, Setup}
import csw.messages.ccs.{Validation, ValidationIssue, Validations}
import csw.messages.framework.ComponentInfo
import csw.messages.location.TrackingEvent
import csw.messages.params.models.Prefix
import csw.messages.params.states.CurrentState
import csw.proto.galil.hcd.CSWDeviceAdapter.CommandMapEntry
import csw.proto.galil.hcd.GalilCommandMessage.GalilRequest
import csw.proto.galil.hcd.GalilResponseMessage.GalilResponse
import csw.services.location.scaladsl.LocationService
import csw.services.logging.scaladsl.ComponentLogger

import scala.async.Async._
import scala.concurrent.{ExecutionContextExecutor, Future}

// Base trait for Galil HCD domain messages
sealed trait GalilHcdDomainMessage extends DomainMessage

// Add messages here...
sealed trait GalilCommandMessage extends GalilHcdDomainMessage
object GalilCommandMessage {
  case class GalilCommand(commandString: String)                                          extends GalilCommandMessage
  case class GalilRequest(commandString: String, prefix: Prefix, cmdInfo: CommandInfo, cmdMapEntry: CommandMapEntry, client: ActorRef[CommandResponse]) extends GalilCommandMessage
}

sealed trait GalilResponseMessage extends GalilHcdDomainMessage
object GalilResponseMessage {
  case class GalilResponse(response: String, prefix: Prefix, cmdInfo: CommandInfo, cmdMapEntry: CommandMapEntry, client: ActorRef[CommandResponse]) extends GalilResponseMessage
}


case class GalilCommandInfo()
private class GalilHcdBehaviorFactory extends ComponentBehaviorFactory[GalilHcdDomainMessage] {
  override def handlers(ctx: ActorContext[ComponentMessage],
                        componentInfo: ComponentInfo,
                        pubSubRef: ActorRef[PubSub.PublisherMessage[CurrentState]],
                        locationService: LocationService
                       ): ComponentHandlers[GalilHcdDomainMessage] =
    new GalilHcdHandlers(ctx, componentInfo, pubSubRef, locationService)
}

private class GalilHcdHandlers(ctx: ActorContext[ComponentMessage],
                               componentInfo: ComponentInfo,
                               pubSubRef: ActorRef[PubSub.PublisherMessage[CurrentState]],
                               locationService: LocationService)
    extends ComponentHandlers[GalilHcdDomainMessage](ctx, componentInfo, pubSubRef, locationService)
    with ComponentLogger.Simple{

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  val config = ConfigFactory.load("GalilCommands.conf")
  val adapter = new CSWDeviceAdapter(config)

  var galilHardwareActor: ActorRef[GalilCommandMessage] = _

  override def componentName(): String = "GalilHcd"

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
    galilHardwareActor = ctx.spawnAnonymous(GalilIOActor.behavior(getGalilConfig, Some(ctx.self)))
  }

  override def onShutdown(): Future[Unit] = async {
    log.debug("onShutdown called")
  }

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def onDomainMsg(galilMsg: GalilHcdDomainMessage): Unit = galilMsg match {
    case (x: GalilResponseMessage) => handleGalilReponse(x)

    case x => log.debug(s"onDomainMessage called: $x")
  }

  def handleGalilReponse(galilResponseMessage: GalilResponseMessage): Unit = galilResponseMessage match {
    case GalilResponse(response, prefix, cmdInfo, cmdMapEntry, client) =>
      val returnResponse = adapter.makeResponse(prefix, cmdInfo, cmdMapEntry, response)
      client ! returnResponse
  }

  override def onSetup(commandMessage: CommandMessage): Validation = {
    log.debug(s"onSetup called: $commandMessage")
    commandMessage match {
      case x: Setup => {
        val cmdMapEntry = adapter.getCommandMapEntry(x)
        if (cmdMapEntry.isSuccess) {
          val cmdString = adapter.validateSetup(x, cmdMapEntry.get)
          if (cmdString.isSuccess) {
            galilHardwareActor ! GalilRequest(cmdString.get, x.prefix, x.info, cmdMapEntry.get, x.replyTo)
            Validations.Valid
          } else {
            Validations.Invalid(ValidationIssue.ParameterValueOutOfRangeIssue(cmdString.failed.get.getMessage))
          }
        } else {
          Validations.Invalid(ValidationIssue.OtherIssue(cmdMapEntry.failed.get.getMessage))
        }
      }
      case _ => log.error("Invalid commandMessage in onSetup.  Not a Setup type")
        Validations.Invalid(ValidationIssue.OtherIssue("Not a Setup"))
    }
  }

  override def onObserve(commandMessage: CommandMessage): Validation =  {
    log.debug(s"onObserve called: $commandMessage")
    Validations.Invalid(ValidationIssue.UnsupportedCommandIssue("Observe  not supported"))
  }

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")

  override protected def maybeComponentName(): Option[String] = Some("GalilHcd")

  def getGalilConfig: GalilConfig = new GalilConfig();

}

object GalilHcdApp extends App {
  val defaultConfig = ConfigFactory.load("GalilHcd.conf")
  ContainerCmd.start("GalilHcd", args, Some(defaultConfig))
}