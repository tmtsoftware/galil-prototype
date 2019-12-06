package csw.proto.galil.hcd


import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{AbstractBehavior, Behaviors, TimerScheduler}
import akka.util.ByteString
import csw.command.client.models.framework.ComponentInfo
import csw.framework.CurrentStatePublisher
import csw.logging.client.scaladsl.LoggerFactory
import csw.params.core.generics.KeyType
import csw.params.core.states.{CurrentState, StateName}
import csw.proto.galil.io.DataRecord.GalilAxisStatus
import csw.proto.galil.io.{DataRecord, GalilIo, GalilIoTcp}
import csw.time.core.models.UTCTime

import scala.concurrent.duration.Duration

// Add messages here
sealed trait StatePollerMessage

object StatePollerMessage {

  case class StartMessage()   extends StatePollerMessage
  case class StopMessage()    extends StatePollerMessage
  case class PublishMessage() extends StatePollerMessage

}

private case object TimerKey

object StatePollerActor {
  def behavior(galilConfig: GalilConfig, componentInfo: ComponentInfo, currentStatePublisher: CurrentStatePublisher, loggerFactory: LoggerFactory): Behavior[StatePollerMessage] =
    Behaviors.withTimers(timers ⇒ StatePollerActor(timers, galilConfig, componentInfo, currentStatePublisher, loggerFactory))

  val currentStateName = StateName("GalilState")
}

case class StatePollerActor(timer: TimerScheduler[StatePollerMessage],
                            galilConfig: GalilConfig,
                            componentInfo: ComponentInfo,
                            currentStatePublisher: CurrentStatePublisher,
                            loggerFactory: LoggerFactory)
  extends AbstractBehavior[StatePollerMessage] {

  import csw.proto.galil.hcd.StatePollerMessage._

  private val log = loggerFactory.getLogger

  log.info("CREATING STATE POLLER ACTOR")


  private val galilIo = connectToGalil()
  verifyGalil()

  // Connect to Galil device and throw error if that doesn't work
  private def connectToGalil(): GalilIo = {
    try {
      GalilIoTcp(galilConfig.host, galilConfig.port)
    } catch {
      case ex: Exception =>
        log.error(s"Failed to connect to Galil device at ${galilConfig.host}:${galilConfig.port}")
        throw ex
    }
  }

  // Check that there is a Galil device on the other end of the socket (Is there good Galil command to use here?)
  private def verifyGalil(): Unit = {
    val s = galilSend("")
    if (s.nonEmpty)
      throw new IOException(s"Unexpected response to empty galil command: '$s': " +
        s"Check if Galil device is really located at ${galilConfig.host}:${galilConfig.port}")
  }



  //prefix
  val prefix = componentInfo.prefix


  //keys
  val timestampKey = KeyType.UTCTimeKey.make("timestampKey")
  val stepperPosKey = KeyType.IntKey.make("stepperPos")



  override def onMessage(msg: StatePollerMessage): Behavior[StatePollerMessage] = {
    msg match {
      case (x: StartMessage)   => doStart(x)
      case (y: StopMessage)    => doStop(y)
      case (y: PublishMessage) => doPublishMessage(y)
      case _                   => log.error(s"unhandled message in StatePublisher Actor onMessage: $msg")
    }
    this
  }

  private def doStart(message: StartMessage): Unit = {
    log.info("start message received")

    timer.startPeriodicTimer(TimerKey, PublishMessage(), Duration.create(1, TimeUnit.SECONDS))

    log.info("start message completed")

  }

  private def doStop(message: StopMessage): Unit = {

    log.info("stop message completed")

  }

  private def doPublishMessage(message: PublishMessage): Unit = {

    import scala.collection.mutable.ArrayBuffer

    log.debug("publishMessage received")

    val dataRecord = queryDataRecord()

    val stepperPositions: Array[Int] = queryStepperPositions()

    val timestamp = timestampKey.set(UTCTime.now)

    val stepperPosParam = stepperPosKey.set(stepperPositions)



    //create CurrentState and use sequential add
    val currentState = CurrentState(prefix, StatePollerActor.currentStateName, dataRecord.toParamSet)
      .add(stepperPosParam)
      .add(timestamp)

    currentStatePublisher.publish(currentState)

  }

  private def queryDataRecord(): DataRecord = {
    log.debug(s"Sending 'QR to Galil")
    val responses = galilIo.send("QR")

    val bs: ByteString  = responses.head._2
    log.debug(s"Data Record size: ${bs.size})")

    // parse the data record
    val dr = DataRecord(bs)
    if (responses.lengthCompare(1) != 0)
      throw new RuntimeException(s"Received ${responses.size} responses to Galil QR")



    dr
  }

  private def queryStepperPositions(): Array[Int] = {

    // add motor positions for the steppers
    log.debug(s"Sending 'TD to Galil")
    val tdResp = galilIo.send("TD")

    val tdbs: ByteString = tdResp.head._2
    val tdString = tdbs.utf8String

    val tdArr: Array[Int] = tdString.replaceAll("\\s", "").split(",").map(_.toInt)

    log.info("tdArr = " + tdArr);

    tdArr
  }




  private def galilSend(cmd: String): String = {
    //log.debug(s"Sending '$cmd' to Galil")
    val responses = galilIo.send(cmd)
    if (responses.lengthCompare(1) != 0)
      throw new RuntimeException(s"Received ${responses.size} responses to Galil $cmd")
    val resp = responses.head._2.utf8String
    //log.debug(s"Response from Galil: $resp")
    resp
  }

}

