package scc

import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.MatcherAssert.assertThat
import org.junit.jupiter.api.Assertions.{assertEquals, assertThrows}
import org.junit.jupiter.api.MethodOrderer.MethodName
import org.junit.jupiter.api.{Nested, Test, TestMethodOrder}
import verification.Checks._
import verification.HaltCode
import verification.Verification._

import java.io.{File, FileOutputStream, PrintWriter}

@TestMethodOrder(classOf[MethodName])
class SpamCCTest {
  val gamepadControl = "../../verilog/cpu/gamepad.control"

  def split(s: String): List[String] = {
    val strings = s.split("\n")
    strings
      .map(_.stripTrailing().stripLeading())
      .filterNot(_.isBlank).toList
  }

  def assertSame(expected: List[String], actual: List[String]): Unit = {
    println("\nCOMPARING ASM:")
    if (expected != actual) {
      println("Expected: " + expected)
      println("Actual  : " + actual)

      val e = expected.map(_.stripTrailing().stripLeading()).mkString("\n")
      val a = actual.map(_.stripTrailing().stripLeading()).mkString("\n")
      assertEquals(e, a)
    }
  }

  @Test
  def varDataFromZeroLenDataAtPosition0IsIllegal(): Unit = {
    // illegal because a zero len data at relative location means no memory allocated
    val lines =
      """fun main() {
        | var s = [0: [  ] ];
        |}
        |""".stripMargin

    val ex = assertThrows(classOf[RuntimeException], () => compile(lines, verbose = true))
    assertThat(ex.getMessage, containsString("located data at offset 0 cannot be empty at :  var s = [0: [  ] ];"))
  }

  @Test
  def varDataFromFile(): Unit = {

    val lines =
      """fun main() {
        | var s = [file("src/test/resources/SomeData.txt")];
        |}
        |""".stripMargin


    val actual = compile(lines, verbose = true)

    val expected = split(
      """B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |B3___VAR_s: EQU   2
        |B3___VAR_s: BYTES [65, 66, 10, 67]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END""".stripMargin)

    assertSame(expected, actual)
  }

  @Test
  def varDataFromFileViaSystemProp(): Unit = {

    val fileName = "src/test/resources/SomeData.txt"
    System.setProperty("FILENAME", fileName)
    val lines =
      """fun main() {
        | var s = [file(systemProp("FILENAME"))];
        |}
        |""".stripMargin

    val actual = compile(lines, verbose = true)

    val expected = split(
      """B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |B3___VAR_s: EQU   2
        |B3___VAR_s: BYTES [65, 66, 10, 67]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END""".stripMargin)

    assertSame(expected, actual)
  }

  @Test
  def varDataFromBytes(): Unit = {

    // two equivalents
    val linesA =
      """fun main() {
        | var s = [ 00 01 $ff ];
        |}
        |""".stripMargin

    val linesB =
      """fun main() {
        | var s = [0: [ 00 01 $ff ] ];
        |}
        |""".stripMargin

    val actualA = compile(linesA, verbose = true)
    val actualB = compile(linesB, verbose = true)

    val expected = split(
      """
        |B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |B3___VAR_s: EQU   2
        |B3___VAR_s: BYTES [0, 1, -1]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END""".stripMargin)

    assertSame(expected, actualA)
    assertSame(expected, actualB)
  }

