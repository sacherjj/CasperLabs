package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import AccessRightsSerializationTest.arbAccessRights

class AccessRightsSerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "AccessRights" should "serialize properly" in forAll { (a: AccessRights) =>
    roundTrip(a, AccessRights.deserializer)
  }
}

object AccessRightsSerializationTest {
  val genAccessRights: Gen[AccessRights] = Gen.choose[Byte](0, 7).map {
    case 0 => AccessRights.None
    case 1 => AccessRights.Read
    case 2 => AccessRights.Write
    case 3 => AccessRights.ReadWrite
    case 4 => AccessRights.Add
    case 5 => AccessRights.ReadAdd
    case 6 => AccessRights.AddWrite
    case 7 => AccessRights.ReadAddWrite
  }

  implicit val arbAccessRights: Arbitrary[AccessRights] = Arbitrary(genAccessRights)
}
