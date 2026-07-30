package csw.proto.galil.io

import org.apache.pekko.util.ByteString
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable

/**
 * Unit tests for the `GalilIo` abstract base class.
 *
 * These tests exercise the protocol layer (writeRaw, send, sendAndWaitForPrompt,
 * uploadProgram, downloadProgram, chunkCompound, the 80-char guard, response
 * parsing) using a programmable `StubGalilIo` that captures writes and serves
 * scripted reads.  No real socket I/O — the TCP/UDP subclasses are integration-
 * tested separately by galil-simulator/GalilIoTests.scala.
 *
 * Tests are grouped by method.  Each covers happy path, corner cases, and the
 * specific empirical findings that motivated the current implementation:
 *
 *   - 80-char line limit  (S53: caught a 102-char compound being silently truncated)
 *   - DL `?` rejection    (S60: the bug where lines >80 chars were dropped silently)
 *   - DL two-ack consume  (S59: deferred ack from DL after `\` consumed `:` for `\`)
 *   - Read-timeout save/restore  (S62: encapsulated inside uploadProgram)
 *
 * A regression in any of these would silently corrupt the controller — exactly
 * the class of failure that drove the migration into GalilIo in S62.
 */
class GalilIoTest extends AnyFunSuite with Matchers:

  // ========================================
  // Test stub: programmable GalilIo
  // ========================================

  /**
   * A test double for GalilIo that:
   *   - captures every write() call into `writes` (in order)
   *   - serves read() responses from a queue (`readQueue`); empty queue → empty ByteString
   *   - serves drainAndShowBuffer() responses from a queue (`drainQueue`); empty → ""
   *   - tracks setReadTimeout / getReadTimeout in a mutable field
   *
   * Tests compose scripted responses, drive the GalilIo method under test, and
   * assert on captured writes + return value + state side effects (e.g. timeout).
   *
   * Convenience helpers:
   *   - `queueRead(s)` enqueues a string-encoded ByteString
   *   - `queueReadBytes(bs)` enqueues a raw ByteString
   *   - `queueDrain(s)` enqueues a drain return value
   *   - `writtenStrings` returns captured writes as decoded strings (for asserting)
   */
  private class StubGalilIo extends GalilIo:
    val writes:     mutable.ListBuffer[Array[Byte]] = mutable.ListBuffer.empty
    val readQueue:  mutable.Queue[ByteString]       = mutable.Queue.empty
    val drainQueue: mutable.Queue[String]           = mutable.Queue.empty
    var timeoutMs:  Int                              = 3000  // matches CCA normal value

    override protected def write(sendBuf: Array[Byte]): Unit =
      writes += sendBuf

    override protected def read(): ByteString =
      if readQueue.isEmpty then ByteString.empty else readQueue.dequeue()

    override def drainAndShowBuffer(timeoutMs: Int = 200): String =
      if drainQueue.isEmpty then "" else drainQueue.dequeue()

    override def close(): Unit = ()

    override def setReadTimeout(timeoutMs: Int): Unit = this.timeoutMs = timeoutMs
    override def getReadTimeout: Int                   = timeoutMs

    // Test conveniences
    def queueRead(s: String): Unit       = readQueue.enqueue(ByteString(s))
    def queueReadBytes(b: ByteString): Unit = readQueue.enqueue(b)
    def queueDrain(s: String): Unit      = drainQueue.enqueue(s)
    def writtenStrings: List[String]     = writes.map(b => new String(b)).toList

  // ========================================
  // writeRaw — appends \r\n and calls write
  // ========================================

  test("writeRaw appends CRLF and calls underlying write once") {
    val io = new StubGalilIo
    io.writeRaw("HELLO")
    io.writes should have size 1
    io.writtenStrings.head shouldBe "HELLO\r\n"
  }

  test("writeRaw with empty string still writes CRLF") {
    val io = new StubGalilIo
    io.writeRaw("")
    io.writtenStrings.head shouldBe "\r\n"
  }

  test("writeRaw with multi-line program preserves embedded newlines") {
    // Used during DL — uploadProgram streams the whole program as one writeRaw.
    val io = new StubGalilIo
    io.writeRaw("LINE1\r\nLINE2\r\nLINE3")
    io.writtenStrings.head shouldBe "LINE1\r\nLINE2\r\nLINE3\r\n"
  }

  // ========================================
  // send (single command) — basic protocol shape
  // ========================================

  test("send: single command writes cmd + CRLF and returns one (cmd, response) pair") {
    val io = new StubGalilIo
    // "MG TIME" → "12345\r\n:" (data response followed by colon ack)
    io.queueRead("12345\r\n:")
    val replies = io.send("MG TIME")
    io.writtenStrings.head shouldBe "MG TIME\r\n"
    replies should have size 1
    replies.head._1 shouldBe "MG TIME"
    replies.head._2.utf8String shouldBe "12345"  // \r\n: stripped
  }

  test("send: command with no data response returns empty ByteString (only ':' from controller)") {
    // Variable assignments like "speed[0]=500" return just ":" — receiveReplies
    // strips the colon, leaving empty.
    val io = new StubGalilIo
    io.queueRead(":")
    val replies = io.send("speed[0]=500")
    replies should have size 1
    replies.head._2.utf8String shouldBe ""
  }

  test("send: rejected command returns '?'") {
    val io = new StubGalilIo
    io.queueRead("?")
    val replies = io.send("BADCMD")
    replies should have size 1
    replies.head._2.utf8String shouldBe "?"
  }

  test("send: multi-packet response is concatenated correctly") {
    // Long responses can arrive in multiple TCP reads.  receiveReplies recurses
    // until it sees the terminator.
    val io = new StubGalilIo
    io.queueRead("PART1")
    io.queueRead("PART2")
    io.queueRead("FINAL\r\n:")
    val replies = io.send("MG something")
    replies.head._2.utf8String shouldBe "PART1PART2FINAL"
  }

  // ========================================
  // send — compound commands (semicolon-separated)
  // ========================================

  test("send: compound assignment pairs each sub-command with its ack") {
    // "a=1;b=2" → controller sends "::" (two acks in one read)
    val io = new StubGalilIo
    io.queueRead("::")
    val replies = io.send("a=1;b=2")
    replies should have size 2
    replies.map(_._1) shouldBe List("a=1", "b=2")
    replies.foreach(_._2.utf8String shouldBe "")
  }

  test("send: compound assignment+query pairs correctly") {
    // "speed[0]=500;MG speed[0]" returns ":\r\n500.0000\r\n:" — first is ack
    // for assignment, second is data response with trailing ack.
    //
    // Note: splitResponses only strips trailing \r\n, not leading.  The data
    // response retains its leading \r\n from the controller's wire format.
    // Downstream parsers normally trim, but at this layer the data is raw.
    val io = new StubGalilIo
    io.queueRead(":\r\n500.0000\r\n:")
    val replies = io.send("speed[0]=500;MG speed[0]")
    replies should have size 2
    replies.head._1 shouldBe "speed[0]=500"
    replies.head._2.utf8String shouldBe ""
    replies(1)._1 shouldBe "MG speed[0]"
    replies(1)._2.utf8String.trim shouldBe "500.0000"
  }

  test("send: compound with three sub-commands handled correctly") {
    val io = new StubGalilIo
    io.queueRead(":::")
    val replies = io.send("a=1;b=2;c=3")
    replies should have size 3
    replies.map(_._1) shouldBe List("a=1", "b=2", "c=3")
  }

  test("send: compound where last sub-command is rejected — '?' normalised, count preserved") {
    // ":?" means first sub-command OK, second rejected.  splitResponses
    // currently normalises "?" → ":" before splitting, so the rejection
    // signal is lost at this layer (both responses look like empty acks).
    // Callers that need to distinguish must use sendAndWaitForPrompt
    // (single command, dedicated "?" check) — the compound send path is
    // for batch ops where we don't expect mid-batch rejection.
    //
    // Documenting current behavior; if this proves to be a problem, the fix
    // is to preserve "?" as a distinct response value in splitResponses.
    val io = new StubGalilIo
    io.queueRead(":?")
    val replies = io.send("a=1;BADCMD")
    replies should have size 2
    replies.map(_._1) shouldBe List("a=1", "BADCMD")
    // Note: replies(1)._2 is empty, NOT "?".  This is a known limitation.
    replies(1)._2.utf8String shouldBe ""
  }

  test("send: compound responses arriving in multiple reads are accumulated") {
    val io = new StubGalilIo
    io.queueRead(":")
    io.queueRead("500.0000\r\n:")
    val replies = io.send("speed[0]=500;MG speed[0]")
    replies should have size 2
    replies(1)._2.utf8String.trim shouldBe "500.0000"
  }

  // ========================================
  // send — 80-character line guard (S53)
  // ========================================
  //
  // The Galil DMC parser silently truncates lines >80 chars.  The S53 work
  // caught a 102-char compound being mangled.  send() throws now to fail loud.

  test("send: exactly 80-char command is accepted") {
    val io = new StubGalilIo
    io.queueRead(":")
    val cmd = "a" * 80  // exactly 80 chars
    cmd.length shouldBe GalilIo.maxCommandLineLength
    io.send(cmd)  // no exception
  }

  test("send: 81-char command throws IllegalArgumentException") {
    val io = new StubGalilIo
    val cmd = "a" * 81
    val ex = intercept[IllegalArgumentException](io.send(cmd))
    ex.getMessage should include("80 chars")
    ex.getMessage should include("81")
  }

  test("send: long compound that exceeds 80 chars (combined) throws") {
    // 4 sub-commands of 20 chars each separated by ; = 4*20 + 3 = 83 chars.
    val io = new StubGalilIo
    val sub = "a" * 20
    val cmd = List.fill(4)(sub).mkString(";")
    cmd.length shouldBe 83
    intercept[IllegalArgumentException](io.send(cmd))
  }

  test("send: 80-char guard rejects BEFORE writing to socket") {
    val io = new StubGalilIo
    intercept[IllegalArgumentException](io.send("x" * 100))
    io.writes shouldBe empty  // nothing written
  }

  // ========================================
  // sendAndWaitForPrompt — drain-before-send + "?" detection
  // ========================================

  test("sendAndWaitForPrompt: drains before send (pre-drain ensures we consume only our own ack)") {
    val io = new StubGalilIo
    io.queueDrain("")  // pre-drain returns empty
    io.queueRead(":")  // controller acks our command
    io.sendAndWaitForPrompt("HX")
    io.drainQueue shouldBe empty  // drain was consumed
  }

  test("sendAndWaitForPrompt: success on ':' ack") {
    val io = new StubGalilIo
    io.queueRead(":")
    io.sendAndWaitForPrompt("MO")  // no exception
  }

  test("sendAndWaitForPrompt: throws on '?' rejection with command in message") {
    val io = new StubGalilIo
    io.queueRead("?")
    val ex = intercept[RuntimeException](io.sendAndWaitForPrompt("BADCMD"))
    ex.getMessage should include("BADCMD")
    ex.getMessage should include("rejected")
  }

  test("sendAndWaitForPrompt: 80-char guard still applies (delegates to send)") {
    val io = new StubGalilIo
    intercept[IllegalArgumentException](io.sendAndWaitForPrompt("x" * 100))
  }

  // ========================================
  // downloadProgram — UL response cleanup
  // ========================================

  test("downloadProgram: strips trailing backslash") {
    val io = new StubGalilIo
    // UL response is the program text terminated by backslash, then \r\n:
    io.queueRead("LINE1\r\nLINE2\\\r\n:")
    val program = io.downloadProgram()
    program shouldBe "LINE1\r\nLINE2"  // backslash stripped, trimmed
  }

  test("downloadProgram: strips trailing Control-Z (alternative terminator)") {
    val io = new StubGalilIo
    io.queueRead("LINE1\r\nLINE2\u001A\r\n:")
    val program = io.downloadProgram()
    program shouldBe "LINE1\r\nLINE2"
  }

  test("downloadProgram: returns empty for empty program") {
    val io = new StubGalilIo
    io.queueRead("\\\r\n:")  // just the terminator and ack
    io.downloadProgram() shouldBe ""
  }

  // ========================================
  // uploadProgram (S60–S62) — DL protocol with full encapsulation
  // ========================================
  //
  // The S60–S62 work consolidated the DL protocol mechanics into GalilIo.
  // These tests verify the encapsulation contract:
  //   - Pre-drain (buffer hygiene before DL)
  //   - Stream program text via writeRaw (DL then program)
  //   - Post-stream drain → throw if '?' detected (line count + preview in msg)
  //   - sendAndWaitForPrompt for '\' terminator
  //   - Second drain for DL's deferred ack
  //   - Read timeout extended to 10000ms for the upload window, restored finally

  test("uploadProgram: happy path streams DL + program + '\\' and consumes both acks") {
    val io = new StubGalilIo
    // Pre-drain: empty (clean buffer)
    io.queueDrain("")
    // Post-stream drain: empty (no rejection)
    io.queueDrain("")
    // sendAndWaitForPrompt("\\") drains first (this is the third drain) then reads
    io.queueDrain("")
    io.queueRead(":")
    // Post-prompt drain (DL's deferred ack)
    io.queueDrain(":")  // the deferred ack arrives in the final drain
    io.uploadProgram("PROG")
    val writes = io.writtenStrings
    writes should have size 3
    writes(0) shouldBe "DL\r\n"
    writes(1) shouldBe "PROG\r\n"
    writes(2) shouldBe "\\\r\n"
  }

  test("uploadProgram: post-stream '?' rejection throws with line count and preview") {
    val io = new StubGalilIo
    io.queueDrain("")             // pre-drain clean
    io.queueDrain("?\r\n?\r\n")   // two rejections
    val ex = intercept[RuntimeException](io.uploadProgram("BADPROG"))
    ex.getMessage should include("DL rejected")
    ex.getMessage should include("2 line(s)")  // count
    ex.getMessage should include("?")           // preview shows the chars
  }

  test("uploadProgram: single '?' rejection counts as 1 line") {
    val io = new StubGalilIo
    io.queueDrain("")
    io.queueDrain("?")
    val ex = intercept[RuntimeException](io.uploadProgram("PROG"))
    ex.getMessage should include("1 line(s)")
  }

  test("uploadProgram: '?' rejection prevents writing the '\\' terminator") {
    val io = new StubGalilIo
    io.queueDrain("")
    io.queueDrain("?")
    intercept[RuntimeException](io.uploadProgram("PROG"))
    val writes = io.writtenStrings
    writes should contain ("DL\r\n")
    writes should contain ("PROG\r\n")
    writes should not contain "\\\r\n"  // never wrote the terminator
  }

  test("uploadProgram: read timeout is extended to 10000ms during upload") {
    // Capture the timeout that's active when the first drain runs — that's
    // after setReadTimeout(10000) but before any other state change.
    // Array[Int] gives a stable holder we can mutate from the override.
    val captured = Array(-1)
    val capturingIo = new StubGalilIo:
      override def drainAndShowBuffer(timeoutMs: Int = 200): String =
        if captured(0) == -1 then captured(0) = this.timeoutMs
        super.drainAndShowBuffer(timeoutMs)
    capturingIo.timeoutMs = 3000
    capturingIo.queueDrain("")
    capturingIo.queueDrain("")
    capturingIo.queueDrain("")
    capturingIo.queueRead(":")
    capturingIo.queueDrain(":")
    capturingIo.uploadProgram("PROG")
    captured(0) shouldBe 10000
  }

  test("uploadProgram: read timeout is restored after successful upload") {
    val io = new StubGalilIo
    io.timeoutMs = 3000
    io.queueDrain("")
    io.queueDrain("")
    io.queueDrain("")
    io.queueRead(":")
    io.queueDrain(":")
    io.uploadProgram("PROG")
    io.timeoutMs shouldBe 3000  // restored in finally
  }

  test("uploadProgram: read timeout is restored even when '?' rejection throws") {
    // The finally clause must restore timeout regardless of how we exit.
    val io = new StubGalilIo
    io.timeoutMs = 3000
    io.queueDrain("")
    io.queueDrain("?")
    intercept[RuntimeException](io.uploadProgram("PROG"))
    io.timeoutMs shouldBe 3000  // restored even on exception
  }

  test("uploadProgram: read timeout is restored from caller's value (not hardcoded 3000)") {
    // Caller may have set a different timeout — e.g. 0 (block forever) for
    // BZ setup.  uploadProgram should restore that value, not assume 3000.
    val io = new StubGalilIo
    io.timeoutMs = 5000  // unusual caller value
    io.queueDrain("")
    io.queueDrain("")
    io.queueDrain("")
    io.queueRead(":")
    io.queueDrain(":")
    io.uploadProgram("PROG")
    io.timeoutMs shouldBe 5000  // restored caller's value, not 3000
  }

  test("uploadProgram: does NOT send HX (caller responsibility)") {
    val io = new StubGalilIo
    io.queueDrain("")
    io.queueDrain("")
    io.queueDrain("")
    io.queueRead(":")
    io.queueDrain(":")
    io.uploadProgram("PROG")
    io.writtenStrings should not contain "HX\r\n"
  }

  test("uploadProgram: pre-drain runs before DL is written") {
    // Order matters: drain first (buffer hygiene), then DL.  If the drain
    // ran after DL, any stray byte would already be misinterpreted.
    val drainSeenBeforeWrite = Array(false)
    val anyWrite = Array(false)
    val orderedIo = new StubGalilIo:
      override protected def write(sendBuf: Array[Byte]): Unit =
        anyWrite(0) = true
        super.write(sendBuf)
      override def drainAndShowBuffer(timeoutMs: Int = 200): String =
        if !anyWrite(0) then drainSeenBeforeWrite(0) = true
        super.drainAndShowBuffer(timeoutMs)
    orderedIo.queueDrain("")
    orderedIo.queueDrain("")
    orderedIo.queueDrain("")
    orderedIo.queueRead(":")
    orderedIo.queueDrain(":")
    orderedIo.uploadProgram("PROG")
    drainSeenBeforeWrite(0) shouldBe true
  }

  // ========================================
  // GalilIo.chunkCompound (S53) — pure helper for 80-char packing
  // ========================================

  test("chunkCompound: empty input → empty output") {
    GalilIo.chunkCompound(Nil) shouldBe Nil
    GalilIo.chunkCompound(Seq.empty) shouldBe Nil
  }

  test("chunkCompound: single short command → single chunk equal to input") {
    GalilIo.chunkCompound(Seq("a=1")) shouldBe Seq("a=1")
  }

  test("chunkCompound: multiple short commands pack into single chunk under 80 chars") {
    val cmds = Seq("a=1", "b=2", "c=3")
    val result = GalilIo.chunkCompound(cmds)
    result should have size 1
    result.head shouldBe "a=1;b=2;c=3"
    result.head.length should be <= GalilIo.maxCommandLineLength
  }

  test("chunkCompound: every output chunk is <= maxCommandLineLength") {
    // 8 commands of 20 chars each = 160 chars total; chunks must respect 80 limit.
    val cmds = (1 to 8).map(i => s"cmd${i}_long_${i.toString * 8}")  // ~20 chars each
    val result = GalilIo.chunkCompound(cmds)
    result.foreach(chunk => chunk.length should be <= GalilIo.maxCommandLineLength)
  }

  test("chunkCompound: packing is greedy (no premature breaks)") {
    // Three 25-char commands: combined length 25+1+25+1+25 = 77 ≤ 80, so one chunk.
    val cmd = "x" * 25
    val cmds = Seq(cmd, cmd, cmd)
    val result = GalilIo.chunkCompound(cmds)
    result should have size 1
    result.head.length shouldBe 77
  }

  test("chunkCompound: splits when next command would exceed limit") {
    // Three 30-char commands: combined 30+1+30+1+30 = 92, must split.
    // First two: 30+1+30 = 61 (fits).  Third alone: 30.  So 2 chunks.
    val cmd = "x" * 30
    val cmds = Seq(cmd, cmd, cmd)
    val result = GalilIo.chunkCompound(cmds)
    result should have size 2
    result.head.length shouldBe 61
    result(1).length shouldBe 30
  }

  test("chunkCompound: boundary at exactly 80 chars in a chunk") {
    // Two 39-char commands: 39+1+39 = 79 (fits).
    // Two 40-char commands: 40+1+40 = 81 (must split).
    val r79 = GalilIo.chunkCompound(Seq("x" * 39, "x" * 39))
    r79 should have size 1
    r79.head.length shouldBe 79
    val r81 = GalilIo.chunkCompound(Seq("x" * 40, "x" * 40))
    r81 should have size 2
  }

  test("chunkCompound: a single sub-command longer than 80 is passed through unchanged") {
    // chunkCompound's docstring: over-length sub-command returned as single-element
    // chunk; `send` will then reject it with IAE — the caller sees exactly which
    // sub-command is the problem.
    val overlong = "x" * 100
    val result = GalilIo.chunkCompound(Seq(overlong))
    result should have size 1
    result.head shouldBe overlong  // passed through, not silently truncated
  }

  test("chunkCompound: catches the S53 regression case (102-char compound truncation)") {
    // The original S53 bug: writeMotionConfig was sending a 102-char compound,
    // silently truncated by the controller.  chunkCompound should split it
    // into two chunks of <=80 chars each.
    // Approximate a realistic motion-config compound:
    val cmds = Seq(
      "speed[0]=20000",   // 14
      "accel[0]=256000",  // 15
      "decel[0]=256000",  // 15
      "iposthr[0]=1",     // 12
      "idxofs[0]=0",      // 11
      "idxspd[0]=256",    // 13
      "cpr[0]=629760",    // 14
      "mdelay[0]=4"       // 11
    )
    val totalIfOneCompound = cmds.mkString(";").length  // sum + separators
    totalIfOneCompound should be > GalilIo.maxCommandLineLength  // confirms the bug condition
    val chunks = GalilIo.chunkCompound(cmds)
    chunks.size should be >= 2
    chunks.foreach(_.length should be <= GalilIo.maxCommandLineLength)
    // All sub-commands appear somewhere (no silent drop)
    val recombined = chunks.flatMap(_.split(';')).toSet
    recombined shouldBe cmds.toSet
  }

  // ========================================
  // GalilIo.maxCommandLineLength — constant
  // ========================================

  test("maxCommandLineLength is 80 (per Galil DMC parser)") {
    GalilIo.maxCommandLineLength shouldBe 80
  }

  // ========================================
  // GalilIo object constants
  // ========================================

  test("separator constant is \\r\\n") {
    GalilIo.separator shouldBe "\r\n"
  }

  test("endMarker constant is \\r\\n:") {
    GalilIo.endMarker shouldBe "\r\n:"
  }

  // ========================================
  // GalilIo.chunkMgOperands (S90) — packing the arguments of one compound MG
  // ========================================

  test("chunkMgOperands: empty input → empty output (no round trip)") {
    GalilIo.chunkMgOperands(Nil) shouldBe Nil
  }

  test("chunkMgOperands: operands that fit stay in one command") {
    GalilIo.chunkMgOperands(Seq("ae[0]", "ae[1]")) shouldBe Seq("MG ae[0],ae[1]")
  }

  test("chunkMgOperands: the whlpos[] regression — 8 axes must be split, 7 must not") {
    // The bug this helper exists for: `MG whlpos[0],...,whlpos[7]` is 82 characters,
    // which GalilIo.send refuses to write, so the achieved wheel-slot read silently
    // never happened on any 8-axis configuration (the HMI showed a permanent "unknown
    // slot" and selectWheel's inPosition never went true).  Seven axes fit at 72, which
    // is why the 5- and 6-axis APS configs and the 7-axis STB config never showed it.
    val eight = (0 to 7).map(i => s"whlpos[$i]")
    ("MG " + eight.mkString(",")).length shouldBe 82           // confirms the bug condition
    82 should be > GalilIo.maxCommandLineLength

    val chunks = GalilIo.chunkMgOperands(eight)
    chunks should have size 2
    chunks.map(_.length) shouldBe Seq(72, 12)
    chunks.foreach(_.length should be <= GalilIo.maxCommandLineLength)

    val seven = (0 to 6).map(i => s"whlpos[$i]")
    GalilIo.chunkMgOperands(seven) should have size 1
  }

  test("chunkMgOperands: the _PV/_BT read splits at 8 tracking axes") {
    // Same arithmetic as whlpos[]: 16 four-character operands is an 82-char line.
    val operands = ('A' to 'H').flatMap(c => Seq(s"_PV$c", s"_BT$c"))
    ("MG " + operands.mkString(",")).length shouldBe 82
    val chunks = GalilIo.chunkMgOperands(operands)
    chunks should have size 2
    chunks.foreach(_.length should be <= GalilIo.maxCommandLineLength)
  }

  test("chunkMgOperands: operand order is preserved across chunks") {
    // The caller zips the concatenated reply tokens against the request list, so a
    // reordering here would silently attribute one axis's slot to another.
    val operands = (0 to 7).map(i => s"whlpos[$i]")
    val recombined = GalilIo.chunkMgOperands(operands).flatMap(_.stripPrefix("MG ").split(','))
    recombined shouldBe operands
  }

  test("chunkMgOperands: every chunk is a well-formed MG and fits the line buffer") {
    val operands = (0 to 20).map(i => s"someLongArrayName[$i]")
    val chunks = GalilIo.chunkMgOperands(operands)
    chunks.foreach { c =>
      c should startWith("MG ")
      c.length should be <= GalilIo.maxCommandLineLength
    }
    chunks.flatMap(_.stripPrefix("MG ").split(',')) shouldBe operands
  }

  test("chunkMgOperands: an operand too long to fit is passed through, not truncated") {
    // Documented behavior: it becomes its own chunk, which send then rejects, so the
    // caller sees which operand is at fault rather than reading truncated values.
    val overlong = "x" * 100
    val chunks = GalilIo.chunkMgOperands(Seq(overlong))
    chunks should have size 1
    chunks.head shouldBe "MG " + overlong
  }
