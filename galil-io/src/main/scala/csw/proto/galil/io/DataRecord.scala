package csw.proto.galil.io

import java.io.IOException
import java.nio.{ByteBuffer, ByteOrder}

import akka.util.ByteString
import csw.params.commands.CommandResponse.{Completed, SubmitResponse}
import csw.params.commands.Result
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
//noinspection ScalaUnusedSymbol
case class DataRecord(header: Header, generalState: GeneralState, axisStatuses: Array[GalilAxisStatus]) {

  override def toString: String = {
    val status = axes
      .zip(axisStatuses)
      .flatMap { p =>
        if (header.blocksPresent.contains(p._1))
          Some((p._1, p._2))
        else None
      }
      .map(p => s"\nAxis ${p._1} Status:${p._2.toString}")
      .mkString("\n")
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
    axisStatuses.foreach(_.write(buffer))

    buffer.flip()
  }

  def toParamSet: Set[Parameter[_]] = {
    val axisStatus = axes.zip(axisStatuses).flatMap { p =>
      // Since the "struct" type is no longer supported, use the implicit "axis" char to build a unique name for
      // the axis parameters
      implicit val axis: Char = p._1
      if (header.blocksPresent.contains(axis))
        Some(p._2.toParamSet)
      else None
    }
    header.toParamSet ++ generalState.toParamSet ++ axisStatus.toSet.flatten
  }
}

object DataRecord {

  /**
   * Param set key for getting the raw data record bytes (with getDataRecordRaw command in GalilCommands.conf)
   */
  val key: Key[ArrayData[Byte]] = KeyType.ByteArrayKey.make("dataRecord")

  // JSON support
  implicit val headerJsonFormat: OFormat[Header]                   = Json.format[Header]
  implicit val generalStateJsonFormat: OFormat[GeneralState]       = Json.format[GeneralState]
  implicit val galilAxisStatusJsonFormat: OFormat[GalilAxisStatus] = Json.format[GalilAxisStatus]
  implicit val dataRecordJsonFormat: OFormat[DataRecord]           = Json.format[DataRecord]

  /**
   * In most cases only the axes A to H are used
   */
  val axes: List[Char] = ('A' to 'H').toList

  /**
   * Possible Galil Axis for header (may not all be present)
   */
  val allAxes: List[Char] = List('S', 'T', 'I') ++ axes

  /**
   * The 4 byte Galil header
   *
   * @param blocks List of name of the block for each block present
   */
  case class Header(blocks: List[String]) {

    import Header._

    val blocksPresent: List[Char] = blocks.map(_.head)
    // DataRecord size depends on the fixed size of the header + general state + each of the axis statuses
    val recordSize: Int = 82 + blocksPresent.count(axes.contains(_)) * 36

    /**
     * Appends the header to the buffer in the documented Galil format
     */
    def write(buffer: ByteBuffer): Unit = {
      val byte0 = allAxes.take(3).zipWithIndex.map(p => setBit(p._2, blocksPresent.contains(p._1))).sum
      val byte1 = axes.zipWithIndex.map(p => setBit(p._2, blocksPresent.contains(p._1))).sum
      buffer.put(byte0.asInstanceOf[Byte])
      buffer.put(byte1.asInstanceOf[Byte])
      buffer.putShort(recordSize.asInstanceOf[Short])
    }

    override def toString: String =
      s"""
         |Blocks present:    ${blocksPresent.mkString(" ")}
         |Data record size: $recordSize
       """.stripMargin

    def toParamSet: Set[Parameter[_]] =
      Set(blocksPresentKey.setAll(blocksPresent.toArray))

  }

  // noinspection ScalaWeakerAccess
  object Header {
    val blocksPresentKey: Key[Char] = KeyType.CharKey.make("blocksPresent")

