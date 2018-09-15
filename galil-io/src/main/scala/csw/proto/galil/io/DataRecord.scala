package csw.proto.galil.io

import java.nio.{ByteBuffer, ByteOrder}

import akka.util.ByteString
import csw.params.commands.CommandResponse.CompletedWithResult
import csw.params.commands.{CommandResponse, Result}
import csw.params.core.generics.{Key, KeyType, Parameter}
import csw.params.core.models._
import csw.proto.galil.io.DataRecord._
import play.api.libs.json.{Json, OFormat}


/**
  * The data record returned from the Galil QR command
  *
  * @param header       the parsed data from the 4 byte header
  * @param generalState data from byte 4 to 55 (sample number to amplifier status)
  * @param axisStatuses an array of axis status, one for each value of axes ('A' to 'H'), Use default (0) values if axis not present
  */
case class DataRecord(header: Header, generalState: GeneralState, axisStatuses: Array[GalilAxisStatus]) {

  override def toString: String = {
    val status = axes.zip(axisStatuses).flatMap { p =>
      if (header.blocksPresent.contains(p._1.toString))
        Some((p._1, p._2))
      else None
    }.map(p => s"\nAxis ${p._1} Status:${p._2.toString}").mkString("\n")
    s"$header\n$generalState\n$status"
  }

  /**
    * For use by the galil simulator: Given a DataRecord, returns the ByteString representation, as
    * returned by the device.
    */
  def toByteBuffer: ByteBuffer = {
    val buffer = ByteBuffer.allocateDirect(header.recordSize + 1).order(ByteOrder.LITTLE_ENDIAN)

    header.write(buffer)
    generalState.write(buffer)

    axes.zip(axisStatuses).foreach { p =>
      if (header.blocksPresent.contains(p._1.toString))
        p._2.write(buffer)
    }

    buffer.flip().asInstanceOf[ByteBuffer]
  }

  def toParamSet: Set[Parameter[_]] = {
    val status = axes.zip(axisStatuses).flatMap { p =>
      val axis = p._1.toString
      if (header.blocksPresent.contains(axis))
        Some(KeyType.StructKey.make(axis).set(Struct(p._2.toParamSet)))
      else None
    }
    header.toParamSet ++ generalState.toParamSet ++ status.toSet
  }
}

object DataRecord {

  /**
    * Param set key for getting the raw data record bytes (with getDataRecordRaw command in GalilCommands.conf)
    */
  val key: Key[ArrayData[Byte]] = KeyType.ByteArrayKey.make("dataRecord")

  // JSON support
  implicit val headerJsonFormat: OFormat[Header] = Json.format[Header]
  implicit val generalStateJsonFormat: OFormat[GeneralState] = Json.format[GeneralState]
  implicit val galilAxisStatusJsonFormat: OFormat[GalilAxisStatus] = Json.format[GalilAxisStatus]
  implicit val dataRecordJsonFormat: OFormat[DataRecord] = Json.format[DataRecord]


  /**
    * Possible Galil Axis (may not all be present)
    */
  val axes: List[Char] = ('A' to 'H').toList

  /**
    * The 4 byte Galil header
    *
    * @param blocksPresent Contains the char name of the block for each block present, or empty string if block not present
    * @param recordSize    size of the data record, including the header
    */
  case class Header(blocksPresent: List[String], recordSize: Int) {
    import Header._

    /**
      * Appends the header to the buffer in the documented Galil format
      */
    def write(buffer: ByteBuffer): Unit = {
      val byte0 = blocksPresent.take(3).zipWithIndex.map(p => setBit(p._2, p._1.nonEmpty)).sum
      val byte1 = blocksPresent.drop(3).zipWithIndex.map(p => setBit(p._2, p._1.nonEmpty)).sum
      buffer.put(byte0.asInstanceOf[Byte])
      buffer.put(byte1.asInstanceOf[Byte])
      buffer.putShort(recordSize.asInstanceOf[Short])
    }

    override def toString: String =
      s"""
         |Blocks present:   ${blocksPresent.mkString(" ")}
         |Data record size: $recordSize
       """.stripMargin

    def toParamSet: Set[Parameter[_]] = {
      Set(blocksPresentKey.set(blocksPresent.toArray),
        recordSizeKey.set(recordSize))
    }

  }

