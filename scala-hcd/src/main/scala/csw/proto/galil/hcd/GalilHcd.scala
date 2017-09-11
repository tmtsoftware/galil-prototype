package csw.proto.galil.hcd

import java.net.InetAddress

import akka.actor.ActorSystem
import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import com.typesafe.config.ConfigFactory
import csw.common.ccs.{Validation, Validations}
import csw.common.framework.internal.wiring.{FrameworkWiring, Standalone}
import csw.common.framework.models.ComponentInfo
import csw.common.framework.models.RunningMessage.DomainMessage
import csw.common.framework.models._
import csw.common.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.param.states.CurrentState
import csw.services.location.commons.ClusterAwareSettings
import csw.services.location.scaladsl.ActorSystemFactory
import csw.services.logging.scaladsl.{ComponentLogger, LoggingSystemFactory}

import scala.async.Async._
import scala.concurrent.{ExecutionContextExecutor, Future}

// Base trait for Galil HCD domain messages
sealed trait GalilHcdDomainMessage extends DomainMessage

// Add messages here...

// Temporary logger, until one is provided by the API
object GalilHcdLogger extends ComponentLogger("GalilHcd")

private class GalilHcdBehaviorFactory extends ComponentBehaviorFactory[GalilHcdDomainMessage] {
  override def handlers(ctx: ActorContext[ComponentMessage],
                        componentInfo: ComponentInfo,
                        pubSubRef: ActorRef[PubSub.PublisherMessage[CurrentState]]
                       ): ComponentHandlers[GalilHcdDomainMessage] = new GalilHcdHandlers(ctx, componentInfo, pubSubRef)
}

private class GalilHcdHandlers(ctx: ActorContext[ComponentMessage], componentInfo: ComponentInfo,
                               pubSubRef: ActorRef[PubSub.PublisherMessage[CurrentState]])
  extends ComponentHandlers[GalilHcdDomainMessage](ctx, componentInfo, pubSubRef) with GalilHcdLogger.Simple {

  implicit val ec: ExecutionContextExecutor = ctx.executionContext

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
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
}

object GalilHcdApp extends App with GalilHcdLogger.Simple {
  val host = InetAddress.getLocalHost.getHostName
  val system: ActorSystem = ClusterAwareSettings.system
  LoggingSystemFactory.start("GalilHcd", "0.1", host, system)
  log.debug("Starting Galil HCD")
  val wiring = FrameworkWiring.make(system)
  Standalone.spawn(ConfigFactory.load("GalilHcd.conf"), wiring)
}