    /**
     * Initialize the header from the given byte buffer
     */
    def apply(buffer: ByteBuffer): Header = {
      // Note: The header information of the data records is formatted in little endian (reversed network byte order)
      val byte0 = buffer.get
      val byte1 = buffer.get

      def getBlock(num: Byte, i: Int, axis: Char): Option[Char] = if (getBit(num, i)) Some(axis) else None

      val blocksPresent = List(
        getBlock(byte0, 0, 'S'),
        getBlock(byte0, 1, 'T'),
        getBlock(byte0, 2, 'I'),
        getBlock(byte1, 0, 'A'),
        getBlock(byte1, 1, 'B'),
        getBlock(byte1, 2, 'C'),
        getBlock(byte1, 3, 'D'),
        getBlock(byte1, 4, 'E'),
        getBlock(byte1, 5, 'F'),
        getBlock(byte1, 6, 'G'),
        getBlock(byte1, 7, 'H')
      ).flatten

      val recordSize = buffer.getShort() & 0x0ffff
      val header     = Header(blocksPresent.map(_.toString))
      if (recordSize != header.recordSize)
        throw new IOException(s"Error: Wrong data record size in DataRecord.Header.apply: $recordSize != ${header.recordSize}")
      header
    }

    /**
     * Initialize from the result of a command (See CompletedWithResult)
     */
    def apply(result: Result): Header = {
      val blocks = result.get(blocksPresentKey).get.values.toList.map(_.toString)
      Header(blocks)
    }
  }