  object Header {
    val recordSizeKey: Key[Int] = KeyType.IntKey.make("recordSize")
    val blocksPresentKey: Key[String] = KeyType.StringKey.make("blocksPresent")

    /**
      * Initialze the header from the given byte buffer
      */
    def apply(buffer: ByteBuffer): Header = {
      val byte0 = buffer.get
      val byte1 = buffer.get

      def getBlock(num: Byte, i: Int, s: String): String = if (getBit(num, i)) s else ""

      val blocksPresent = List(
        getBlock(byte0, 0, "S"),
        getBlock(byte0, 1, "T"),
        getBlock(byte0, 2, "I"),
        getBlock(byte1, 0, "A"),
        getBlock(byte1, 1, "B"),
        getBlock(byte1, 2, "C"),
        getBlock(byte1, 3, "D"),
        getBlock(byte1, 4, "E"),
        getBlock(byte1, 5, "F"),
        getBlock(byte1, 6, "G"),
        getBlock(byte1, 7, "H"))
      val recordSize = buffer.getShort() & 0xFFFF
      Header(blocksPresent, recordSize)
    }

    /**
      * Initialze from the result of a command (See CompletedWithResult)
      */
    def apply(result: Result): Header = {
      val blocksPresent = result.get(blocksPresentKey).get.values.toList
      val recordSize = result.get(recordSizeKey).get.head
      Header(blocksPresent, recordSize)
    }
  }

  case class GeneralState(sampleNumber: Short,
                          inputs: Array[Byte],
                          outputs: Array[Byte],
                          ethernetHandleStatus: Array[Byte],
                          errorCode: Byte,
                          threadStatus: Byte,
                          amplifierStatus: Int,
                          contourModeSegmentCount: Int,
                          contourModeBufferSpaceRemaining: Short,
                          sPlaneSegmentCount: Short,
                          sPlaneMoveStatus: Short,
                          sPlaneDistanceTraveled: Int,
                          sPlaneBufferSpaceRemaining: Short,
                          tPlaneSegmentCount: Short,
                          tPlaneMoveStatus: Short,
                          tPlaneDistanceTraveled: Int,
                          tPlaneBufferSpaceRemaining: Short) {

    import GeneralState._

    /**
      * Appends the GeneralState to the given ByteBuffer in the documented Galil format
      */
    def write(buffer: ByteBuffer): Unit = {

      buffer.putShort(sampleNumber)
        .put(inputs)
        .put(outputs)

      buffer.position(buffer.position() + 16)

      buffer.put(ethernetHandleStatus)
        .put(errorCode)
        .put(threadStatus)
        .putInt(amplifierStatus)
        .putInt(contourModeSegmentCount)
        .putShort(contourModeBufferSpaceRemaining)
        .putShort(sPlaneSegmentCount)
        .putShort(sPlaneMoveStatus)
        .putInt(sPlaneDistanceTraveled)
        .putShort(sPlaneBufferSpaceRemaining)
        .putShort(tPlaneSegmentCount)
        .putShort(tPlaneMoveStatus)
        .putInt(tPlaneDistanceTraveled)
        .putShort(tPlaneBufferSpaceRemaining)
    }

    private def toBinaryString(a: Array[Byte]) = a.map(i => i.toBinaryString).mkString(" ")

    override def toString: String =
      s"""
         |Sample number:                  ${sampleNumber & 0xFFFF}
         |Inputs:                         ${toBinaryString(inputs)}
         |Outputs:                        ${toBinaryString(outputs)}
         |Ethernet handle status:         ${ethernetHandleStatus.map(_ & 0xFF).mkString(", ")}
         |Error code:                     $errorCode
         |Thread status:                  ${threadStatus.toBinaryString}
         |Amplifier status:               $amplifierStatus
         |Contour mode segment count:                     $contourModeSegmentCount,
         |contour mode buffer space remaining:            ${contourModeBufferSpaceRemaining & 0xFFFF},
         |S plane segment count of coordinated move:      ${sPlaneSegmentCount & 0xFFFF},
         |S plane coordinated move status:                ${sPlaneMoveStatus & 0xFFFF},
         |S plane distance traveled in coordinated move:  $sPlaneDistanceTraveled,
         |S plane buffer space remaining:                 ${sPlaneBufferSpaceRemaining & 0xFFFF},
         |T plane segment count of coordinated move:      ${tPlaneSegmentCount & 0xFFFF},
         |T plane coordinated move status:                ${tPlaneMoveStatus & 0xFFFF},
         |T plane distance traveled in coordinated move:  $tPlaneDistanceTraveled,
         |T plane buffer space remaining:                 ${tPlaneBufferSpaceRemaining & 0xFFFF},
       """.stripMargin

    def toParamSet: Set[Parameter[_]] = {
      Set(
        sampleNumberKey.set(sampleNumber),
        inputsKey.set(inputs),
        outputsKey.set(outputs),
        ethernetHandleStatusKey.set(ethernetHandleStatus),
        errorCodeKey.set(errorCode),
        threadStatusKey.set(threadStatus),
        amplifierStatusKey.set(amplifierStatus),
        contourModeSegmentCountKey.set(contourModeSegmentCount),
        contourModeBufferSpaceRemainingKey.set(contourModeBufferSpaceRemaining),
        sPlaneSegmentCountKey.set(sPlaneSegmentCount),
        sPlaneMoveStatusKey.set(sPlaneMoveStatus),
        sPlaneDistanceTraveledKey.set(sPlaneDistanceTraveled),
        sPlaneBufferSpaceRemainingKey.set(sPlaneBufferSpaceRemaining),
        tPlaneSegmentCountKey.set(tPlaneSegmentCount),
        tPlaneMoveStatusKey.set(tPlaneMoveStatus),
        tPlaneDistanceTraveledKey.set(tPlaneDistanceTraveled),
        tPlaneBufferSpaceRemainingKey.set(tPlaneBufferSpaceRemaining)
      )
    }

  }

