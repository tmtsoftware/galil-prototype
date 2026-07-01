package aps.ics.assembly.common

import org.apache.pekko.util.ByteString

import java.nio.{ByteBuffer, ByteOrder}

/**
 * Encodes an in-memory [[Frame]] as a minimal, valid FITS file (single primary
 * HDU) so a VBDS subscriber can decode it with a standard FITS reader. The
 * reference esw-vbds subscriber (`python-client/vbds-centroid.py`) does exactly
 * `fits.HDUList(file=image)` -> `hdulist[0].data` -> `centroid_com(data)`, so a
 * parseable FITS primary HDU is the consumer contract for the APT
 * acquisition/guiding path (SDD §5.1.2.2.1).
 *
 * NOTE: VBDS transport itself is byte-opaque — the server carries the file bytes
 * and appends a one-byte newline to frame each file — so FITS is required by the
 * *consumer/design* contract, not by VBDS. We emit it here at the publish seam.
 *
 * Layout (FITS standard, 2880-byte blocks):
 *   - Primary header: SIMPLE=T, BITPIX=-32 (IEEE single-precision float — matches
 *     Frame.data: Array[Float]), NAXIS=2, NAXIS1=width, NAXIS2=height, END;
 *     ASCII, 80-byte cards, space-padded to a 2880 multiple.
 *   - Data: width*height float32 in BIG-ENDIAN order (FITS is always big-endian),
 *     row-major with NAXIS1 (width / x) varying fastest — which is exactly
 *     Frame.data's layout (`data(y*w + x)`), so no transpose is needed;
 *     zero-padded to a 2880 multiple.
 */
object FitsEncoder:

  private val BlockSize  = 2880
  private val CardLength = 80

  /** Encode `frame` as a complete FITS file (header block(s) + data block(s)). */
  def encode(frame: Frame): ByteString =
    ByteString(headerBlock(frame.width, frame.height)) ++ ByteString(dataBlock(frame))

  /** One fixed-format value card: keyword (bytes 1-8), "= " (9-10), value
   *  right-justified (11-30), space-padded to 80. */
  private def card(keyword: String, value: String): String =
    f"$keyword%-8s= $value%20s".padTo(CardLength, ' ')

  private def endCard: String = "END".padTo(CardLength, ' ')

  private def headerBlock(width: Int, height: Int): Array[Byte] =
    val cards = List(
      card("SIMPLE", "T"),
      card("BITPIX", "-32"),
      card("NAXIS", "2"),
      card("NAXIS1", width.toString),
      card("NAXIS2", height.toString),
      endCard
    )
    val text   = cards.mkString
    val padLen = (BlockSize - (text.length % BlockSize)) % BlockSize
    (text + (" " * padLen)).getBytes("US-ASCII")

  private def dataBlock(frame: Frame): Array[Byte] =
    val n      = frame.width * frame.height
    val rawLen = n * 4
    val padLen = (BlockSize - (rawLen % BlockSize)) % BlockSize
    // ByteBuffer.allocate zero-fills, so the trailing pad bytes are already 0.
    val buf    = ByteBuffer.allocate(rawLen + padLen).order(ByteOrder.BIG_ENDIAN)
    var i = 0
    while i < n do
      buf.putFloat(frame.data(i))
      i += 1
    buf.array()
