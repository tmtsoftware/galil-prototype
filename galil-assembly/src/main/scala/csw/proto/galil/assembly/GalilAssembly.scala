package csw.proto.galil.assembly

import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.apps.containercmd.ContainerCmd
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.messages.RunningMessage.DomainMessage
import csw.messages._
import csw.messages.ccs.commands.{CommandResponse, ControlCommand}
import csw.messages.framework.ComponentInfo
import csw.messages.location.TrackingEvent
import csw.messages.models.PubSub.PublisherMessage
import csw.messages.params.states.CurrentState
import csw.services.location.scaladsl.LocationService

import scala.async.Async._
import scala.concurrent.{ExecutionContextExecutor, Future}

// Base trait for Galil Assembly domain messages
sealed trait GalilAssemblyDomainMessage extends DomainMessage

// Add messages here...

private class GalilAssemblyBehaviorFactory extends ComponentBehaviorFactory[GalilAssemblyDomainMessage] {
  override def handlers(
                ctx: ActorContext[ComponentMessage],
                componentInfo: ComponentInfo,
                commandResponseManager: ActorRef[CommandResponseManagerMessage],
                pubSubRef: ActorRef[PublisherMessage[CurrentState]],
                locationService: LocationService
              ): ComponentHandlers[GalilAssemblyDomainMessage] =
    new GalilAssemblyHandlers(ctx, componentInfo, commandResponseManager, pubSubRef, locationService)
}

private class GalilAssemblyHandlers(ctx: ActorContext[ComponentMessage],
                                    componentInfo: ComponentInfo,
                                    commandResponseManager: ActorRef[CommandResponseManagerMessage],
                                    pubSubRef: ActorRef[PublisherMessage[CurrentState]],
                                    locationService: LocationService)
  extends ComponentHandlers[GalilAssemblyDomainMessage](ctx, componentInfo, commandResponseManager, pubSubRef, locationService) {

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log = loggerFactory.getLogger

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
  }

  override def validateCommand(controlCommand: ControlCommand): CommandResponse = {
    CommandResponse.Accepted(controlCommand.runId)
  }

  override def onSubmit(controlCommand: ControlCommand, replyTo: ActorRef[CommandResponse]): Unit = {
    log.debug(s"onSubmit called: $controlCommand")
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

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")
}


// Start assembly from the command line using GalilAssembly.conf resource file
object GalilAssemblyApp extends App {
  val defaultConfig = ConfigFactory.load("GalilAssembly.conf")
  ContainerCmd.start("GalilAssembly", args, Some(defaultConfig))
}
