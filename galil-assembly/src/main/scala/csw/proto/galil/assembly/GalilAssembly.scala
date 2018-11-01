package csw.proto.galil.assembly

import akka.actor.Scheduler
import akka.actor.typed.scaladsl.ActorContext
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.location.api.models.{
  AkkaLocation,
  LocationRemoved,
  LocationUpdated,
  TrackingEvent
}
import csw.params.commands.CommandResponse.{
  Error,
  SubmitResponse,
  ValidateCommandResponse
}
import csw.params.commands.{CommandResponse, ControlCommand, Setup}

import scala.async.Async._
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

// Add messages here...

private class GalilAssemblyBehaviorFactory extends ComponentBehaviorFactory {
  override def handlers(
      ctx: ActorContext[TopLevelActorMessage],
      cswCtx: CswContext
  ): ComponentHandlers =
    new GalilAssemblyHandlers(ctx, cswCtx)
}

private class GalilAssemblyHandlers(ctx: ActorContext[TopLevelActorMessage],
                                    cswServices: CswContext)
    extends ComponentHandlers(ctx, cswServices) {

  import cswServices._

  implicit val ec: ExecutionContextExecutor = ctx.executionContext
  private val log = loggerFactory.getLogger
  private var galilHcd: Option[CommandService] = None

  override def initialize(): Future[Unit] = async {
    log.debug("Initialize called")
  }

  override def validateCommand(
      controlCommand: ControlCommand): ValidateCommandResponse = {
    CommandResponse.Accepted(controlCommand.runId)
  }

  override def onSubmit(controlCommand: ControlCommand): SubmitResponse = {
    log.debug(s"onSubmit called: $controlCommand")
    forwardCommandToHcd(controlCommand)
    CommandResponse.Started(controlCommand.runId)
  }

  override def onOneway(controlCommand: ControlCommand): Unit = {
    log.debug(s"onOneway called: $controlCommand")
  }

  override def onShutdown(): Future[Unit] = async {
    log.debug("onShutdown called")
  }

  override def onGoOffline(): Unit = log.debug("onGoOffline called")

  override def onGoOnline(): Unit = log.debug("onGoOnline called")

  override def onLocationTrackingEvent(trackingEvent: TrackingEvent): Unit = {
    log.debug(s"onLocationTrackingEvent called: $trackingEvent")
    trackingEvent match {
      case LocationUpdated(location) =>
        galilHcd = Some(
          CommandServiceFactory.make(location.asInstanceOf[AkkaLocation])(
            ctx.system))
      case LocationRemoved(_) =>
        galilHcd = None
    }
  }

  // For testing, forward command to HCD and complete this command when it completes
  private def forwardCommandToHcd(controlCommand: ControlCommand): Unit = {
    implicit val scheduler: Scheduler = ctx.system.scheduler
    implicit val timeout: Timeout = Timeout(3.seconds)
    galilHcd.foreach { hcd =>
      val setup = Setup(controlCommand.source,
                        controlCommand.commandName,
                        controlCommand.maybeObsId,
                        controlCommand.paramSet)
      commandResponseManager.addSubCommand(controlCommand.runId, setup.runId)

      val f = for {
        response <- hcd.submit(setup)
      } yield {
        log.info(s"response = $response")
        commandResponseManager.updateSubCommand(response)
      }
      f.recover {
        case ex =>
          commandResponseManager.updateSubCommand(
            Error(setup.runId, ex.toString))
      }
    }
  }
}

// Start assembly from the command line using GalilAssembly.conf resource file
object GalilAssemblyApp extends App {
  val defaultConfig = ConfigFactory.load("GalilAssembly.conf")
  ContainerCmd.start("GalilAssembly", args, Some(defaultConfig))
}
