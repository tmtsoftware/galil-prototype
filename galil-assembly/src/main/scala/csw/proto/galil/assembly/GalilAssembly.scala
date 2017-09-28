package csw.proto.galil.assembly

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.framework.internal.wiring.{FrameworkWiring, Standalone}
import csw.framework.models.ComponentInfo
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.param.messages._
import csw.param.messages.PubSub.PublisherMessage
import csw.param.messages.RunningMessage.DomainMessage
import csw.param.models.{Validation, Validations}
import csw.param.states.CurrentState
import csw.services.location.commons.ClusterAwareSettings
import csw.services.location.models.TrackingEvent
import csw.services.location.scaladsl.LocationService
import csw.services.logging.scaladsl.{ComponentLogger, LoggingSystemFactory}

import scala.async.Async._
import scala.concurrent.{ExecutionContextExecutor, Future}

// Base trait for Galil Assembly domain messages
sealed trait GalilAssemblyDomainMessage extends DomainMessage

// Add messages here...

private class GalilAssemblyBehaviorFactory extends ComponentBehaviorFactory[GalilAssemblyDomainMessage] {
  override def handlers(
                ctx: ActorContext[ComponentMessage],
                componentInfo: ComponentInfo,
                pubSubRef: ActorRef[PublisherMessage[CurrentState]],
                locationService: LocationService
              ): ComponentHandlers[GalilAssemblyDomainMessage] =
    new GalilAssemblyHandlers(ctx, componentInfo, pubSubRef, locationService)
}

private class GalilAssemblyHandlers(ctx: ActorContext[ComponentMessage],
                                    componentInfo: ComponentInfo,
                                    pubSubRef: ActorRef[PubSub.PublisherMessage[CurrentState]],
                                    locationService: LocationService)
  extends ComponentHandlers[GalilAssemblyDomainMessage](ctx, componentInfo, pubSubRef, locationService)
    with ComponentLogger.Simple {

  implicit val ec: ExecutionContextExecutor = ctx.executionContext

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
  }

  override def onRun(): Future[Unit] = async {
    log.debug("onRun called")
  }

  override def onShutdown(): Future[Unit] = async {
    log.debug("onShutdown called")
  }

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def onDomainMsg(galilMessage: GalilAssemblyDomainMessage): Unit = galilMessage match {
    case x => log.debug(s"onDomainMsg called: $x")
  }

  override def onControlCommand(commandMessage: CommandMessage): Validation = {
    log.debug(s"onControlCommand called: $commandMessage")
    Validations.Valid
  }

  override def onCommandValidationNotification(validationResponse: CommandValidationResponse): Unit =
    log.debug(s"onCommandValidationNotification called: $validationResponse")

  override def onCommandExecutionNotification(executionResponse: CommandExecutionResponse): Unit =
    log.debug(s"onCommandExecutionNotification called: $executionResponse")

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")

  override protected def maybeComponentName(): Option[String] = Some("GalilAssembly")

}

// Start assembly from the command line using GalilAssembly.conf resource file
// (XXX TODO: change to use ContainerCmd when it supports resource files)
object GalilAssemblyApp extends App {
  val host = InetAddress.getLocalHost.getHostName
  val system: ActorSystem = ClusterAwareSettings.system
  LoggingSystemFactory.start("GalilAssembly", "0.1", host, system)
  val wiring  = FrameworkWiring.make(system)
  Standalone.spawn(ConfigFactory.load("GalilAssembly.conf"), wiring)
}


//// Start assembly from the command line (pass --file $path/resources/GalilAssembly.conf)
//object GalilAssemblyApp extends App {
//  import csw.apps.containercmd.ContainerCmd
//  ContainerCmd.start("GalilAssembly", args)
//}