  @Test
  def varDataFromLongFile(): Unit = {

    val lines =
      """fun main() {
        | var s = [file("src/test/resources/LotOfData.txt")];
        | short a0 = s[0];
        | short a1= s[1];
        | short a15= s[15];
        | short a16= s[16];
        | short a99= s[99];
        | short a100= s[100];
        | short a200= s[200];
        | short a254= s[254];
        | short a255= s[255];
        | short a256 = s[256];
        | short a338 = s[337];
        | putuart(a0)
        | putuart(a1)
        | putuart(a15)
        | putuart(a16)
        | putuart(a99)
        | putuart(a100)
        | putuart(a200)
        | putuart(a254)
        | putuart(a255)
        | putuart(a256)
        | putuart(a338)
        |
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedChars('d', str, List('0', '1', 'F', 'G', 'O', 'P', '3', '4', '5', '6', 'F'))
    })
  }

  @Test
  def varFromAll255File(): Unit = {

    val data = File.createTempFile(this.getClass.getName, ".dat")
    val os = new FileOutputStream(data)
    (0 to 255).foreach {
      x => os.write(x)
    }
    os.close()

    // backslash in names look like escapes
    val fileForwrdSlash = data.getAbsolutePath.replaceAll("\\\\", "/")

    val lines =
      s"""fun main() {
         | var s = [file("$fileForwrdSlash")];
         |}
         |""".stripMargin

    val actual = compile(lines, verbose = true)

    val expected = split(
      """
        |B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |B3___VAR_s: EQU   2
        |B3___VAR_s: BYTES [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50, 51, 52, 53, 54, 55, 56, 57, 58, 59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 80, 81, 82, 83, 84, 85, 86, 87, 88, 89, 90, 91, 92, 93, 94, 95, 96, 97, 98, 99, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112, 113, 114, 115, 116, 117, 118, 119, 120, 121, 122, 123, 124, 125, 126, 127, -128, -127, -126, -125, -124, -123, -122, -121, -120, -119, -118, -117, -116, -115, -114, -113, -112, -111, -110, -109, -108, -107, -106, -105, -104, -103, -102, -101, -100, -99, -98, -97, -96, -95, -94, -93, -92, -91, -90, -89, -88, -87, -86, -85, -84, -83, -82, -81, -80, -79, -78, -77, -76, -75, -74, -73, -72, -71, -70, -69, -68, -67, -66, -65, -64, -63, -62, -61, -60, -59, -58, -57, -56, -55, -54, -53, -52, -51, -50, -49, -48, -47, -46, -45, -44, -43, -42, -41, -40, -39, -38, -37, -36, -35, -34, -33, -32, -31, -30, -29, -28, -27, -26, -25, -24, -23, -22, -21, -20, -19, -18, -17, -16, -15, -14, -13, -12, -11, -10, -9, -8, -7, -6, -5, -4, -3, -2, -1]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END""".stripMargin)

    assertSame(expected, actual)
  }

  @Test
  def varDataFromLocatedMixedData(): Unit = {

    val data = File.createTempFile(this.getClass.getName, ".dat")
    val os = new FileOutputStream(data)
    ('a' to 'c').foreach {
      x => os.write(x)
    }
    os.close()

    // backslash in names look like escapes
    val fileForwardSlash = data.getAbsolutePath.replaceAll("\\\\", "/")

    val lines =
      s"""fun main() {
         | var s = [
         |    2: [file("$fileForwardSlash")]
         |    10: [ 'A' 'B' 'C' ]
         |    15: [ ]
         | ];
         |}
         |""".stripMargin

    val actual = compile(lines, verbose = true)

    val expected = split(
      """
        |B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |B3___VAR_s: EQU   2
        |B3___VAR_s: BYTES [0, 0, 97, 98, 99, 0, 0, 0, 0, 0, 65, 66, 67, 0, 0]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END""".stripMargin)

    assertSame(expected, actual)
  }

  @Test
  def varEq1(): Unit = {

    val lines =
      """fun main() {
        | short a=1;
        |}
        |""".stripMargin

    val actual: List[String] = compile(lines)

    val expected = split(
      """
        |B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |B3___VAR_a: EQU   2
        |B3___VAR_a: BYTES [0, 0]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |REGA = > 1
        |REGD = < 1
        |[:B3___VAR_a] = REGA
        |[:B3___VAR_a+1] = REGD
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END""".stripMargin)

    assertSame(expected, actual)
  }

  @Test
  def varEqHex4142(): Unit = {

    val lines =
      """
        |
        |fun main(x) {
        |
        | // $42 = 66 dev
        | // $41 = 65 dec
        | short a = $4142;
        |
        | // $4142 >> 1 => 20A1
        | // $A1 = 161 dec
        | putuart(a >> 1)
        |
        | short b = a >> 1;
        | putuart(b) // 161
        |
        | // $4142 & $ff => 42 => 66 dec
        | putuart(a) // should be 66
        | putuart(a & $ff )
        |
        |}
        |
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>

        checkTransmittedL('d', str, List("161", "161", "66", "66"))
    })
  }

  def toDecStr(bits: String): String = {
    val d = Integer.parseInt(bits, 2)
    d.toString
  }

  @Test
  def varLSR(): Unit = {

    val lines =
      """
        |
        |fun main(x) {
        |
        | short a = %0100000101000010;
        |
        | putuart(a)
        | putuart(a >> 0)
        | putuart(a >> 1)
        | putuart(a >> 2)
        | putuart(a >> 3)
        | putuart(a >> 8)
        | putuart(a >> 9)
        | putuart(a >> 16)
        |
        |}
        |
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>

        checkTransmittedL('d', str, List(
          toDecStr("01000010"),
          toDecStr("01000010"),
          toDecStr("10100001"),
          toDecStr("01010000"),
          toDecStr("00101000"),
          toDecStr("01000001"),
          toDecStr("00100000"),
          toDecStr("00000000"),
        ))
    })
  }

  @Test
  def varASR(): Unit = {

    val lines =
      """
        |
        |fun main(x) {
        |
        | short positive = %0100000101000010;
        |
        | putuart(positive)
        | putuart(positive >>> 0)
        | putuart(positive >>> 1)
        | putuart(positive >>> 2)
        | putuart(positive >>> 3)
        | putuart(positive >>> 8)
        | putuart(positive >>> 9)
        | putuart(positive >>> 16)
        |
        | short negative = %1100000101000010;
        |
        | putuart(negative)
        | putuart(negative >>> 0)
        | putuart(negative >>> 1)
        | putuart(negative >>> 2)
        | putuart(negative >>> 3)
        | putuart(negative >>> 8)
        | putuart(negative >>> 9)
        | putuart(negative >>> 16)
        |
        }
        |
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>

        checkTransmittedL('d', str, List(
          // pos
          toDecStr("01000010"),
          toDecStr("01000010"),
          toDecStr("10100001"),
          toDecStr("01010000"),
          toDecStr("00101000"),
          toDecStr("01000001"),
          toDecStr("00100000"),
          toDecStr("00000000"),
          // neg
          toDecStr("01000010"),
          toDecStr("01000010"),
          toDecStr("10100001"),
          toDecStr("01010000"),
          toDecStr("00101000"),
          toDecStr("11000001"),
          toDecStr("11100000"),
          toDecStr("11111111"),
        ))
    })
  }

  @Test
  def varLSLAll(): Unit = {

    val lines =
      """
        |
        |fun main(x) {
        |
        | short b = %1001;
        |
        | putuart( b << 0 )
        | putuart( b << 1 )
        | putuart( b << 2 )
        | putuart( b << 3 )
        | putuart( b << 4 )
        | putuart( b << 5 )
        | putuart( b << 6 )
        | putuart( b << 7 )
        | putuart( b << 8 )
        | putuart( ((b << 1)) >> 1  )
        |
        | // bug noticed in mandelbrot work << is broken for 16 bits and this number happens to illustrate the issue
        |foo: {
        | short init = $ffbc;
        | short expected =$ff78;
        | short actual = init << 1;
        | short mult = init * 2;
        |
        | if (actual != expected) {
        |   halt(actual, 99)
        | }
        |
        | if (mult != expected) {
        |   halt(actual, 88)
        | }
        |}
        |
        |bar: {
        | short i = $ffc7;
        | short init = i >>> 6;
        | short actual = init << 1;
        | short mult = init * 2;
        |
        | short expected =$fffe;
        | if (actual != expected) {
        |   halt(actual, 91)
        | }
        |
        | if (mult != expected) {
        |   halt(actual, 81)
        | }
        |}
        |}
        |
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>

        checkTransmittedL('b', str, List(
          "00001001", "00010010", "00100100", "01001000", "10010000", "00100000", "01000000", "10000000", "00000000", "00001001",
        ))
    })
  }

  @Test
  def varLogicalAnd(): Unit = {

    val lines =
      """
        |
        |fun main(x) {
        |
        | short a = $ABCD;
        |
        | putuart(a & $f0)
        | putuart(a & $0f)
        | putuart( (a>> 8) & $f0)
        | putuart( a>> 12)
        | putuart( (a>> 8) & $0f)
        |
        | if ( (a & $f000) == $a000 ) {
        |   putuart( '=' )
        | }
        |
        | if ( (a & $f000) == $a001 ) {
        |   // not expected to go here
        |   putuart( '!' )
        | }
        |
        |}
        |
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>

        checkTransmittedL('h', str, List("c0", "0d", "a0", "0a", "0b", '='.toInt.toHexString))
    })
  }

  @Test
  def varEqIfElse(): Unit = {

    val lines =
      """
        |
        |fun main(x) {
        | short a = 2;
        |
        | if (a>2) {
        |   putuart(1)
        | } else {
        |   if (a<2) {
        |     putuart(2)
        |   }
        |   else { // ==
        |     putuart(3)
        |   }
        | }
        |
        | if (a>2) {
        |   putuart(1)
        | } else if (a<2) {
        |     putuart(2)
        | }
        | else { // ==
        |     putuart(3)
        | }
        |}
        |
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('d', str, List("3", "3"))
    })
  }

  @Test
  def varEqIfCondition(): Unit = {

    val lines =
      """
        |
        |fun main(x) {
        |
        | // = 1110
        | short a = $0100;
        | a = a + $1010;
        |
        | if (a>$1110) {
        |   putuart(1)
        | } else
        |   if (a<$1110) {
        |     putuart(2)
        |   }
        |   else { // ==
        |     putuart(3)
        |   }
        |
        |
        | if (a>$1010) {
        |   putuart(11)
        | } else
        |   if (a<$1010) {
        |     putuart(12)
        |   } else { // ==
        |     putuart(13)
        |   }
        |
        |
        | if (a>$1210) {
        |   putuart(21)
        | } else if (a<$1210) {
        |   putuart(22)
        | } else { // ==
        |   putuart(23)
        | }
        |
        |
        | // comparing lower byte
        | if (a>$1101) {
        |   putuart(31)
        | } else {
        |   if (a<$1101) {
        |     putuart(32)
        |   } else { // ==
        |     putuart(33)
        |   }
        | }
        |
        | if (a>$1120) {
        |   putuart(41)
        | } else {
        |   if (a<$1120) {
        |     putuart(42)
        |   } else { // ==
        |     putuart(43)
        |   }
        | }
        |
        | // compare
        | short b=$1000;
        |
        | if ( a == (b+$110) ) {
        |  putuart('=')
        | }
        |
        | if ( a != (b+$110) ) {
        |  putuart('~')
        | }
        |
        | if ( a != b ) {
        |  putuart('!')
        | }
        |
        |}
        |
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('d', str, List("3", "11", "22", "31", "42", '='.toInt.toString, '!'.toInt.toString))
    })
  }

  @Test
  def varScopeVarsWithIfBlock(): Unit = {

    val lines =
      """
        |fun main(x) {
        | short a = 0;
        | if (a == 0) {
        |     short thevar = 0;
        | }
        | else if (a == 1) {
        |     short thevar = 1;
        | }
        |}
        |""".stripMargin

    compile(lines, verbose = true)
    // no other assertion yet
  }

  @Test
  def varEqVar(): Unit = {

    val lines =
      """fun main() {
        | short a=1;
        | short b=a;
        |}
        |""".stripMargin

    val actual: List[String] = compile(lines)

    val expected = split(
      """
        |B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |B3___VAR_a: EQU   2
        |B3___VAR_a: BYTES [0, 0]
        |B3___VAR_b: EQU   4
        |B3___VAR_b: BYTES [0, 0]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |REGA = > 1
        |REGD = < 1
        |[:B3___VAR_a] = REGA
        |[:B3___VAR_a+1] = REGD
        |REGA = [:B3___VAR_a]
        |REGD = [:B3___VAR_a + 1]
        |[:B3___VAR_b] = REGA
        |[:B3___VAR_b+1] = REGD
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END
        |""".stripMargin)

    assertSame(expected, actual)
  }

  @Test
  def varEqConstExpr(): Unit = {

    val lines =
      """
        |fun main() {
        | short a=1;
        | short b=64+1;
        |}
        |""".stripMargin

    val actual: List[String] = compile(lines)

    val expected = split(
      """
        |B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |B3___VAR_a: EQU   2
        |B3___VAR_a: BYTES [0, 0]
        |B3___VAR_b: EQU   4
        |B3___VAR_b: BYTES [0, 0]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |REGA = > 1
        |REGD = < 1
        |[:B3___VAR_a] = REGA
        |[:B3___VAR_a+1] = REGD
        |REGA = > 65
        |REGD = < 65
        |[:B3___VAR_b] = REGA
        |[:B3___VAR_b+1] = REGD
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END""".stripMargin)

    assertSame(expected, actual)
  }


  @Test
  def twoFunctionsSameVarNames(): Unit = {

    val lines =
      """
        |fun other() {
        | short a=100;
        | short b=200;
        | putuart(a)
        | putuart(b)
        |}
        |fun main() {
        | short a=1;
        | short b=2;
        | other()
        | putuart(a)
        | putuart(b)
        |}
        |""".stripMargin


    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('d', str, List("100", "200", "1", "2"))
    })
  }

  @Test
  def varEqSimpleTwoArgExpr(): Unit = {

    val lines =
      """
        |fun main() {
        |  // a = 63 + 2 = 'A'
        |  short a = 63 + 2;
        |
        |  // b = a + 1 = 'B'
        |  short b = a + 1;
        |
        |  // c = 1 + b = 'C'
        |  short c = 1 + b;
        |
        |  // d = c the d++
        |  short d = c;
        |  d = d + 1;
        |
        |  // e = a + (b/2) = 'b'
        |  short e = a + (b/2);
        |
        |  // should print 'A'
        |  putuart(a)
        |  // should print 'B'
        |  putuart(b)
        |  // should print 'C'
        |  putuart(c)
        |  // should print 'D'
        |  putuart(d)
        |  // should print 'b'
        |  putuart(e)
        |
        |  // should shift left twice to become the '@' char
        |  a = %00010000;
        |  b = 2;
        |  short at = a << b;
        |  // should print '@'
        |  putuart(at)
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = str => {
      checkTransmittedChar(str, 'A')
      checkTransmittedChar(str, 'B')
      checkTransmittedChar(str, 'C')
      checkTransmittedChar(str, 'D')
      checkTransmittedChar(str, 'b')
      checkTransmittedChar(str, '@')
    })
  }

  @Test
  def varEqNestedExpr(): Unit = {

    val lines =
      """fun main() {
        |  short a = 64;
        |  short b = 1 + (a + 3);
        |  putuart(b)
        |}
        |""".stripMargin

    compile(lines, outputCheck = str => {
      checkTransmittedChar(str, 'D')
    })

  }

  @Test
  def putuartUsingConditionInvert(): Unit = {
    /*
    * Purpose of this test doing a validation by comparing the code is to double check that the more efficient condition invert bit is being employed
    *
    * Previous logic was ..
    *
    * root_function_main_putuartConst_65____LABEL_wait_1:
    * PCHITMP = <:root_function_main_putuartConst_65____LABEL_transmit_2
    * PC = >:root_function_main_putuartConst_65____LABEL_transmit_2 _DO
    * PCHITMP = <:root_function_main_putuartConst_65____LABEL_wait_1
    * PC = >:root_function_main_putuartConst_65____LABEL_wait_1
    * root_function_main_putuartConst_65____LABEL_transmit_2:
    * UART = 65
    *
    * New logic is ....
    *
    * root_function_main_putuartConst_65____LABEL_wait_1:
    * PCHITMP = <:root_function_main_putuartConst_65____LABEL_wait_1
    * PC = >:root_function_main_putuartConst_65____LABEL_wait_1 ! _DO
    * UART = 65
    *
    * Saving two instructions and simpler logic.
    */

    val lines =
      """
        |fun main() {
        |  putuart('A')
        |}
        |""".stripMargin

    val code: List[String] = compile(lines, verbose = true, outputCheck = str => {
      checkTransmittedL('c', str, List("A"))
    })

    val expected = split(
      """B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |B4___LABEL_wait_2:
        |PCHITMP = <:B4___LABEL_wait_2
        |PC = >:B4___LABEL_wait_2 ! _DO
        |UART = 65
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END""".stripMargin)

    assertSame(expected, code)
  }

  @Test
  def putuart(): Unit = {

    val lines =
      """
        |fun main() {
        |  putuart(65)
        |  putuart('B')
        |  short c=67;
        |  putuart(c)
        |  putuart(c+1)
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = str => {
      checkTransmittedL('c', str, List("A", "B", "C", "D"))
    })
  }

  @Test
  def putfuart(): Unit = {

    val lines =
      """
        |fun main() {
        |  putfuart(X, 1)
        |  putfuart(C, 2)
        |  putfuart(B, 3)
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = str => {
      checkTransmittedL('d', str, List("6", "1", "5", "2", "19", "3"))
    })
  }

  @Test
  def waituart(): Unit = {

    val lines =
      """
        |fun main() {
        |  short g = waituart();
        |  putuart(g)
        |}
        |""".stripMargin

    compile(lines, verbose = true, timeout = 1000, dataIn = List("t1", "rA"), outputCheck = {
      str =>
        checkTransmittedChar(str, 'A')
    })
  }

  @Test
  def writePortLoopback(): Unit = {

    val lines =
      """
        |fun main() {
        |  writeport(Parallel, 123) // this code assumes that the parallel out is looped back to parallel in
        |  short g = readport(Parallel);
        |  halt(g, 0)
        |}
        |""".stripMargin

    // assert the correct value was read
    compile(lines, verbose = true, checkHalt = Some(HaltCode(123, 0)), timeout = 1000)
  }

  @Test
  def readPortGamepad(): Unit = {

    // set the game controller value to 123
    val pw = new PrintWriter(new File(gamepadControl))
    pw.println("/ setting controller to 1")
    pw.println("c1=" + 123.toHexString)
    pw.flush()

    // read the controller via the sim
    val lines =
      """
        |fun main() {
        |  short g = readport(Gamepad1);
        |  halt(g, 0)
        |}
        |""".stripMargin

    // assert the correct value was read
    compile(lines, verbose = true, checkHalt = Some(HaltCode(123, 0)), timeout = 1000)
  }

  @Test
  def readRandom(): Unit = {

    // set the game controller value to 123
    val pw = new PrintWriter(new File(gamepadControl))
    pw.println("/ forcing random value to a fixed value for testing")
    pw.println("r=" + 123.toHexString)
    pw.flush()

    // read the controller via the sim
    val lines =
      """
        |fun main() {
        |  short g = random();
        |  halt(g, 0)
        |}
        |""".stripMargin

    // assert the correct value was read
    compile(lines, verbose = true, checkHalt = Some(HaltCode(123, 0)), timeout = 1000)
  }

  @Test
  def valEqVarLogical(): Unit = {
    val lines =
      """
        |fun main() {
        | short b=1024;
        |
        | short a1 = b>1;
        | putuart(a1) // true
        |
        | short a2= b==0;
        | putuart(a2) // false
        |
        | short a3= b==1;
        | putuart(a3) // false
        |
        | short a4= b==1024;
        | putuart(a4) // true
        |
        | // compare b==(a+24)
        | short a5=1000;
        | short a6= b==(a5+24);
        | putuart(a6) // true
        |
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('h', str, List("01", "00", "00", "01", "01"))
    })
  }

  @Test
  def valEqVarAdd(): Unit = {

    val lines =
      """
        |fun main() {
        | short a='a';
        | short c = a + 2;
        | putuart(c)
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('c', str, List("c"))
    })
  }

  /*
  @Test
  def cpp(): Unit = {

    val lines =
      """
        |#define MACROA 'a'
        |fun main() {
        | putuart(MACROA)
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('c', str, List("a"))
    })
  }
   */

  @Test
  def valEqVarMinus(): Unit = {

    val lines =
      """
        |fun main() {
        | short c='c';
        | short a = c - 2;
        | putuart(a)
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('c', str, List("a"))
    })
  }

  @Test
  def valEqVarTimesConst(): Unit = {

    val lines =
      """
        |fun main() {
        | short s = 503;
        | short a = s * 2; // alu expr
        | putuart(a>>8)
        | putuart(a)
        |}
        |""".stripMargin

    val expected = 503 * 2
    val upper = expected >> 8
    val lower = expected & 0xff

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('d', str, List(upper.toString, lower.toString))
    })
  }

  @Test
  def valEqVarTimesVar(): Unit = {

    val lines =
      """
        |fun main() {
        | short x = 503;
        | short y = 2;
        | short a = x * y; // alu expr
        | putuart(a>>8)
        | putuart(a)
        |}
        |""".stripMargin

    val expected = 503 * 2
    val upper = expected >> 8
    val lower = expected & 0xff

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('d', str, List(upper.toString, lower.toString))
    })
  }

  @Test // BROKEN
  def valEqGlobalVarBROKEN(): Unit = {

    val lines =
      """
        |short g = $1234;
        |// FIXME - "g" never gets initialised as the initialisation code sits between the top of the program and the beginning of main and it jumps straight to main.
        |// WORKAROUND = can initialise in main() if it's a primitive value
        |
        |fun fun3() {
        |   putuart(g>>8)
        |   putuart(g)
        |
        |   var screen = [6: [1 2]]; // TODO - this array is effectively in global storage as the contents persist between calls and are not reinitialised
        | }
        |fun fun2() {
        |   putuart(g>>8)
        |   putuart(g)
        |   g = g + $101;
        |   fun3()
        | }
        |fun main() {
        |   short gg=-99;
        |   halt(gg, 99)
        |   putuart(g>>8)
        |   putuart(g)
        |   g = g + $101;
        |   fun2()
        | }
        |}
        |""".stripMargin

    compile(lines, verbose = true, stripComments = false, outputCheck = {
      str =>
        checkTransmittedL('d', str, List("12", "34", "13", "35", "14", "36"))
    })
  }

  @Test
  def valEqVarDivide(): Unit = {

    // IMPLEMENTED BUT NOT WORKING
    val lines =
      """
        |fun main() {
        | short a=8; // const expr
        |
        | short res = a / 3; // alu expr
        | putuart(res)
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        val expected = "2"
        checkTransmittedL('d', str, List(expected))
    })
  }

  @Test
  def multipleDivs(): Unit = {

    val lines =
      s"""fun main() {
         |
         | short a1 = 1;
         | short a2 = a1 / 10;
         | short a3 = a1 / 10;
         |
         | short x = 123;
         |
         | short i100 = x / 100;
         | short xDiv10 = x / 10;
         | short i10 = xDiv10 - (10 * i100);
         | short i1 = x - (10*xDiv10);
         |
         | short bcd = (i100 * 100) + (i10 * 10) + i1;
         | putuart(i100)
         | putuart(i10)
         | putuart(i1)
         |}
         |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('d', str, List("1", "2", "3"))
    })

  }

  @Test
  def valEqConstLogical(): Unit = {

    val lines =
      """
        |fun main() {
        | short a=1>0;
        | putuart(a)
        |
        | a=0>1;
        | putuart(a)
        |
        | a=0==1;
        | putuart(a)
        |
        | a=1==1;
        | putuart(a)
        |
        | a=%1010 & %1100;
        | putuart(a)

        | a=%1010 | %1100;
        | putuart(a)
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      str =>
        checkTransmittedL('h', str, List("01", "00", "00", "01", "08", "0e"))
    })
  }

  @Test
  def whileLoopCondVarVsConst(): Unit = {

    val lines =
      """
        |fun main() {
        | short a=1260;
        | while(a>1250) {
        |   a=a-1;
        |   putuart(a-1250)
        | }
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      lines =>
        //        checkTransmittedL('h', lines, List("01", "00", "00", "01", "08", "0e"))
        checkTransmittedDecs(lines, List(9, 8, 7, 6, 5, 4, 3, 2, 1, 0))
    })
  }


  @Test
  def whileLoopCondVarVsVar(): Unit = {

    val lines =
      """
        |fun main() {
        | short a=3;
        | short zero=0;
        | while(a>zero) {
        |   putuart(a)
        |   a=a-1;
        | }
        |}
        |""".stripMargin

    compile(lines, verbose = true, outputCheck = {
      lines =>
        //        checkTransmittedL('h', lines, List("01", "00", "00", "01", "08", "0e"))
        checkTransmittedDecs(lines, List(3, 2, 1))
    })
  }

  @Test
  def variablesAtParentScope(): Unit = {

    val lines =
      """
        |fun main() {
        | short a = 1;
        |
        | // block
        | {
        |   short b = a;
        |   putuart(b)
        | }
        |
        | // variable at same scope as prev block
        | {
        |   short b = a + 1;
        |   putuart(b)
        | }
        |
        | // if referring to outer var
        | {
        |   if (a > 0) {
        |     short c = a + 2;
        |     putuart(c)
        |   }
        | }
        |
        | // labelled if
        | {
        |   labelFoo: if (a > 0) {
        |     labelBar: short d = a + 3;
        |     short e = d;
        |     putuart(e)
        |   }
        | }
        |}
        |""".stripMargin

    compile(lines, outputCheck = {
      lines =>
        checkTransmittedDecs(lines, List(1, 2, 3, 4))
    })
  }


  @Test
  def whileLoopTrueIfBreak(): Unit = {

    val lines =
      """
        |fun main() {
        | short a = 0;
        | short b = 0;
        | short limit = 5;
        | labelFoo: while(true) {
        |   while(true) {
        |     labelBar: a = a + 1;
        |
        |     putuart(a)
        |     if (a == limit) {
        |       break
        |     }
        |   }
        |   b = b + 1;
        |   limit = limit + 5;
        |   if (b == 2) break
        | }
        |
        | while(a < 100) {
        |   a = a - 1;
        |
        |   if (a==0) {
        |     break
        |   }
        |   putuart(a)
        |
        | }
        |}
        |""".stripMargin

    compile(lines, outputCheck = {
      lines =>
        checkTransmittedDecs(lines, List(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1))
    })
  }

  @Test
  def functionCalls(): Unit = {

    val lines =
      """
        |// START FN COMMAND
        |
        |fun print(a1 out, a2, a3, a4) {
        | // FN COMMENT
        | short d = a1;
        | //d = a2;
        | putuart(d)
        | putuart(a2)
        | putuart(a3)
        | putuart(a4)
        |
        | // ascii 33 dec
        | a1 = '!';
        | // END FN COMMENT
        |}
        |
        |fun main() {
        | short arg1 = 'A';
        | short arg2 = 1;
        |
        | // CALLING PRINT - 63 is '?'
        | print(arg1, arg2+arg1, 63, arg1+4)
        |
        | // CALLING PUT CHAR OF OUT VALUE
        | putuart(arg1)
        |
        |}
        |
        |// END  COMMAND
        |""".stripMargin

    compile(lines, verbose = true, stripComments = true, outputCheck = str => {
      checkTransmittedChar(str, 'A')
      checkTransmittedChar(str, 'B')
      checkTransmittedChar(str, '?')
      checkTransmittedChar(str, 'E')
      checkTransmittedChar(str, '!')
    })

  }

  @Test
  def functionCalls2Deep(): Unit = {

    val lines =
      """
        |fun depth2(b1 out) {
        | b1 = b1 + 1;
        |}
        |
        |fun depth1(a1 out) {
        | depth2(a1)
        |}
        |
        |fun main() {
        | short arg1 = 'A';
        | depth1(arg1)
        | putuart(arg1)
        |}
        |
        |// END  COMMAND
        |""".stripMargin

    compile(lines, stripComments = true, outputCheck = {
      lines =>
        checkTransmittedChars(lines, List("B"))
    })
  }

  @Test
  def blockComment(): Unit = {

    // TODO USE "export" to allow sharing a var into static subroutines in call tree (not sure if will work for stack based calls)
    val lines =
      """
        |/* top comment */
        |//  export short s = 'A';
        |
        |fun main() {
        | //putuart(a)
        | /*
        | commented
        | out
        | */
        |}
        |""".stripMargin

    val actual = compile(lines, timeout = 50, stripComments = true).map(x => x.replaceAll("^\\s*", ""))

    val expected = split(
      """B2___VAR_RETURN_HI: EQU   0
        |B2___VAR_RETURN_HI: BYTES [0]
        |B2___VAR_RETURN_LO: EQU   1
        |B2___VAR_RETURN_LO: BYTES [0]
        |PCHITMP = < :ROOT________main_start
        |PC = > :ROOT________main_start
        |ROOT________main_start:
        |B2___LABEL_START:
        |PCHITMP = <:root_end
        |PC = >:root_end
        |root_end:
        |MARHI=255
        |MARLO=255
        |HALT=255
        |END""".stripMargin)

    assertSame(expected, actual)
  }

  @Test
  def referencesNOTIMPL(): Unit = {

    val lines =
      """
        |fun main() {
        |
        | // define string
        | var even = ["Even\0"];
        | var odd = ["Odd\0"];
        |
        | // value at 16 bit var ptr becomes address of array odd
        | ref ptr = odd;
        |
        | short i = 10;
        | while (i>0) {
        |    System.out.pri
        |        |   i = i - 1;
        |   short c = i % 2;
        |   if (c == 0) {
        |       // set pointer to point at even
        |       ptr = even;
        |   }
        |   if (c != 0) {
        |      ptr = odd;
        |   }
        |   puts(ptr)
        | }
        |}
        |""".stripMargin

    compile(lines, verbose = true, stripComments = true, outputCheck = str => {
      val value: List[String] = "OddEvenOddEvenOddEvenOddEvenOddEven".toList.map(_.toString)
      checkTransmittedL('c', str, value)
    })
  }

  @Test
  def referenceLongNOTIMPL(): Unit = {

    val data = (0 to 255) map { x =>
      f"$x%02x"
    } mkString ("")

    val lines =
      s"""
         |fun main() {
         |
         | // define string
         | var string = ["$data\\0"];
         |
         | // value at 16 bit var ptr becomes address of array odd
         | short addr16 = string;
         | short idx = 0;
         | short c = string[idx];
         | ref ptr = string;
         |
         | short i = 255;
         | while (i>0) {
         |   short lo = ptr;
         |   ptr  = ptr + 1;
         |   short hi = ptr;
         |   ptr  = ptr + 1;
         |
         |   i = i - 1;
         | }
         |}
         |""".stripMargin

    compile(lines, verbose = true, stripComments = true, outputCheck = str => {
      val value: List[String] = "OddEvenOddEvenOddEvenOddEvenOddEven".toList.map(_.toString)
      checkTransmittedL('c', str, value)
    })
  }

  @Test
  def stringIndexingRead(): Unit = {

    val lines =
      """
        |fun main() {
        | // define string
        | var string = ["ABCD\0"];
        |
        | // index by literal
        | short ac = string[0];
        |
        | // index by variable
        | short b = 1;
        | short bc = string[b];
        |
        | // print values so we can test correct values selected
        | short d = 3;
        | putuart(ac)
        | putuart(bc)
        | putuart(string[2])
        | putuart(string[d])
        |}
        |""".stripMargin

    compile(lines, verbose = false, stripComments = true, outputCheck = str => {
      checkTransmittedChar(str, 'A')
      checkTransmittedChar(str, 'B')
      checkTransmittedChar(str, 'C')
      checkTransmittedChar(str, 'D')
    })
  }

  @Test
  def stringIndexingWrite(): Unit = {

    val lines =
      """
        |fun main() {
        | var string = ["ABCD\0"];
        | string[1] = '!';
        |
        | // define string
        | putuart(string[0])
        | putuart(string[1])
        | putuart(string[2])
        |
        | // this expression creates further temp vars in addition to those created on string[1] assign above - code gen is expected to generate unique names (INDEX_LO/HI)
        | string[1] = string[1] + 2;
        | putuart(string[1])
        |}
        |""".stripMargin

    compile(lines, verbose = true, stripComments = true, outputCheck = str => {
      checkTransmittedChars(str, Seq("A", "!", "C", "#"))
    })
  }

  @Test
  def stringIteration(): Unit = {

    val lines =
      """
        |fun main() {
        | // define string
        | var string = ["ABCD\0"];
        |
        | short idx = 0;
        | short c = string[idx];
        | while (c != 0) {
        |   putuart(c)
        |   idx = idx + 1;
        |   c = string[idx];
        | }
        |}
        |""".stripMargin

    compile(lines, verbose = true, stripComments = true, outputCheck = str => {
      checkTransmittedChar(str, 'A')
      checkTransmittedChar(str, 'B')
      checkTransmittedChar(str, 'C')
      checkTransmittedChar(str, 'D')
    })
  }

  @Test
  def puts(): Unit = {

    val lines =
      """
        |fun main() {
        | // define string
        | var string = [ "ABCD\0" ];
        | puts(string)
        |}
        |""".stripMargin

    compile(lines, verbose = true, stripComments = true, outputCheck = str => {
      checkTransmittedChar(str, 'A')
      checkTransmittedChar(str, 'B')
      checkTransmittedChar(str, 'C')
      checkTransmittedChar(str, 'D')
    })
  }

  @Nested
  class Multiply {
    @Test
    def negMathsXPosPosMult(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = 2*256+128; // 2.5
          | short b = 3;
          | short c = a * b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(0x0780, 0x80)), stripComments = true)
    }

    @Test
    def negMathsYPosPosMult(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = 2*256+128; // 2.5
          | short b = 2*256+128; // 2.5
          | short c = a * b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(0x0640, 0x40)), stripComments = true)
    }

    @Test
    def negMathsPosPosMult(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = 2;
          | short b = 3;
          | short c = a * b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(6, 6)), stripComments = true)
    }

    @Test
    def negMathsNegPosMult(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = -2;
          | short b = 3;
          | short c = a * b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(0xfffa, 0xfa)), stripComments = true)
    }


    @Test
    def negMathsPosNegMult(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = 2;
          | short b = -3;
          | short c = a * b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(-6, -6)), stripComments = true)
    }

    @Test
    def negMathsNegNegMult(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = -2;
          | short b = -3;
          | short c = a * b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(6, 6)), stripComments = true)
    }
  }

  @Nested
  class Division {

    @Test
    def negMathsLongPosPosDiv(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = 2570; // $0a0a
          | short b = 1025; // $401
          | short c = a / b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(2, 2)), stripComments = true)
    }

    def negMathsLongPosNegDiv(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = 2570; // $0a0a
          | short b = -1025; // $401
          | short c = a / b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(-2, -2)), stripComments = true)
    }

    def negMathsLongNegPosDiv(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = -2570; // $0a0a
          | short b = 1025; // $401
          | short c = a / b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(-2, -2)), stripComments = true)
    }

    @Test
    def negMathsLongNegNegDiv(): Unit = {

      val lines =
        """
          |fun main() {
          | short a = -2570; // $0a0a
          | short b = -1025; // $401
          | short c = a / b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(2, 2)), stripComments = true)
    }


    @Test
    def negMathsSmallPosPosDiv(): Unit = {

      // can got via fast all positive 8 bit route
      val lines =
        """
          |fun main() {
          | short a = 8;
          | short b = 2;
          | short c = a / b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(4, 4)), stripComments = true)
    }

    @Test
    def negMathsSmallNegNegDiv(): Unit = {

      // since upper byte is !=0 (cos two compl) then will go via slow route
      val lines =
        """
          |fun main() {
          | short a = -8;
          | short b = -2;
          | short c = a / b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(4, 4)), stripComments = true)
    }

    @Test
    def negMathsSmallNegPosDiv(): Unit = {

      // since upper byte is !=0 (cos two compl) then will go via slow route
      val lines =
        """
          |fun main() {
          | short a = -8;
          | short b = 2;
          | short c = a / b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(-4, -4)), stripComments = true)
    }

    @Test
    def negMathSmallPosNegDiv(): Unit = {

      // since upper byte is !=0 (cos two compl) then will go via slow route
      val lines =
        """
          |fun main() {
          | short a = 8;
          | short b = -2;
          | short c = a / b;
          | halt(c, c)
          |}
          |""".stripMargin

      compile(lines, timeout = 50, checkHalt = Some(HaltCode(-4, -4)), stripComments = true)
    }
  }

  @Test
  def halt(): Unit = {

    val lines =
      """
        |fun main() {
        | halt(65432, 123)
        |}
        |""".stripMargin

    compile(lines, timeout = 50, checkHalt = Some(HaltCode(65432, 123)), stripComments = true)
  }

  @Test
  def haltVar(): Unit = {

    val lines =
      """
        |fun main() {
        | short a = 65432;
        | halt(a, 123)
        |}
        |""".stripMargin

    //    expectHalt(() => compile(lines, timeout = 50, quiet = true), MAR = 65432, CODE = 123)
    compile(lines, timeout = 50, checkHalt = Some(HaltCode(65432, 123)), stripComments = true)
  }
}
