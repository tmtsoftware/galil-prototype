package csw.proto.galil.hcd

import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.apps.containercmd.ContainerCmd
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.messages._
import csw.messages.RunningMessage.DomainMessage
import csw.messages.ccs.commands.ControlCommand
import csw.messages.ccs.{Validation, Validations}
import csw.messages.framework.ComponentInfo
import csw.messages.location.TrackingEvent
import csw.messages.params.states.CurrentState
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
  case class GalilRequest(commandString: String, replyTo: ActorRef[GalilResponseMessage]) extends GalilCommandMessage
}

sealed trait GalilResponseMessage extends GalilHcdDomainMessage
object GalilResponseMessage {
  case class GalilResponse(response: String) extends GalilResponseMessage
}


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
    case x => log.debug(s"onDomainMessage called: $x")
  }

  override def onSubmit(controlCommand: ControlCommand, replyTo: ActorRef[CommandResponse]): Validation = {
    log.debug("onSubmit called")
    Validations.Valid
  }

  override def onOneway(controlCommand: ControlCommand): Validation = {
    log.debug("onOneway called")
    Validations.Valid
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