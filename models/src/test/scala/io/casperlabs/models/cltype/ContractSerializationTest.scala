package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import ContractSerializationTest.arbContract

class ContractSerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "Contracts" should "serialize properly" in forAll { (c: Contract) =>
    roundTrip(c, Contract.deserializer)
  }
}

object ContractSerializationTest {
  val genContract: Gen[Contract] = for {
    bytes <- Gen.listOf(Gen.choose[Byte](-128, 127))
    namedKeys <- Gen.mapOf(
                  Gen.alphaStr.flatMap(s => KeySerializationTest.genKey.map(k => s -> k))
                )
    version <- SemVerSerializationTest.genSemVer
  } yield Contract(bytes.toIndexedSeq, namedKeys, version)

  implicit val arbContract: Arbitrary[Contract] = Arbitrary(genContract)
}
