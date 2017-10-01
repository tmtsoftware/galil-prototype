package csw.proto.galil.simulatorRepl

import akka.util.ByteString
import java.nio.{ByteBuffer, ByteOrder}

object QrCmd {

  private def getBit(num: Byte, i: Int): Boolean =
    (num & (1 << i)) != 0

  private def getBlock(num: Byte, i: Int, s: String): String =
    if (getBit(num, i)) s else ""

  private def toBinaryString(a: Array[Boolean]) = a.map(i => if (i) 1 else 0).mkString("")

  // 4 byte header
  case class Header(blocksPresent: List[String], recordSize: Int) {
    override def toString: String =
      s"""
         |Blocks present:   ${blocksPresent.mkString(" ")}
         |Data record size: $recordSize
       """.stripMargin
  }

  object Header {
    def apply(buffer: ByteBuffer): Header = {
      val byte0 = buffer.get
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
  }

  case class GeneralState(sampleNumber: Int,
                          inputs: Array[Boolean],
                          outputs: Array[Boolean],
                          ethernetHandleStatus: Array[Int],
                          errorCode: Int,
                          threadStatus: Array[Boolean],
                          amplifierStatus: Int) {
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

  object GeneralState {
    def apply(buffer: ByteBuffer): GeneralState = {
      // Reads numBytes bytes and returns 80 booleans corresponding to the bits
      def getBits(numBytes: Int): Array[Boolean] =
        (for (_ <- 0 until numBytes) yield {
          val b = buffer.get()
          for (j <- 0 until 8) yield {
            getBit(b, j)
          }
        }).flatten.toArray

      // ADDR 04 - 05
      val sampleNumber = buffer.getShort() & 0xFFFF
      // ADDR 06 - 15
      val inputs = getBits(10)
      // ADDR 16 - 25
      val outputs = getBits(10)

      // ADDR 26 - 41 (reserved)
      buffer.position(buffer.position() + 16)

      // ADDR 42 - 49
      val ethernetHandleStatus = (for (_ <- 0 until 8) yield buffer.get & 0xFF).toArray

      // ADDR 50
      val errorCode = buffer.get() & 0xFF

      // ADDR 51
      val threadStatus = getBits(1)

      // ADDR 52 - 55
      val amplifierStatus = buffer.getInt()

      GeneralState(sampleNumber, inputs, outputs, ethernetHandleStatus, errorCode, threadStatus, amplifierStatus)
    }
  }

  /**
    * Formats the binary result of a Galil QR command.
    * See DMC-500x0 User Manual: Chapter 4 Software Tools and Communication, p 58.
    */
  def format(bs: ByteString): String = {
    println(s"XXX message size: ${bs.size}")
    if (bs.size < 4) {
      "error: missing QR header"
    } else {
      val buffer = bs.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
      val header = Header(buffer)
      val generalState = GeneralState(buffer)
      s"""
         |Blocks present:   ${header.blocksPresent.mkString(" ")}
         |Data record size: ${header.recordSize}
         |
         |Sample number:    ${generalState.sampleNumber}
         |
       """.stripMargin
    }
  }

}
