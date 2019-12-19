package io.casperlabs.casper.highway

import org.scalatest._

class LeaderSequencerSpec extends WordSpec with Matchers {
  "toByteArray" should {
    "concatenate bits to a byte array, padding on the right" in {
      val bits =
        List(true, false, false, true, true, false, false, false) ++
          List(false, true, true, false, true)

      val bytes = LeaderSequencer.toByteArray(bits)

      bytes should have size 2

      // If we just called .toInt on the bytes Java would interpret them as signed
      // think it's -104 instead of 152.
      def check(i: Int, b: String) =
        java.lang.Byte.toUnsignedInt(bytes(i)) shouldBe Integer.parseInt(b, 2)

      check(0, "10011000")
      check(1, "01101000")
    }
  }
}
