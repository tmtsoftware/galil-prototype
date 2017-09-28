package csw.proto.galil.hcd

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.framework.internal.wiring.{FrameworkWiring, Standalone}
import csw.framework.models.ComponentInfo
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.param.messages._
import csw.param.messages.RunningMessage.DomainMessage
import csw.param.models.{Validation, Validations}
import csw.param.states.CurrentState
import csw.services.location.commons.ClusterAwareSettings
import csw.services.location.models.TrackingEvent
import csw.services.location.scaladsl.LocationService
import csw.services.logging.scaladsl.{ComponentLogger, LoggingSystemFactory}

import scala.async.Async._
import scala.concurrent.{ExecutionContextExecutor, Future}

// Base trait for Galil HCD domain messages
sealed trait GalilHcdDomainMessage extends DomainMessage

// Add messages here...

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

  override def onDomainMsg(galilMsg: GalilHcdDomainMessage): Unit = galilMsg match {
    case x => log.debug(s"onDomainMessage called: $x")
  }

  override def onControlCommand(commandMsg: CommandMessage): Validation = {
    log.debug(s"onControlCommand called: $commandMsg")
    Validations.Valid
  }

  override def onCommandValidationNotification(validationResponse: CommandValidationResponse): Unit =
    log.debug(s"onCommandValidationNotification called: $validationResponse")

  override def onCommandExecutionNotification(executionResponse: CommandExecutionResponse): Unit =
    log.debug(s"onCommandExecutionNotification called: $executionResponse")

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit =
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")

  override protected def maybeComponentName(): Option[String] = Some("GalilHcd")
}

object GalilHcdApp extends App {
  val host = InetAddress.getLocalHost.getHostName
  val system: ActorSystem = ClusterAwareSettings.system
  LoggingSystemFactory.start("GalilHcd", "0.1", host, system)
  val wiring = FrameworkWiring.make(system)
  Standalone.spawn(ConfigFactory.load("GalilHcd.conf"), wiring)
}