  object GeneralState {
    val sampleNumberKey: Key[Short] = KeyType.ShortKey.make("sampleNumber")
    val inputsKey: Key[Byte] = KeyType.ByteKey.make("inputs")
    val outputsKey: Key[Byte] = KeyType.ByteKey.make("outputs")
    val ethernetHandleStatusKey: Key[Byte] = KeyType.ByteKey.make("ethernetHandleStatus")
    val errorCodeKey: Key[Byte] = KeyType.ByteKey.make("errorCode")
    val threadStatusKey: Key[Byte] = KeyType.ByteKey.make("threadStatus")
    val amplifierStatusKey: Key[Int] = KeyType.IntKey.make("amplifierStatus")
    val contourModeSegmentCountKey: Key[Int] = KeyType.IntKey.make("contourModeSegmentCount")
    val contourModeBufferSpaceRemainingKey: Key[Short] = KeyType.ShortKey.make("contourModeBufferSpaceRemaining")
    val sPlaneSegmentCountKey: Key[Int] = KeyType.IntKey.make("sPlaneSegmentCount")
    val sPlaneMoveStatusKey: Key[Int] = KeyType.IntKey.make("sPlaneMoveStatus")
    val sPlaneDistanceTraveledKey: Key[Int] = KeyType.IntKey.make("sPlaneDistanceTraveled")
    val sPlaneBufferSpaceRemainingKey: Key[Int] = KeyType.IntKey.make("sPlaneBufferSpaceRemaining")
    val tPlaneSegmentCountKey: Key[Int] = KeyType.IntKey.make("tPlaneSegmentCount")
    val tPlaneMoveStatusKey: Key[Int] = KeyType.IntKey.make("tPlaneMoveStatus")
    val tPlaneDistanceTraveledKey: Key[Int] = KeyType.IntKey.make("tPlaneDistanceTraveled")
    val tPlaneBufferSpaceRemainingKey: Key[Int] = KeyType.IntKey.make("tPlaneBufferSpaceRemaining")


