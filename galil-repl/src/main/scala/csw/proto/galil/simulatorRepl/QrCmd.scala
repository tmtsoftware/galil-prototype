package csw.proto.galil.simulatorRepl

import akka.util.ByteString
import java.nio.ByteOrder

object QrCmd {

  private def getBit(num: Byte, i: Int): Boolean =
    (num & (1 << i)) != 0

  private def getBlock(num: Byte, i: Int, s: String): String =
    if (getBit(num, i)) s else ""

  /**
    * Formats the binary result of a Galil QR command.
    * See DMC-500x0 User Manual: Chapter 4 Software Tools and Communication, p 58.
    */
  def format(bs: ByteString): String = {
    if (bs.size < 4) {
      "error: missing QR header"
    } else {
      val buffer = bs.toByteBuffer.order(ByteOrder.LITTLE_ENDIAN)
      // 4 byte header
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
        getBlock(byte1, 7, "H")).mkString(", ")

      val recordSize = buffer.getShort() & 0xFFFF

      s"""
         |Blocks present:   $blocksPresent
         |Data record size: $recordSize
       """.stripMargin
    }
  }

}
