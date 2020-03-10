package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import ByteArray32SerializationTest.arbByteArray32

class ByteArray32SerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "ByteArray32" should "serialize properly" in forAll { (b: ByteArray32) =>
    roundTrip(b, ByteArray32.deserializer)
  }
}

object ByteArray32SerializationTest {
  val genByteArray32: Gen[ByteArray32] =
    Gen
      .listOfN[Byte](32, Gen.choose[Byte](-128, 127))
      .map(bytes => ByteArray32(bytes.toIndexedSeq).get)

  implicit val arbByteArray32: Arbitrary[ByteArray32] = Arbitrary(genByteArray32)
}
