package csw.proto.galil.assembly

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

// Base trait for Galil Assembly domain messages
sealed trait GalilAssemblyDomainMessage extends DomainMessage

// Add messages here...

object GalilAssemblyLogger extends ComponentLogger("GalilAssembly")

private class GalilAssemblyBehaviorFactory extends ComponentBehaviorFactory[GalilAssemblyDomainMessage] {
  override def handlers(ctx: ActorContext[ComponentMessage],
                        componentInfo: ComponentInfo,
                        pubSubRef: ActorRef[PubSub.PublisherMessage[CurrentState]]
                       ): ComponentHandlers[GalilAssemblyDomainMessage] = new GalilAssemblyHandlers(ctx, componentInfo, pubSubRef)
}

private class GalilAssemblyHandlers(ctx: ActorContext[ComponentMessage], componentInfo: ComponentInfo,
                                    pubSubRef: ActorRef[PubSub.PublisherMessage[CurrentState]])
  extends ComponentHandlers[GalilAssemblyDomainMessage](ctx, componentInfo, pubSubRef) with GalilAssemblyLogger.Simple {

  implicit val ec: ExecutionContextExecutor = ctx.executionContext

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
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
}

object GalilAssemblyApp extends App with GalilAssemblyLogger.Simple {
  val host = InetAddress.getLocalHost.getHostName
//  val system = akka.actor.ActorSystem()
  val system: ActorSystem = ClusterAwareSettings.system
  LoggingSystemFactory.start("GalilAssembly", "0.1", host, system)
  log.debug("Starting Galil Assembly")
  val wiring  = FrameworkWiring.make(system)
  Standalone.spawn(ConfigFactory.load("GalilAssembly.conf"), wiring)
}