package csw.proto.galil.assembly

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
import csw.services.location.models.ComponentId
import csw.services.location.models.ComponentType.{Assembly, HCD}
import csw.services.location.models.Connection.AkkaConnection
import csw.services.logging.scaladsl.{ComponentLogger, LoggingSystemFactory}

import scala.async.Async._
import scala.concurrent.duration.DurationLong
import scala.concurrent.{ExecutionContextExecutor, Future}

// Base trait for Galil Assembly domain messages
sealed trait GalilAssemblyDomainMsg extends DomainMsg

// Add messages here...

// Temporary logger, until one is provided by the API
object GalilAssemblyLogger extends ComponentLogger("GalilAssembly")

private class GalilAssemblyWiring extends ComponentWiring[GalilAssemblyDomainMsg] {
  override def handlers(ctx: ActorContext[ComponentMsg],
                        componentInfo: ComponentInfo,
                        pubSubRef: ActorRef[PubSub.PublisherMsg[CurrentState]]
                       ): ComponentHandlers[GalilAssemblyDomainMsg] = new GalilAssemblyHandlers(ctx, componentInfo, pubSubRef)
}

private class GalilAssemblyHandlers(ctx: ActorContext[ComponentMsg], componentInfo: ComponentInfo,
                                    pubSubRef: ActorRef[PubSub.PublisherMsg[CurrentState]])
  extends ComponentHandlers[GalilAssemblyDomainMsg](ctx, componentInfo, pubSubRef) with GalilAssemblyLogger.Simple {

  implicit val ec: ExecutionContextExecutor = ctx.executionContext

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
  }

  override def onRun(): Unit = log.debug("onRun called")

  override def onShutdown(): Unit = log.debug("onShutdown called")

  override def onRestart(): Unit = log.debug("onRestart called")

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def onDomainMsg(galilMsg: GalilAssemblyDomainMsg): Unit = galilMsg match {
    case x => log.debug(s"onDomainMsg called: $x")
  }

  override def onControlCommand(commandMsg: CommandMsg): Validation = {
    log.debug(s"onControlCommand called: $commandMsg")
    Validations.Valid
  }
}

object GalilAssemblyApp extends App with GalilAssemblyLogger.Simple {
  def startLogging(): Unit = {
    val host = InetAddress.getLocalHost.getHostName
    val system = akka.actor.ActorSystem()
    LoggingSystemFactory.start("GalilAssembly", "0.1", host, system)
    log.debug("Starting Galil Assembly")
  }

  def startAssembly(): Unit = {
    // XXX This should be read from a config file
//    val assemblyInfo = ComponentInfo("GalilAssembly",
//      Assembly,
//      "wfos",
//      "csw.proto.galil.assembly.GalilAssemblyWiring",
//      Set(AkkaConnection(ComponentId("GalilHcd", HCD)))
//    )

//    val system = akka.typed.ActorSystem(akka.typed.scaladsl.Actor.empty, "GalilAssembly")
//    implicit val timeout: Timeout = Timeout(2.seconds)
//    val f = system.systemActorOf(SupervisorBehaviorFactory.behavior(assemblyInfo), "GalilAssemblySupervisor")

    Component.createStandalone(ConfigFactory.load("GalilAssembly.conf"))

//    // XXX temp: Until Supervisor.registerWithLocationService() is implemented...
//    // Start a dummy assembly client class that sends it a Submit message
//    import system.executionContext
//    f.foreach { supervisor =>
//      DummyAssemblyClient.start(supervisor)
//    }
  }

  startLogging()
  startAssembly()
}