    /**
      * Initializes from the given ByteBuffer in the documented Galil data record format
      */
    def apply(buffer: ByteBuffer, header: Header): GeneralState = {

      // QZ
      // 4, 52, 26, 36
      //Number of axes present
      //number of bytes in general block of data record
      //number of bytes in coordinate plane block of data record
      //Number of Bytes in each axis block of data record

      def getBytes(numBytes: Int): Array[Byte] = {
        val ar = Array.fill(numBytes)(0.toByte)
        buffer.get(ar)
        ar
      }

      val numAxes = header.blocksPresent.size
      val ioSize = if (numAxes > 4) 10 else 1

      // ADDR 04 - 05
      val sampleNumber = buffer.getShort()
      // ADDR 06 - 15
      val inputs = getBytes(ioSize)
      // ADDR 16 - 25
      val outputs = getBytes(ioSize)

      // ADDR 26 - 41 (reserved)
      buffer.position(buffer.position() + 16)

      // ADDR 42 - 49
      val ethernetHandleStatus = axes.map(_ => buffer.get).toArray
      //      val ethernetHandleStatus = axes.flatMap(axis =>  if (header.blocksPresent.contains(axis.toString)) Some(buffer.get) else None).toArray

      // ADDR 50
      val errorCode = buffer.get()

      // ADDR 51
      val threadStatus = buffer.get()

      // ADDR 52 - 55
      val amplifierStatus = buffer.getInt()

      // ADDR 56-59
      val countourModeSegmentCount = buffer.getInt()

      // ADDR 60-61
      val contourModeBufferSpaceRemaining = buffer.getShort()

      // TODO check for existence?

      // ADDR 62-63
      val sPlaneSegmentCount = buffer.getShort()

      // ADDR 64-65
      val sPlaneMoveStatus = buffer.getShort()

      // ADDR 66-69
      val sPlaneDistanceTraveled = buffer.getInt()

      // ADDR 70-71
      val sPlaneBufferSpaceRemaining = buffer.getShort()

      // TODO Check for existance

      // ADDR 72-73
      val tPlaneSegmentCount = buffer.getShort()

      // ADDR 74-75
      val tPlaneMoveStatus = buffer.getShort()

      // ADDR 76-79
      val tPlaneDistanceTraveled = buffer.getInt()

      // ADDR 80-81
      val tPlaneBufferSpaceRemaining = buffer.getShort()


      GeneralState(sampleNumber, inputs, outputs, ethernetHandleStatus, errorCode, threadStatus,
        amplifierStatus, countourModeSegmentCount, contourModeBufferSpaceRemaining,
        sPlaneSegmentCount, sPlaneMoveStatus, sPlaneDistanceTraveled, sPlaneBufferSpaceRemaining,
        tPlaneSegmentCount, tPlaneMoveStatus, tPlaneDistanceTraveled, tPlaneBufferSpaceRemaining)
    }

    /**
      * Creates a GeneralState from the result of a command (See CompletedWithResult)
      */
    def apply(result: Result): GeneralState = {
      val sampleNumber = result.get(sampleNumberKey).get.head
      val inputs = result.get(inputsKey).get.values
      val outputs = result.get(outputsKey).get.values
      val ethernetHandleStatus = result.get(ethernetHandleStatusKey).get.values
      val errorCode = result.get(errorCodeKey).get.head
      val threadStatus = result.get(threadStatusKey).get.head
      val amplifierStatus = result.get(amplifierStatusKey).get.head
      val countourModeSegmentCount = result.get(contourModeSegmentCountKey).get.head
      val contourModeBufferSpaceRemaining = result.get(contourModeBufferSpaceRemainingKey).get.head
      val sPlaneSegmentCount = result.get(sPlaneSegmentCountKey).get.head.toShort
      val sPlaneMoveStatus = result.get(sPlaneMoveStatusKey).get.head.toShort
      val sPlaneDistanceTraveled = result.get(sPlaneDistanceTraveledKey).get.head
      val sPlaneBufferSpaceRemaining = result.get(sPlaneBufferSpaceRemainingKey).get.head.toShort
      val tPlaneSegmentCount = result.get(tPlaneSegmentCountKey).get.head.toShort
      val tPlaneMoveStatus = result.get(tPlaneMoveStatusKey).get.head.toShort
      val tPlaneDistanceTraveled = result.get(tPlaneDistanceTraveledKey).get.head
      val tPlaneBufferSpaceRemaining = result.get(tPlaneBufferSpaceRemainingKey).get.head.toShort

      GeneralState(sampleNumber, inputs, outputs, ethernetHandleStatus, errorCode, threadStatus,
        amplifierStatus, countourModeSegmentCount, contourModeBufferSpaceRemaining,
        sPlaneSegmentCount, sPlaneMoveStatus, sPlaneDistanceTraveled, sPlaneBufferSpaceRemaining,
        tPlaneSegmentCount, tPlaneMoveStatus, tPlaneDistanceTraveled, tPlaneBufferSpaceRemaining)
    }

  }

