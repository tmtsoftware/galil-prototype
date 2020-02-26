package csw.proto.galil.assembly

import akka.actor.typed.scaladsl.ActorContext
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import csw.command.api.scaladsl.CommandService
import csw.command.client.CommandServiceFactory
import csw.command.client.messages.TopLevelActorMessage
import csw.framework.deploy.containercmd.ContainerCmd
import csw.framework.models.CswContext
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.location.api.models.{AkkaLocation, LocationRemoved, LocationUpdated, TrackingEvent}
import csw.params.commands.CommandResponse.{Completed, Error, SubmitResponse, ValidateCommandResponse}
import csw.params.commands.{CommandResponse, ControlCommand, Setup}
import csw.params.core.models.Id
import csw.prefix.models.Subsystem.CSW
import csw.time.core.models.UTCTime

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

  override def validateCommand(runId: Id, controlCommand: ControlCommand): ValidateCommandResponse = {
    CommandResponse.Accepted(runId)
  }

  override def onSubmit(runId: Id, controlCommand: ControlCommand): SubmitResponse = {
    log.debug(s"onSubmit called: $controlCommand")
    forwardCommandToHcd(runId, controlCommand).map {
      case c @ Completed(_, result) =>
        log.info(s"submit Completed.  result = $result")
        commandResponseManager.updateCommand(c)
      case x =>
        log.error(s"submit failed.")
        commandResponseManager.updateCommand(x)
    }
    CommandResponse.Started(runId)
  }

  override def onOneway(runId: Id, controlCommand: ControlCommand): Unit = {
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
  private def forwardCommandToHcd(runId: Id, controlCommand: ControlCommand): Future[SubmitResponse] = {
    implicit val timeout: Timeout = Timeout(3.seconds)
    val setup = Setup(componentInfo.prefix,
      controlCommand.commandName,
      controlCommand.maybeObsId,
      controlCommand.paramSet)
    galilHcd.get match {
      case hcd: CommandService => hcd.submit(setup)
      case _ => Future(Error(runId, "HCD not found"))
    }
  }

  override def onDiagnosticMode(startTime: UTCTime, hint: String): Unit = {}

  override def onOperationsMode(): Unit = {}
}

// Start assembly from the command line using GalilAssembly.conf resource file
object GalilAssemblyApp extends App {
  val defaultConfig = ConfigFactory.load("GalilAssembly.conf")
  ContainerCmd.start("galil.assembly.GalilAssembly", CSW, args, Some(defaultConfig))
}
