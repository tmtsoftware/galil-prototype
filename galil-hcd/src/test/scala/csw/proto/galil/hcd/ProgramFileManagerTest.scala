package csw.proto.galil.hcd

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * Tests for ProgramFileManager's pure text-processing logic — the DL upload
 * preparation (S60) and the LS download parsing.  These are pure String→String
 * functions, so the suite needs no actor harness or filesystem.
 *
 * prepareProgramForUpload carries the S60 fix: the Galil DL parser silently
 * truncates lines over 80 chars, so the HCD strips REM/blank lines, trims
 * trailing whitespace, compresses overlong lines (whitespace outside string
 * literals and comments), and hard-fails if any line still exceeds 80 chars.
 * A regression here silently corrupts every DL upload — and therefore the
 * faultReset Reload recovery path — so it is worth a regression guard.
 */
class ProgramFileManagerTest extends AnyFunSuite with Matchers:

  private val Max = 80

  // ========================================
  // prepareProgramForUpload
  // ========================================

  test("prepareProgramForUpload strips REM lines, blank lines, and trailing whitespace"):
    val raw =
      "#Init\n" +
      "REM this is a comment line\n" +
      "\n" +
      "   \n" +
      "speed[0]=1000   \n" +
      "  REM indented comment\n" +
      "EN"
    val out = ProgramFileManager.prepareProgramForUpload(raw)
    val lines = out.split("\r\n").toList
    lines shouldBe List("#Init", "speed[0]=1000", "EN")

  test("prepareProgramForUpload joins lines with CR+LF"):
    val out = ProgramFileManager.prepareProgramForUpload("#Init\nEN")
    out shouldBe "#Init\r\nEN"

  test("prepareProgramForUpload leaves short lines unchanged"):
    val line = "speed[0]=1000;accel[0]=9216"
    ProgramFileManager.prepareProgramForUpload(line) shouldBe line

  test("prepareProgramForUpload compresses an over-80-char line by stripping whitespace outside strings"):
    // 7-param compound padded with spaces to exceed 80 chars; compression
    // removes the inter-token whitespace and brings it under the limit.
    val long = "speed[0]=1000 ; accel[0]=9216 ; decel[0]=9216 ; hspd[0]=500 ; hoff[0]=0 ; mdelay[0]=100"
    long.length should be > Max
    val out = ProgramFileManager.prepareProgramForUpload(long)
    out should not include " "
    out.length should be <= Max
    out shouldBe "speed[0]=1000;accel[0]=9216;decel[0]=9216;hspd[0]=500;hoff[0]=0;mdelay[0]=100"

  test("prepareProgramForUpload throws when a line still exceeds 80 chars after compression"):
    // A single token with no compressible whitespace, longer than 80 chars.
    val unshrinkable = "MG " + "\"" + ("X" * 90) + "\""
    val ex = intercept[RuntimeException] {
      ProgramFileManager.prepareProgramForUpload(unshrinkable)
    }
    ex.getMessage should include ("80")

  // ========================================
  // compressLine
  // ========================================

  test("compressLine strips whitespace outside strings and comments"):
    ProgramFileManager.compressLine("a = b + c") shouldBe "a=b+c"

  test("compressLine preserves whitespace inside a string literal"):
    ProgramFileManager.compressLine("""MG "hello world"""") shouldBe """MG"hello world""""

  test("compressLine preserves everything after an inline comment marker"):
    ProgramFileManager.compressLine("x = 1 ' set x to 1") shouldBe "x=1' set x to 1"

  test("compressLine handles a line with both a string and a trailing comment"):
    ProgramFileManager.compressLine("""MG "a b" ' note here""") shouldBe """MG"a b"' note here"""

  // ========================================
  // parseDownloadedPrograms
  // ========================================

  test("parseDownloadedPrograms strips leading line numbers and terminators"):
    val ls = "0 #AUTO\n1 EN\n:\n2 #Init\n3 speed[0]=1000\n:"
    ProgramFileManager.parseDownloadedPrograms(ls) shouldBe
      "#AUTO\nEN\n#Init\nspeed[0]=1000"

  test("parseDownloadedPrograms removes blank lines"):
    val ls = "0 #AUTO\n\n   \n1 EN"
    ProgramFileManager.parseDownloadedPrograms(ls) shouldBe "#AUTO\nEN"

  test("parseDownloadedPrograms leaves a line without a numeric prefix unchanged"):
    // No "<digits> " prefix → returned as-is (after trim).
    ProgramFileManager.parseDownloadedPrograms("#NoNumberHere") shouldBe "#NoNumberHere"

  test("parseDownloadedPrograms keeps the remainder when the value itself contains spaces"):
    ProgramFileManager.parseDownloadedPrograms("12 MG \"hello world\"") shouldBe "MG \"hello world\""

  // ========================================
  // createTimestampedFilename
  // ========================================

  test("createTimestampedFilename uses the prefix and yyyyMMdd_HHmmss.dmc pattern"):
    ProgramFileManager.createTimestampedFilename() should fullyMatch regex """backup_\d{8}_\d{6}\.dmc"""
    ProgramFileManager.createTimestampedFilename("snap") should fullyMatch regex """snap_\d{8}_\d{6}\.dmc"""