  case class GalilAxisStatus(status: Short = 0, // unsigned
                             switches: Byte = 0, //unsigned
                             stopCode: Byte = 0, // unsigned
                             referencePosition: Int = 0,
                             motorPosition: Int = 0,
                             positionError: Int = 0,
                             auxiliaryPosition: Int = 0,
                             velocity: Int = 0,
                             torque: Int = 0,
                             analogInput: Short = 0,
                             hallInputStatus: Byte = 0, // unsigned
                             reservedByte: Byte = 0, //unsigned
                             userDefinedVariable: Int = 0) {
    import GalilAxisStatus._

    /**
      * Appends bytes to the buffer in the documented Galil format
      */
    def write(buffer: ByteBuffer): Unit = {
      buffer.putShort(status)
        .put(switches)
        .put(stopCode)
        .putInt(referencePosition)
        .putInt(motorPosition)
        .putInt(positionError)
        .putInt(auxiliaryPosition)
        .putInt(velocity)
        .putInt(torque)
        .putShort(analogInput)
        .put(hallInputStatus)
        .put(reservedByte)
        .putInt(userDefinedVariable)
    }

    override def toString: String = {
      s"""
         |status:              ${status & 0xFFFF}
         |switches:            $switches
         |stopCode:            $stopCode
         |referencePosition:   $referencePosition
         |motorPosition:       $motorPosition
         |positionError:       $positionError
         |auxiliaryPosition:   $auxiliaryPosition
         |velocity:            $velocity
         |torque:              $torque
         |analogInput:         ${analogInput & 0xFFFF}
         |hallInputStatus:     $hallInputStatus
         |reservedByte:        $reservedByte
         |userDefinedVariable: $userDefinedVariable
       """.stripMargin
    }

    def toParamSet: Set[Parameter[_]] = {
      Set(
        statusKey.set(status),
        switchesKey.set(switches),
        stopCodeKey.set(stopCode),
        referencePositionKey.set(referencePosition),
        motorPositionKey.set(motorPosition),
        positionErrorKey.set(positionError),
        auxiliaryPositionKey.set(auxiliaryPosition),
        velocityKey.set(velocity),
        torqueKey.set(torque),
        analogInputKey.set(analogInput),
        hallInputStatusKey.set(hallInputStatus)
      )
    }

  }

  object GalilAxisStatus {
    val statusKey: Key[Short] = KeyType.ShortKey.make("status")
    val switchesKey: Key[Byte] = KeyType.ByteKey.make("switches")
    val stopCodeKey: Key[Byte] = KeyType.ByteKey.make("stopCode")
    val referencePositionKey: Key[Int] = KeyType.IntKey.make("referencePosition")
    val motorPositionKey: Key[Int] = KeyType.IntKey.make("motorPosition")
    val positionErrorKey: Key[Int] = KeyType.IntKey.make("positionError")
    val auxiliaryPositionKey: Key[Int] = KeyType.IntKey.make("auxiliaryPosition")
    val velocityKey: Key[Int] = KeyType.IntKey.make("velocity")
    val torqueKey: Key[Int] = KeyType.IntKey.make("torque")
    val analogInputKey: Key[Short] = KeyType.ShortKey.make("analogInput")
    val hallInputStatusKey: Key[Byte] = KeyType.ByteKey.make("hallInputStatus")

    /**
      * Initialzie from the given bytes
      */
    def apply(buffer: ByteBuffer): GalilAxisStatus = {
      val status = buffer.getShort()
      val switches = buffer.get()
      val stopCode = buffer.get()
      val referencePosition = buffer.getInt()
      val motorPosition = buffer.getInt()
      val positionError = buffer.getInt()
      val auxiliaryPosition = buffer.getInt()
      val velocity = buffer.getInt()
      val torque = buffer.getInt
      val analogInput = buffer.getShort()
      val hallInputStatus = buffer.get()
      val reservedByte = buffer.get()
      val userDefinedVariable = buffer.getInt()
      GalilAxisStatus(status, switches, stopCode, referencePosition, motorPosition, positionError,
        auxiliaryPosition, velocity, torque, analogInput, hallInputStatus, reservedByte, userDefinedVariable)
    }

