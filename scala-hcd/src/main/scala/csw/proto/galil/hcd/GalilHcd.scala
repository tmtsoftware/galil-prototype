package csw.proto.galil.hcd

import java.net.InetAddress

import akka.actor.Scheduler
import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import akka.util.Timeout
import csw.common.ccs.Validation
import csw.common.framework.internal.supervisor.Supervisor
import csw.common.framework.models.Component.{ComponentInfo, HcdInfo, RegisterOnly}
import csw.common.framework.models.PubSub.PublisherMsg
import csw.common.framework.models.RunningMsg.DomainMsg
import csw.common.framework.models._
import csw.common.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.param.states.CurrentState
import csw.services.location.models.ConnectionType.AkkaType
import csw.services.logging.scaladsl.{ComponentLogger, LoggingSystemFactory}

import scala.async.Async._
import scala.concurrent.duration.{DurationLong, FiniteDuration}
import scala.concurrent.{ExecutionContextExecutor, Future}

// Base trait for Galil HCD domain messages
sealed trait GalilMsg extends DomainMsg
// Add messages here...

object GalilLogger extends ComponentLogger("GalilHcd")

class GalilHcdBehaviorFactory extends ComponentBehaviorFactory[GalilMsg] {
  override def make(ctx: ActorContext[ComponentMsg],
                    componentInfo: ComponentInfo,
                    pubSubRef: ActorRef[PublisherMsg[CurrentState]]): ComponentHandlers[GalilMsg] =
    new GalilHcdHandlers(ctx, componentInfo)
}

class GalilHcdHandlers(ctx: ActorContext[ComponentMsg], componentInfo: ComponentInfo)
  extends ComponentHandlers[GalilMsg](ctx, componentInfo) with GalilLogger.Simple {

  implicit val timeout: Timeout = Timeout(2.seconds)
  implicit val scheduler: Scheduler         = ctx.system.scheduler
  implicit val ec: ExecutionContextExecutor = ctx.executionContext

  var pubSubRef: ActorRef[PubSub[CurrentState]] = ctx.system.deadLetters

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
  }

  override def onRun(): Unit = log.debug("onRun called")

  override def onShutdown(): Unit = log.debug("onShutdown called")

  override def onRestart(): Unit = log.debug("onRestart called")

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def onDomainMsg(galilMsg: GalilMsg): Unit = galilMsg match {
    case x => log.debug(s"onDomainMsg called: $x")
  }

  override def onControlCommand(commandMsg: CommandMsg): Validation.Validation = {
    log.debug(s"onControlCommand called: $commandMsg")
    Validation.Valid
  }
}

object GalilHcdApp extends App with GalilLogger.Simple {
  def startLogging(): Unit = {
    val host = InetAddress.getLocalHost.getHostName
    val system = akka.actor.ActorSystem()
    LoggingSystemFactory.start("GalilHcd", "0.1", host, system)
    log.debug("Starting Galil HCD")
  }

  def startHcd(): Unit = {
    // XXX put this in a config file?
    val hcdInfo = HcdInfo("GalilHcd",
      "wfos",
      "csw.proto.galil.hcd.GalilHcdBehaviorFactory",
      RegisterOnly,
      Set(AkkaType),
      FiniteDuration(5, "seconds"))

    val system = akka.typed.ActorSystem("GalilHcd", akka.typed.scaladsl.Actor.empty)
    implicit val timeout: Timeout = Timeout(2.seconds)
    val f = system.systemActorOf(Supervisor.behavior(hcdInfo, new GalilHcdBehaviorFactory()), "GalilHcdSupervisor")

    // XXX temp: Until Supervisor.registerWithLocationService() is implemented...
    // Start a dummy HCD client class that sends it a Submit message
    import system.executionContext
    f.foreach { supervisor =>
      DummyHcdClient.start(supervisor)
    }
  }

  startLogging()
  startHcd()
}