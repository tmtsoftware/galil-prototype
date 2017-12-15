package csw.proto.galil.io

import java.nio.{ByteBuffer, ByteOrder}

import akka.util.ByteString
import csw.proto.galil.io.DataRecord._


/**
  * The data record returned from the Galil QR command
  *
  * @param header       the parsed data from the 4 byte header
  * @param generalState data from byte 4 to 55 (sample number to amplifier status)
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
    val buffer = ByteBuffer.allocateDirect(header.recordSize).order(ByteOrder.LITTLE_ENDIAN)

    header.write(buffer)
    generalState.write(buffer)

    axes.zip(axisStatuses).foreach { p =>
      if (header.blocksPresent.contains(p._1.toString))
        p._2.write(buffer)
    }

    buffer.flip().asInstanceOf[ByteBuffer]
  }
}

object DataRecord {

  /**
    * Possible Galil Axis (may not all be present)
    */
  val axes: List[Char] = ('A' to 'H').toList

  /**
    * The 4 byte Galil header
    * @param blocksPresent Contains the char name of the block for each block present, or empty string if block not present
    * @param recordSize size of the data record, including the header
    */
  case class Header(blocksPresent: List[String], recordSize: Int) {

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
  }

  object Header {
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
  }

  case class GeneralState(sampleNumber: Int,
                          inputs: Array[Byte],
                          outputs: Array[Byte],
                          ethernetHandleStatus: Array[Byte],
                          errorCode: Byte,
                          threadStatus: Byte,
                          amplifierStatus: Int,
                          contourModeSegmentCount: Int,
                          contourModeBufferSpaceRemaining: Int,
                          sPlaneSegmentCount: Int,
                          sPlaneMoveStatus: Int,
                          sPlaneDistanceTraveled: Int,
                          sPlaneBufferSpaceRemaining: Int,
                          tPlaneSegmentCount: Int,
                          tPlaneMoveStatus: Int,
                          tPlaneDistanceTraveled: Int,
                          tPlaneBufferSpaceRemaining: Int) {

    /**
      * Appends the GeneralState to the given ByteBuffer in the documented Galil format
      */
    def write(buffer: ByteBuffer): Unit = {

      buffer.putShort(sampleNumber.asInstanceOf[Short])
        .put(inputs)
        .put(outputs)

      buffer.position(buffer.position() + 16)

      buffer.put(ethernetHandleStatus)
        .put(errorCode)
        .put(threadStatus)
        .putInt(amplifierStatus)
        .putInt(contourModeSegmentCount)
        .putShort(contourModeBufferSpaceRemaining.asInstanceOf[Short])
        .putShort(sPlaneSegmentCount.asInstanceOf[Short])
        .putShort(sPlaneMoveStatus.asInstanceOf[Short])
        .putInt(sPlaneDistanceTraveled)
        .putShort(sPlaneBufferSpaceRemaining.asInstanceOf[Short])
        .putShort(tPlaneSegmentCount.asInstanceOf[Short])
        .putShort(tPlaneMoveStatus.asInstanceOf[Short])
        .putInt(tPlaneDistanceTraveled)
        .putShort(tPlaneBufferSpaceRemaining.asInstanceOf[Short])
    }

    private def toBinaryString(a: Array[Byte]) = a.map(i => i.toBinaryString).mkString(" ")

    override def toString: String =
      s"""
         |Sample number:                  $sampleNumber
         |Inputs:                         ${toBinaryString(inputs)}
         |Outputs:                        ${toBinaryString(outputs)}
         |Ethernet handle status:         ${ethernetHandleStatus.map(_ & 0xFF).mkString(", ")}
         |Error code:                     $errorCode
         |Thread status:                  ${threadStatus.toBinaryString}
         |Amplifier status:               $amplifierStatus
         |Contour mode segment count:                     $contourModeSegmentCount,
         |contour mode buffer space remaining:            $contourModeBufferSpaceRemaining,
         |S plane segment count of coordinated move:      $sPlaneSegmentCount,
         |S plane coordinated move status:                $sPlaneMoveStatus,
         |S plane distance traveled in coordinated move:  $sPlaneDistanceTraveled,
         |S plane buffer space remaining:                 $sPlaneBufferSpaceRemaining,
         |T plane segment count of coordinated move:      $tPlaneSegmentCount,
         |T plane coordinated move status:                $tPlaneMoveStatus,
         |T plane distance traveled in coordinated move:  $tPlaneDistanceTraveled,
         |T plane buffer space remaining:                 $tPlaneBufferSpaceRemaining,
       """.stripMargin
  }

  object GeneralState {
    /**
      * Initializes from the given ByteBuffer in the documented Galil data record format
      */
    def apply(buffer: ByteBuffer): GeneralState = {

      def getBytes(numBytes: Int): Array[Byte] = {
        val ar = Array.fill(numBytes)(0.toByte)
        buffer.get(ar)
        ar
      }

      // ADDR 04 - 05
      val sampleNumber = buffer.getShort() & 0xFFFF
      // ADDR 06 - 15
      val inputs = getBytes(10)
      // ADDR 16 - 25
      val outputs = getBytes(10)

      // ADDR 26 - 41 (reserved)
      buffer.position(buffer.position() + 16)

      // ADDR 42 - 49
      val ethernetHandleStatus = axes.map(_ => buffer.get).toArray

      // ADDR 50
      val errorCode = buffer.get()

      // ADDR 51
      val threadStatus = buffer.get()

      // ADDR 52 - 55
      val amplifierStatus = buffer.getInt()

      // ADDR 56-59
      val countourModeSegmentCount = buffer.getInt()

      // ADDR 60-61
      val contourModeBufferSpaceRemaining = buffer.getShort() & 0xFFFF

      // TODO check for existence?

      // ADDR 62-63
      val sPlaneSegmentCount = buffer.getShort() & 0xFFFF

      // ADDR 64-65
      val sPlaneMoveStatus = buffer.getShort() & 0xFFFF

      // ADDR 66-69
      val sPlaneDistanceTraveled = buffer.getInt()

      // ADDR 70-71
      val sPlaneBufferSpaceRemaining = buffer.getShort() & 0xFFFF

      // TODO Check for existance

      // ADDR 72-73
      val tPlaneSegmentCount = buffer.getShort() & 0xFFFF

      // ADDR 74-75
      val tPlaneMoveStatus = buffer.getShort() & 0xFFFF

      // ADDR 76-79
      val tPlaneDistanceTraveled = buffer.getInt()

      // ADDR 80-81
      val tPlaneBufferSpaceRemaining = buffer.getShort() & 0xFFFF


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
         |status:              $status
         |switches:            $switches
         |stopCode:            $stopCode
         |referencePosition:   $referencePosition
         |motorPosition:       $motorPosition
         |positionError:       $positionError
         |auxiliaryPosition:   $auxiliaryPosition
         |velocity:            $velocity
         |torque:              $torque
         |analogInput:         $analogInput
         |hallInputStatus:     $hallInputStatus
         |reservedByte:        $reservedByte
         |userDefinedVariable: $userDefinedVariable
       """.stripMargin
    }
  }

  object GalilAxisStatus {
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
    val generalState = GeneralState(buffer)
    val axisStatuses = axes.map { axis =>
      if (header.blocksPresent.contains(axis.toString)) GalilAxisStatus(buffer) else GalilAxisStatus()
    }
    DataRecord(header, generalState, axisStatuses.toArray)
  }
}

