package csw.proto.galil.io

import java.nio._

import akka.util.ByteString
import csw.proto.galil.io.DataRecord._

case class GalilAxisStatus(status: Short = 0,  // unsigned
   switches: Byte = 0,  //unsigned
   stopCode: Byte = 0,  // unsigned
   referencePosition: Int = 0,
   motorPosition: Int = 0,
   positionError: Int = 0,
   auxiliaryPosition: Int = 0,
   velocity: Int = 0,
   torque: Int = 0,
   analogInput: Short = 0,
   hallInputStatus: Byte = 0, // unsigned
   reservedByte: Byte = 0,   //unsigned
   userDefinedVariable: Int = 0
)


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
  * The data record returned from the Galil QR command
  *
  * @param header       the parsed data from the 4 byte header
  * @param generalState data from byte 4 to 55 (sample number to amplifier status)
  */
case class DataRecord(header: Header, generalState: GeneralState, axisStatuses: Array[GalilAxisStatus]) {
  override def toString: String = s"$header\n$generalState"
}

object DataRecord {
  def apply(bs: ByteString): DataRecord = {
    val buffer = bs.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
    val header = readHeader(buffer)
    val generalState = readGeneralState(buffer)
    val axisStatuses = new Array[GalilAxisStatus](8)
    for ((axis, ii) <- ('A' to 'H').zipWithIndex) {
      if (header.blocksPresent.contains(axis.toString)) {
        axisStatuses.update(ii, readGalilAxisStatus(buffer))
      }
    }
    DataRecord(header, generalState, axisStatuses)
  }

