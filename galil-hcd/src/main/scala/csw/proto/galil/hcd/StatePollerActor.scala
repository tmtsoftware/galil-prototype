package csw.proto.galil.hcd


import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{Behaviors, MutableBehavior, TimerScheduler}
import akka.util.ByteString
import csw.framework.scaladsl.CurrentStatePublisher
import csw.messages.params.generics.{KeyType, Parameter}
import csw.messages.params.models.Prefix
import csw.messages.params.states.{CurrentState, StateName}
import csw.proto.galil.io.{DataRecord, GalilIo, GalilIoTcp}
import csw.services.logging.scaladsl.LoggerFactory

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
  def behavior(galilConfig: GalilConfig, currentStatePublisher: CurrentStatePublisher, loggerFactory: LoggerFactory): Behavior[StatePollerMessage] =
    Behaviors.withTimers(timers ⇒ StatePollerActor(timers, galilConfig, currentStatePublisher, loggerFactory))

  val currentStateName = StateName("GalilState")
}

case class StatePollerActor(timer: TimerScheduler[StatePollerMessage],
                              galilConfig: GalilConfig,
                              currentStatePublisher: CurrentStatePublisher,
                              loggerFactory: LoggerFactory)
    extends MutableBehavior[StatePollerMessage] {

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
    val prefix = Prefix("tcs.test")

    //keys
    val timestampKey = KeyType.TimestampKey.make("timestampKey")



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

      //log.debug(s"dataRecord = $dataRecord")

      val motorPositions = ArrayBuffer[Int]()
      val positionErrors = ArrayBuffer[Int]()
      val referencePositions = ArrayBuffer[Int]()
      val statuses = ArrayBuffer[Short]()
      val velocities = ArrayBuffer[Int]()
      val torques = ArrayBuffer[Int]()

      dataRecord.axisStatuses.foreach((axis: DataRecord.GalilAxisStatus) => {

        motorPositions += axis.motorPosition
        positionErrors += axis.positionError
        referencePositions += axis.referencePosition
        statuses += axis.status
        velocities += axis.velocity
        torques += axis.torque

      })

      val motorPositionParam = DataRecord.GalilAxisStatus.motorPositionKey.set(motorPositions.toArray)
      val positionErrorParam = DataRecord.GalilAxisStatus.positionErrorKey.set(positionErrors.toArray)
      val referencePositionParam = DataRecord.GalilAxisStatus.referencePositionKey.set(referencePositions.toArray)
      val statusParam = DataRecord.GalilAxisStatus.statusKey.set(statuses.toArray)
      val velocityParam = DataRecord.GalilAxisStatus.velocityKey.set(velocities.toArray)
      val torqueParam = DataRecord.GalilAxisStatus.torqueKey.set(torques.toArray)

      val errorCodeParam = DataRecord.GeneralState.errorCodeKey.set(dataRecord.generalState.errorCode)

      val timestamp = timestampKey.set(Instant.now)


      // FIXME: matcher test only
      val testParam: Parameter[Int] = KeyType.IntKey.make("encoder").set(100)



      //create CurrentState and use sequential add
      val currentState = CurrentState(prefix, StatePollerActor.currentStateName)
        .add(motorPositionParam)
        .add(positionErrorParam)
        .add(referencePositionParam)
        .add(statusParam)
        .add(velocityParam)
        .add(torqueParam)
        .add(errorCodeParam)
        .add(timestamp)
        .add(testParam)

      currentStatePublisher.publish(currentState)

    }

    private def queryDataRecord(): DataRecord = {
      //log.debug(s"Sending 'QR to Galil")
      val responses = galilIo.send("QR")

      val bs: ByteString  = responses.head._2
      //log.debug(s"Data Record size: ${bs.size})")

      // parse the data record
      val dr = DataRecord(bs)
      if (responses.lengthCompare(1) != 0)
        throw new RuntimeException(s"Received ${responses.size} responses to Galil QR")

      dr
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
