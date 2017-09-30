package csw.proto.galil.simulatorRepl

import akka.util.ByteString

object QrCmd {

  private def getBit(num: Byte, i: Int): Boolean = (num & (1 << i)) != 0

  private def getBlock(num: Byte, i: Int, s: String): String = {
    if (getBit(num, i)) s else ""
  }

  private def getUnsignedShort(b1: Byte, b2: Byte): Int = {
    import java.nio.ByteBuffer
    import java.nio.ByteOrder
    val bb = ByteBuffer.allocate(2)
    bb.order(ByteOrder.LITTLE_ENDIAN)
    bb.put(b1)
    bb.put(b2)
    bb.flip()
    bb.getShort(0) & 0xFFFF
  }

  /**
    * Formats the binary result of a Galil QR command.
    * See DMC-500x0 User Manual: Chapter 4 Software Tools and Communication, p 58.
    */
  def format(bs: ByteString): String = {
    println(s"XXX bs size = ${bs.size}")
    if (bs.size < 4) {
      "error: missing QR header"
    } else {
      // 4 byte header
      val b0 = bs(0)
      val b1 = bs(1)
      val testBit = getBit(b0, 7)
      println(s"XXX test bit set: $testBit")
      val blocksPresent = List(
        getBlock(b0, 0, "S"),
        getBlock(b0, 1, "T"),
        getBlock(b0, 2, "I"),
        getBlock(b1, 0, "A"),
        getBlock(b1, 1, "B"),
        getBlock(b1, 2, "C"),
        getBlock(b1, 3, "D"),
        getBlock(b1, 4, "E"),
        getBlock(b1, 5, "F"),
        getBlock(b1, 6, "G"),
        getBlock(b1, 7, "H")).mkString(" ")

      val recordSize = getUnsignedShort(bs(2),bs(3))

      s"""
         |Blocks present:   $blocksPresent
         |Data record size: $recordSize
       """.stripMargin
    }
  }

}