    /**
      * Initialze from the result of a command (See CompletedWithResult)
      */
    def apply(result: Result): GalilAxisStatus = {
      val status = result.get(statusKey).get.head
      val switches = result.get(switchesKey).get.head
      val stopCode = result.get(stopCodeKey).get.head
      val referencePosition = result.get(referencePositionKey).get.head
      val motorPosition = result.get(motorPositionKey).get.head
      val positionError = result.get(positionErrorKey).get.head
      val auxiliaryPosition = result.get(auxiliaryPositionKey).get.head
      val velocity = result.get(velocityKey).get.head
      val torque = result.get(torqueKey).get.head
      val analogInput = result.get(analogInputKey).get.head
      val hallInputStatus = result.get(hallInputStatusKey).get.head

      GalilAxisStatus(status, switches, stopCode, referencePosition, motorPosition, positionError,
        auxiliaryPosition, velocity, torque, analogInput, hallInputStatus)

    }

  }

  private def getBit(num: Byte, i: Int): Boolean = (num & (1 << i)) != 0

  private def setBit(i: Int, b: Boolean): Int = if (b) 1 << i else 0

  object AxisStatusBits {
    val MotorOff = 0
    val ThirdPhaseOfHMInProgress = 1
    val LatchArmed = 2
    val MotionMakingFinalDeceleration = 3
    val MotionStoppingDueToSTOfLimitSwitch = 4
    val MotionIsSlewing = 5
    val ModeOfMotionContour = 6
    val NegativeDirectionMove = 7
    val ModeOfMotionCoordMotion = 8
    val SecondPhaseOfHMCompleteOrFICommandIssued = 9
    val FirstPhaseOfHMComplete = 10
    val HomeInProgress = 11
    val FindEdgeInProgress = 12
    val ModeOfMotionPAOnly = 13
    val ModeOfMotionPAorPR = 14
    val MoveInProgress = 15
  }

  object AxisSwitchesBits {
    val StepperMode = 0
    val StateOfHomeInput = 1
    val StateOfReverseInput = 2
    val StateOfLatchInput = 5
    val LatchOccurred = 6
  }

  object CoordinatedMotionStatus {
    val MotionIsMakingFinalDeceleration = 3
    val MotionIsStoppingDueToSTorLimitSwitch = 4
    val MotionIsSlewing = 5
    val MoveInProgress = 15
  }

  /**
    * Creates a DataRecord from the bytes returned from a Galil device
    */
  def apply(bs: ByteString): DataRecord = {
    println(s"XXX input len = ${bs.size}")
    val buffer = bs.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
    val header = Header(buffer)
    println(s"XXX header = $header")
    val generalState = GeneralState(buffer, header)
    val axisStatuses = axes.map { axis =>
      if (header.blocksPresent.contains(axis.toString)) GalilAxisStatus(buffer) else GalilAxisStatus()
    }
    DataRecord(header, generalState, axisStatuses.toArray)
  }

  /**
    * Initialze the header from the result of a command (See CompletedWithResult)
    */
  def apply(result: Result): DataRecord = {
    val header = Header(result)
    val generalState = GeneralState(result)
    val axisStatuses = axes.flatMap { axis =>
      val axisKey = KeyType.StructKey.make(axis.toString)
      result.get(axisKey)
        .map(_.head.paramSet)
        .map(Result(result.prefix, _))
        .map(GalilAxisStatus(_))
    }
    DataRecord(header, generalState, axisStatuses.toArray)
  }

  /**
    * Returns a command response for a QR (getDataRecord) command
    *
    * @param prefix     the component's prefix
    * @param runId      runId from the Setup command
    * @param maybeObsId optional observation id from the command
    * @param dr         the parsed data record from the device
    * @return a CommandResponse containing values from the data record
    */
  def makeCommandResponse(prefix: Prefix, runId: Id, maybeObsId: Option[ObsId], dr: DataRecord): CommandResponse = {
    CompletedWithResult(runId, Result(prefix, dr.toParamSet))
  }

}

