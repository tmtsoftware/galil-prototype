package csw.proto.galil.hcd

import java.net.InetAddress

import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.common.ccs.{Validation, Validations}
import csw.common.framework.models.ComponentInfo
import csw.common.framework.models.RunningMsg.DomainMsg
import csw.common.framework.models._
import csw.common.framework.scaladsl.{Component, ComponentHandlers, ComponentWiring, SupervisorBehaviorFactory}
import csw.param.states.CurrentState
import csw.services.location.models.ComponentType.HCD
import csw.services.logging.scaladsl.{ComponentLogger, LoggingSystemFactory}

import scala.async.Async._
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContextExecutor, Future}

// Base trait for Galil HCD domain messages
sealed trait GalilHcdDomainMsg extends DomainMsg

// Add messages here...

// Temporary logger, until one is provided by the API
object GalilHcdLogger extends ComponentLogger("GalilHcd")

private class GalilHcdWiring extends ComponentWiring[GalilHcdDomainMsg] {
  override def handlers(ctx: ActorContext[ComponentMsg],
                        componentInfo: ComponentInfo,
                        pubSubRef: ActorRef[PubSub.PublisherMsg[CurrentState]]
                       ): ComponentHandlers[GalilHcdDomainMsg] = new GalilHcdHandlers(ctx, componentInfo, pubSubRef)
}

private class GalilHcdHandlers(ctx: ActorContext[ComponentMsg], componentInfo: ComponentInfo,
                               pubSubRef: ActorRef[PubSub.PublisherMsg[CurrentState]])
  extends ComponentHandlers[GalilHcdDomainMsg](ctx, componentInfo, pubSubRef) with GalilHcdLogger.Simple {

  implicit val ec: ExecutionContextExecutor = ctx.executionContext

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
  }

  override def onRun(): Unit = log.debug("onRun called")

  override def onShutdown(): Unit = log.debug("onShutdown called")

  override def onRestart(): Unit = log.debug("onRestart called")

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def onDomainMsg(galilMsg: GalilHcdDomainMsg): Unit = galilMsg match {
    case x => log.debug(s"onDomainMsg called: $x")
  }

  override def onControlCommand(commandMsg: CommandMsg): Validation = {
    log.debug(s"onControlCommand called: $commandMsg")
    Validations.Valid
  }
}

object GalilHcdApp extends App with GalilHcdLogger.Simple {
  def startLogging(): Unit = {
    val host = InetAddress.getLocalHost.getHostName
    val system = akka.actor.ActorSystem()
    LoggingSystemFactory.start("GalilHcd", "0.1", host, system)
    log.debug("Starting Galil HCD")
  }

  def startHcd(): Unit = {
//    // XXX This should be read from a config file
//    val hcdInfo = ComponentInfo("GalilHcd",
//      HCD,
//      "wfos",
//      "csw.proto.galil.hcd.GalilHcdWiring")
//
//    val system = akka.typed.ActorSystem(akka.typed.scaladsl.Actor.empty, "GalilHcd")
//    implicit val timeout: Timeout = Timeout(2.seconds)
//    val f = system.systemActorOf(SupervisorBehaviorFactory.behavior(hcdInfo), "GalilHcdSupervisor")

    Component.createStandalone(ConfigFactory.load("GalilHcd.conf"))


    //    // XXX temp: Until Supervisor.registerWithLocationService() is implemented...
    //    // Start a dummy HCD client class that sends it a Submit message
    //    import system.executionContext
    //    f.foreach { supervisor =>
    //      DummyHcdClient.start(supervisor)
    //    }
  }

  startLogging()
  startHcd()
}