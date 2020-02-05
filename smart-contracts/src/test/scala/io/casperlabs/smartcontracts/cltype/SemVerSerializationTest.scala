package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import SemVerSerializationTest.arbSemVer

class SemVerSerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "SemVers" should "serialize properly" in forAll { (v: SemVer) =>
    roundTrip(v)
  }
}

object SemVerSerializationTest {
  val genSemVer: Gen[SemVer] = for {
    major <- Gen.choose(1, 10)
    minor <- Gen.choose(0, 20)
    patch <- Gen.choose(0, 1000)
  } yield SemVer(major, minor, patch)

  implicit val arbSemVer: Arbitrary[SemVer] = Arbitrary(genSemVer)
}
