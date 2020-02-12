package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import CLValueSerializationTest.arbCLValue

class CLValueSerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "CLValues" should "serialize properly" in forAll { (v: CLValue) =>
    roundTrip(v)
  }
}

object CLValueSerializationTest {
  val genCLValue: Gen[CLValue] = for {
    clType <- CLTypeSerializationTest.genCLType
    bytes  <- Gen.listOf(Gen.choose[Byte](-128, 127))
  } yield CLValue(clType, bytes.toIndexedSeq)

  implicit val arbCLValue: Arbitrary[CLValue] = Arbitrary(genCLValue)
}