  def readGalilAxisStatus(buffer: ByteBuffer): GalilAxisStatus = {
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

  def generateByteBuffer(dr: DataRecord): ByteBuffer = {
    val buffer = ByteBuffer.allocateDirect(370)
    buffer.putInt(getHeaderBytes(dr.header))
      .putShort(dr.generalState.sampleNumber)
      .put(toBytes(dr.generalState.inputs))
      .put(toBytes(dr.generalState.outputs))
      .position(buffer.position() + 16)
    buffer.put(dr.generalState.ethernetHandleStatus)
      .put(dr.generalState.errorCode)
      .putInt(dr.generalState.amplifierStatus)
      .putInt(dr.generalState.contourModeSegmentCount)
      .putShort(dr.generalState.contourModeBufferSpaceRemaining)
      .putShort(dr.generalState.sPlaneSegmentCountOfCoordinatedMove)
      .putShort(dr.generalState.sPlaneCoordinatedMoveStatus)
      .putInt(dr.generalState.sPlaneDistanceTraveledInCoordinatedMove)
      .putShort(dr.generalState.sPlaneBufferSpaceRemaining)
      .putShort(dr.generalState.tPlaneSegmentCountOfCoordinatedMove)
      .putShort(dr.generalState.tPlaneCoordinatedMoveStatus)
      .putInt(dr.generalState.tPlaneDistanceTraveledInCoordinatedMove)
      .putShort(dr.generalState.tPlaneBufferSpaceRemaining)
    dr.axisStatuses.foreach { axis =>
      buffer.putShort( axis.status )
        .put(axis.switches)
        .put(axis.stopCode)
        .putInt(axis.referencePosition)
        .putInt(axis.motorPosition)
        .putInt(axis.positionError)
        .putInt(axis.auxiliaryPosition)
        .putInt(axis.velocity)
        .putInt(axis.torque)
        .putShort(axis.analogInput)
        .put(axis.hallInputStatus)
        .position(buffer.position+1)
    }
    buffer
  }

  private def getBit(num: Byte, i: Int): Boolean =
    (num & (1 << i)) != 0

  private def getBlock(num: Byte, i: Int, s: String): String =
    if (getBit(num, i)) s else ""

  private def toBinaryString(a: Array[Boolean]) = a.map(i => if (i) 1 else 0).mkString("")

  private def toBytes(a : Array[Boolean]) = {
    a.grouped(8)
      .map(_.foldLeft(0)((i,b) => (i<<1) + (if(b) 1 else 0)).toByte)
      .toArray
  }
  // 4 byte header
  case class Header(blocksPresent: List[String], recordSize: Int) {
    override def toString: String =
      s"""
         |Blocks present:   ${blocksPresent.mkString(" ")}
         |Data record size: $recordSize
       """.stripMargin
  }

  private def readHeader(buffer: ByteBuffer): Header = {
    val byte0 = buffer.get

    // XXX TODO: Error handling?
    if (!getBit(byte0, 7))
      println("Warning: The MSB of the first byte in the Data Record header is not one")

    val byte1 = buffer.get
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

  private def getHeaderBytes(h :Header) = {
    var blockPresentShort: Short = 0x8000.toShort
    for ((item, bit) <- h.blocksPresent.zipWithIndex if !item.isEmpty) {
      blockPresentShort = (blockPresentShort | (1 << bit)).toShort
    }
    (h.recordSize << 16 | blockPresentShort) & 0xFFFFFFFF

  }



  case class GeneralState(sampleNumber: Short,
                          inputs: Array[Boolean],
                          outputs: Array[Boolean],
                          ethernetHandleStatus: Array[Byte],
                          errorCode: Byte,
                          threadStatus: Array[Boolean],
                          amplifierStatus: Int,
                          contourModeSegmentCount: Int,
                          contourModeBufferSpaceRemaining: Short,
                          sPlaneSegmentCountOfCoordinatedMove: Short,
                          sPlaneCoordinatedMoveStatus: Short,
                          sPlaneDistanceTraveledInCoordinatedMove: Int,
                          sPlaneBufferSpaceRemaining: Short,
                          tPlaneSegmentCountOfCoordinatedMove: Short,
                          tPlaneCoordinatedMoveStatus: Short,
                          tPlaneDistanceTraveledInCoordinatedMove: Int,
                          tPlaneBufferSpaceRemaining: Short,
                         ) {
    override def toString: String =
      s"""
         |Sample number:          $sampleNumber
         |Inputs:                 ${toBinaryString(inputs)}
         |Outputs:                ${toBinaryString(outputs)}
         |Ethernet Handle Status: ${ethernetHandleStatus.mkString(", ")}
         |Error code:             $errorCode
         |Thread status:          ${toBinaryString(threadStatus)}
         |Amplifier status:       $amplifierStatus
       """.stripMargin
  }

  private def readGeneralState(buffer: ByteBuffer): GeneralState = {
    // Reads numBytes bytes and returns 80 booleans corresponding to the bits
    def getBits(numBytes: Int): Array[Boolean] =
      (for (_ <- 0 until numBytes) yield {
        val b = buffer.get()
        for (j <- 0 until 8) yield {
          getBit(b, j)
        }
      }).flatten.toArray

    // ADDR 04 - 05
    val sampleNumber = buffer.getShort()
    // ADDR 06 - 15
    val inputs = getBits(10)
    // ADDR 16 - 25
    val outputs = getBits(10)

    // ADDR 26 - 41 (reserved)
    buffer.position(buffer.position() + 16)

    // ADDR 42 - 49
    val ethernetHandleStatus = (for (_ <- 0 until 8) yield buffer.get).toArray

    // ADDR 50
    val errorCode = buffer.get()

    // ADDR 51
    val threadStatus = getBits(1)

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


    GeneralState(sampleNumber, inputs, outputs, ethernetHandleStatus, errorCode, threadStatus, amplifierStatus,
      countourModeSegmentCount, contourModeBufferSpaceRemaining,
      sPlaneSegmentCount, sPlaneMoveStatus, sPlaneDistanceTraveled, sPlaneBufferSpaceRemaining,
      tPlaneSegmentCount, tPlaneMoveStatus, tPlaneDistanceTraveled, tPlaneBufferSpaceRemaining,
    )
  }


}

