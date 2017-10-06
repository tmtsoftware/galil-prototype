package csw.proto.galil.assembly

import java.io.FileWriter
import java.nio.file.{Files, Path}

import akka.typed.ActorRef
import akka.typed.scaladsl.ActorContext
import csw.apps.containercmd.ContainerCmd
import csw.framework.scaladsl.{ComponentBehaviorFactory, ComponentHandlers}
import csw.messages._
import csw.messages.PubSub.PublisherMessage
import csw.messages.RunningMessage.DomainMessage
import csw.messages.ccs.{Validation, ValidationIssue, Validations}
import csw.messages.framework.ComponentInfo
import csw.messages.location.TrackingEvent
import csw.messages.params.states.CurrentState
import csw.services.location.scaladsl.LocationService
import csw.services.logging.scaladsl.ComponentLogger

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

  override def onSetup(commandMessage: CommandMessage): Validation = {
    log.debug(s"onSetup called: $commandMessage")
    Validations.Valid
  }

  override def onObserve(commandMessage: CommandMessage): Validation =  {
    log.debug(s"onObserve called: $commandMessage")
    Validations.Invalid(ValidationIssue.UnsupportedCommandIssue("Observe  not supported"))
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

  override protected def maybeComponentName(): Option[String] = Some("GalilAssembly")

}


// Start assembly from the command line using GalilAssembly.conf resource file
object GalilAssemblyApp extends App {
  // XXX TODO: FIXME
  def createStandaloneTmpFile(resourceFileName: String): Path = {
    val hcdConfiguration       = scala.io.Source.fromResource(resourceFileName).mkString
    val standaloneConfFilePath = Files.createTempFile("csw-temp-resource", ".conf")
    val fileWriter             = new FileWriter(standaloneConfFilePath.toFile, true)
    fileWriter.write(hcdConfiguration)
    fileWriter.close()
    standaloneConfFilePath
  }

  // See See DEOPSCSW-171: Starting component from command line.
  val path = createStandaloneTmpFile("GalilAssembly.conf")
  val defaultArgs = if (args.isEmpty) Array("--local",  "--standalone",  path.toString) else args
  ContainerCmd.start("GalilAssembly", defaultArgs)
}
