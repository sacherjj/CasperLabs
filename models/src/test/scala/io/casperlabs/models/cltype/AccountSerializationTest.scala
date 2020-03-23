package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.SerializationTest.roundTrip
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import AccountSerializationTest.arbAccount

class AccountSerializationTest extends FlatSpec with Matchers with PropertyChecks {
  "Accounts" should "serialize properly" in forAll { (a: Account) =>
    roundTrip(a, Account.deserializer)
  }
}

object AccountSerializationTest {
  private val genWeight = Gen.choose[Byte](-128, 127)

  val genAccount: Gen[Account] = for {
    publicKey <- ByteArray32SerializationTest.genByteArray32
    namedKeys <- Gen.mapOf(
                  Gen.alphaStr.flatMap(s => KeySerializationTest.genKey.map(k => s -> k))
                )
    mainPurse <- URefSerializationTest.genURef
    associatedKeys <- Gen.mapOf(
                       ByteArray32SerializationTest.genByteArray32.flatMap(
                         k => genWeight.map(w => k -> w)
                       )
                     )
    actionThresholds <- genWeight.flatMap { d =>
                         genWeight.map(k => Account.ActionThresholds(d, k))
                       }
  } yield Account(publicKey, namedKeys, mainPurse, associatedKeys, actionThresholds)

  implicit val arbAccount: Arbitrary[Account] = Arbitrary(genAccount)
}
