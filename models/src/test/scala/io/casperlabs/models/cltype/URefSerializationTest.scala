package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import URefSerializationTest.arbURef

class URefSerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "URef" should "serialize properly" in forAll { (u: URef) =>
    roundTrip(u, URef.deserializer)
  }
}

object URefSerializationTest {
  val genURef: Gen[URef] = for {
    addr   <- ByteArray32SerializationTest.genByteArray32
    access <- AccessRightsSerializationTest.genAccessRights
  } yield URef(addr, access)

  implicit val arbURef: Arbitrary[URef] = Arbitrary(genURef)
}