  case class GeneralState(
      sampleNumber: Short,
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
      tPlaneBufferSpaceRemaining: Short
  ) {

    import GeneralState._

    if (inputs.length != 10) throw new IOException(s"Error: Wrong number of inputs: ${inputs.length}")
    if (outputs.length != 10) throw new IOException(s"Error: Wrong number of outputs: ${outputs.length}")
    if (ethernetHandleStatus.length != 8)
      throw new IOException(s"Error: Wrong number of ethernetHandleStatus: ${ethernetHandleStatus.length}")

    /**
     * Appends the GeneralState to the given ByteBuffer in the documented Galil format
     */
    def write(buffer: ByteBuffer): Unit = {

      buffer
        .putShort(sampleNumber)
        .put(inputs)
        .put(outputs)

      buffer.position(buffer.position() + 16)

      buffer
        .put(ethernetHandleStatus)
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
         |Sample number:                  ${sampleNumber & 0xffff}
         |Inputs:                         ${toBinaryString(inputs)}
         |Outputs:                        ${toBinaryString(outputs)}
         |Ethernet handle status:         ${ethernetHandleStatus.map(_ & 0xff).mkString(", ")}
         |Error code:                     $errorCode
         |Thread status:                  ${threadStatus.toBinaryString}
         |Amplifier status:               $amplifierStatus
         |Contour mode segment count:                     $contourModeSegmentCount,
         |contour mode buffer space remaining:            ${contourModeBufferSpaceRemaining & 0xffff},
         |S plane segment count of coordinated move:      ${sPlaneSegmentCount & 0xffff},
         |S plane coordinated move status:                ${sPlaneMoveStatus & 0xffff},
         |S plane distance traveled in coordinated move:  $sPlaneDistanceTraveled,
         |S plane buffer space remaining:                 ${sPlaneBufferSpaceRemaining & 0xffff},
         |T plane segment count of coordinated move:      ${tPlaneSegmentCount & 0xffff},
         |T plane coordinated move status:                ${tPlaneMoveStatus & 0xffff},
         |T plane distance traveled in coordinated move:  $tPlaneDistanceTraveled,
         |T plane buffer space remaining:                 ${tPlaneBufferSpaceRemaining & 0xffff},
       """.stripMargin

    def toParamSet: Set[Parameter[_]] = {
      Set(
        sampleNumberKey.set(sampleNumber),
        inputsKey.setAll(inputs),
        outputsKey.setAll(outputs),
        ethernetHandleStatusKey.setAll(ethernetHandleStatus),
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

  // noinspection ScalaWeakerAccess,SpellCheckingInspection,DuplicatedCode
  object GeneralState {
    val sampleNumberKey: Key[Short]                    = KeyType.ShortKey.make("sampleNumber")
    val inputsKey: Key[Byte]                           = KeyType.ByteKey.make("inputs")
    val outputsKey: Key[Byte]                          = KeyType.ByteKey.make("outputs")
    val ethernetHandleStatusKey: Key[Byte]             = KeyType.ByteKey.make("ethernetHandleStatus")
    val errorCodeKey: Key[Byte]                        = KeyType.ByteKey.make("errorCode")
    val threadStatusKey: Key[Byte]                     = KeyType.ByteKey.make("threadStatus")
    val amplifierStatusKey: Key[Int]                   = KeyType.IntKey.make("amplifierStatus")
    val contourModeSegmentCountKey: Key[Int]           = KeyType.IntKey.make("contourModeSegmentCount")
    val contourModeBufferSpaceRemainingKey: Key[Short] = KeyType.ShortKey.make("contourModeBufferSpaceRemaining")
    val sPlaneSegmentCountKey: Key[Int]                = KeyType.IntKey.make("sPlaneSegmentCount")
    val sPlaneMoveStatusKey: Key[Int]                  = KeyType.IntKey.make("sPlaneMoveStatus")
    val sPlaneDistanceTraveledKey: Key[Int]            = KeyType.IntKey.make("sPlaneDistanceTraveled")
    val sPlaneBufferSpaceRemainingKey: Key[Int]        = KeyType.IntKey.make("sPlaneBufferSpaceRemaining")
    val tPlaneSegmentCountKey: Key[Int]                = KeyType.IntKey.make("tPlaneSegmentCount")
    val tPlaneMoveStatusKey: Key[Int]                  = KeyType.IntKey.make("tPlaneMoveStatus")
    val tPlaneDistanceTraveledKey: Key[Int]            = KeyType.IntKey.make("tPlaneDistanceTraveled")
    val tPlaneBufferSpaceRemainingKey: Key[Int]        = KeyType.IntKey.make("tPlaneBufferSpaceRemaining")

    /**
     * Initializes from the given ByteBuffer in the documented Galil data record format
     */
    def apply(buffer: ByteBuffer): GeneralState = {

      // QZ
      // 4, 52, 26, 36
      // Number of axes present
      // number of bytes in general block of data record
      // number of bytes in coordinate plane block of data record
      // Number of Bytes in each axis block of data record

      def getBytes(numBytes: Int): Array[Byte] = {
        val ar = Array.fill(numBytes)(0.toByte)
        buffer.get(ar)
        ar
      }

      // ADDR 04 - 05
      val sampleNumber = buffer.getShort()
      // ADDR 06 - 15
      val inputs = getBytes(10)
      // ADDR 16 - 25
      val outputs = getBytes(10)

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
      val contourModeSegmentCount = buffer.getInt()

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

      GeneralState(
        sampleNumber,
        inputs,
        outputs,
        ethernetHandleStatus,
        errorCode,
        threadStatus,
        amplifierStatus,
        contourModeSegmentCount,
        contourModeBufferSpaceRemaining,
        sPlaneSegmentCount,
        sPlaneMoveStatus,
        sPlaneDistanceTraveled,
        sPlaneBufferSpaceRemaining,
        tPlaneSegmentCount,
        tPlaneMoveStatus,
        tPlaneDistanceTraveled,
        tPlaneBufferSpaceRemaining
      )
    }

    /**
     * Creates a GeneralState from the result of a command (See CompletedWithResult)
     */
    def apply(result: Result): GeneralState = {
      val sampleNumber                    = result.get(sampleNumberKey).get.head
      val inputs                          = result.get(inputsKey).get.values
      val outputs                         = result.get(outputsKey).get.values
      val ethernetHandleStatus            = result.get(ethernetHandleStatusKey).get.values
      val errorCode                       = result.get(errorCodeKey).get.head
      val threadStatus                    = result.get(threadStatusKey).get.head
      val amplifierStatus                 = result.get(amplifierStatusKey).get.head
      val contourModeSegmentCount         = result.get(contourModeSegmentCountKey).get.head
      val contourModeBufferSpaceRemaining = result.get(contourModeBufferSpaceRemainingKey).get.head
      val sPlaneSegmentCount              = result.get(sPlaneSegmentCountKey).get.head.toShort
      val sPlaneMoveStatus                = result.get(sPlaneMoveStatusKey).get.head.toShort
      val sPlaneDistanceTraveled          = result.get(sPlaneDistanceTraveledKey).get.head
      val sPlaneBufferSpaceRemaining      = result.get(sPlaneBufferSpaceRemainingKey).get.head.toShort
      val tPlaneSegmentCount              = result.get(tPlaneSegmentCountKey).get.head.toShort
      val tPlaneMoveStatus                = result.get(tPlaneMoveStatusKey).get.head.toShort
      val tPlaneDistanceTraveled          = result.get(tPlaneDistanceTraveledKey).get.head
      val tPlaneBufferSpaceRemaining      = result.get(tPlaneBufferSpaceRemainingKey).get.head.toShort

      GeneralState(
        sampleNumber,
        inputs,
        outputs,
        ethernetHandleStatus,
        errorCode,
        threadStatus,
        amplifierStatus,
        contourModeSegmentCount,
        contourModeBufferSpaceRemaining,
        sPlaneSegmentCount,
        sPlaneMoveStatus,
        sPlaneDistanceTraveled,
        sPlaneBufferSpaceRemaining,
        tPlaneSegmentCount,
        tPlaneMoveStatus,
        tPlaneDistanceTraveled,
        tPlaneBufferSpaceRemaining
      )
    }

  }

  case class GalilAxisStatus(
      status: Short = 0,  // unsigned
      switches: Byte = 0, // unsigned
      stopCode: Byte = 0, // unsigned
      referencePosition: Int = 0,
      motorPosition: Int = 0,
      positionError: Int = 0,
      auxiliaryPosition: Int = 0,
      velocity: Int = 0,
      torque: Int = 0,
      analogInput: Short = 0,
      hallInputStatus: Byte = 0, // unsigned
      reservedByte: Byte = 0,    // unsigned
      userDefinedVariable: Int = 0
  ) {

    import GalilAxisStatus._

    /**
     * Appends bytes to the buffer in the documented Galil format
     */
    def write(buffer: ByteBuffer): Unit = {
      buffer
        .putShort(status)
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
         |status:              ${status & 0xffff}
         |switches:            $switches
         |stopCode:            $stopCode
         |referencePosition:   $referencePosition
         |motorPosition:       $motorPosition
         |positionError:       $positionError
         |auxiliaryPosition:   $auxiliaryPosition
         |velocity:            $velocity
         |torque:              $torque
         |analogInput:         ${analogInput & 0xffff}
         |hallInputStatus:     $hallInputStatus
         |reservedByte:        $reservedByte
         |userDefinedVariable: $userDefinedVariable
       """.stripMargin
    }

    def toParamSet(implicit axis: Char): Set[Parameter[_]] = {
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

  // noinspection ScalaWeakerAccess,DuplicatedCode
  object GalilAxisStatus {
    // Note: Each key is prefixed with "axis-a" for axis "a"
    def statusKey(implicit axis: Char): Key[Short] = KeyType.ShortKey.make(s"axis-$axis-status")

    def switchesKey(implicit axis: Char): Key[Byte] = KeyType.ByteKey.make(s"axis-$axis-switches")

    def stopCodeKey(implicit axis: Char): Key[Byte] = KeyType.ByteKey.make(s"axis-$axis-stopCode")

    def referencePositionKey(implicit axis: Char): Key[Int] = KeyType.IntKey.make(s"axis-$axis-referencePosition")

    def motorPositionKey(implicit axis: Char): Key[Int] = KeyType.IntKey.make(s"axis-$axis-motorPosition")

    def positionErrorKey(implicit axis: Char): Key[Int] = KeyType.IntKey.make(s"axis-$axis-positionError")

    def auxiliaryPositionKey(implicit axis: Char): Key[Int] = KeyType.IntKey.make(s"axis-$axis-auxiliaryPosition")

    def velocityKey(implicit axis: Char): Key[Int] = KeyType.IntKey.make(s"axis-$axis-velocity")

    def torqueKey(implicit axis: Char): Key[Int] = KeyType.IntKey.make(s"axis-$axis-torque")

    def analogInputKey(implicit axis: Char): Key[Short] = KeyType.ShortKey.make(s"axis-$axis-analogInput")

    def hallInputStatusKey(implicit axis: Char): Key[Byte] = KeyType.ByteKey.make(s"axis-$axis-hallInputStatus")

    /**
     * Initialize from the given bytes
     */
    def apply(buffer: ByteBuffer): GalilAxisStatus = {
      val status              = buffer.getShort()
      val switches            = buffer.get()
      val stopCode            = buffer.get()
      val referencePosition   = buffer.getInt()
      val motorPosition       = buffer.getInt()
      val positionError       = buffer.getInt()
      val auxiliaryPosition   = buffer.getInt()
      val velocity            = buffer.getInt()
      val torque              = buffer.getInt
      val analogInput         = buffer.getShort()
      val hallInputStatus     = buffer.get()
      val reservedByte        = buffer.get()
      val userDefinedVariable = buffer.getInt()
      GalilAxisStatus(
        status,
        switches,
        stopCode,
        referencePosition,
        motorPosition,
        positionError,
        auxiliaryPosition,
        velocity,
        torque,
        analogInput,
        hallInputStatus,
        reservedByte,
        userDefinedVariable
      )
    }

    /**
     * Initialize from the result of a command (See CompletedWithResult)
     */
    def apply(axis: Char, result: Result): GalilAxisStatus = {
      implicit val a: Char  = axis
      val status            = result.get(statusKey).get.head
      val switches          = result.get(switchesKey).get.head
      val stopCode          = result.get(stopCodeKey).get.head
      val referencePosition = result.get(referencePositionKey).get.head
      val motorPosition     = result.get(motorPositionKey).get.head
      val positionError     = result.get(positionErrorKey).get.head
      val auxiliaryPosition = result.get(auxiliaryPositionKey).get.head
      val velocity          = result.get(velocityKey).get.head
      val torque            = result.get(torqueKey).get.head
      val analogInput       = result.get(analogInputKey).get.head
      val hallInputStatus   = result.get(hallInputStatusKey).get.head

      GalilAxisStatus(
        status,
        switches,
        stopCode,
        referencePosition,
        motorPosition,
        positionError,
        auxiliaryPosition,
        velocity,
        torque,
        analogInput,
        hallInputStatus
      )

    }

  }

  private def getBit(num: Byte, i: Int): Boolean = (num & (1 << i)) != 0

  private def setBit(i: Int, b: Boolean): Int = if (b) 1 << i else 0

  // noinspection ScalaUnusedSymbol
  object AxisStatusBits {
    val MotorOff                                 = 0
    val ThirdPhaseOfHMInProgress                 = 1
    val LatchArmed                               = 2
    val MotionMakingFinalDeceleration            = 3
    val MotionStoppingDueToSTOfLimitSwitch       = 4
    val MotionIsSlewing                          = 5
    val ModeOfMotionContour                      = 6
    val NegativeDirectionMove                    = 7
    val ModeOfMotionCoordMotion                  = 8
    val SecondPhaseOfHMCompleteOrFICommandIssued = 9
    val FirstPhaseOfHMComplete                   = 10
    val HomeInProgress                           = 11
    val FindEdgeInProgress                       = 12
    val ModeOfMotionPAOnly                       = 13
    val ModeOfMotionPAorPR                       = 14
    val MoveInProgress                           = 15
  }

  // noinspection ScalaUnusedSymbol
  object AxisSwitchesBits {
    val StepperMode         = 0
    val StateOfHomeInput    = 1
    val StateOfReverseInput = 2
    val StateOfLatchInput   = 5
    val LatchOccurred       = 6
  }

  object CoordinatedMotionStatus {
    val MotionIsMakingFinalDeceleration      = 3
    val MotionIsStoppingDueToSTorLimitSwitch = 4
    val MotionIsSlewing                      = 5
    val MoveInProgress                       = 15
  }

  /**
   * Creates a DataRecord from the bytes returned from a Galil device
   */
  def apply(bs: ByteString): DataRecord = {
    val buffer       = bs.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
    val header       = Header(buffer)
    val generalState = GeneralState(buffer)
    val axisStatuses = axes.flatMap { axis =>
      if (header.blocksPresent.contains(axis)) Some(GalilAxisStatus(buffer)) else None
    }
    DataRecord(header, generalState, axisStatuses.toArray)
  }

  /**
   * Initialize the header from the result of a command (See CompletedWithResult)
   */
  def apply(result: Result): DataRecord = {
    val header       = Header(result)
    val generalState = GeneralState(result)
    val axisStatuses = axes
      .filter(header.blocksPresent.contains)
      .map(GalilAxisStatus(_, result))
    DataRecord(header, generalState, axisStatuses.toArray)
  }

  /**
   * Returns a command response for a QR (getDataRecord) command
   *
   * @param runId      runId from the Setup command
   * @param maybeObsId optional observation id from the command
   * @param dr         the parsed data record from the device
   * @return a CommandResponse containing values from the data record
   */
  def makeCommandResponse(runId: Id, maybeObsId: Option[ObsId], dr: DataRecord): SubmitResponse = {
    Completed(runId, Result(dr.toParamSet))
  }

}
