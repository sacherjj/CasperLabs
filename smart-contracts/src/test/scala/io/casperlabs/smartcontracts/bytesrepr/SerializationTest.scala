package io.casperlabs.smartcontracts.bytesrepr

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks

class SerializationTest extends FlatSpec with Matchers with PropertyChecks {

  "Booleans" should "serialize properly" in forAll { (b: Boolean) =>
    roundTrip(b)
  }

  "Integers" should "serialize properly" in forAll { (i: Int) =>
    roundTrip(i)
  }

  "Long integers" should "serialize properly" in forAll { (i: Long) =>
    roundTrip(i)
  }

  "BigInt" should "serialize properly" in forAll { (i: BigInt) =>
    whenever(i > 0) { roundTrip(i) }
  }

  "Unit" should "serialize properly" in {
    roundTrip(())
  }

  "Strings" should "serialize properly" in forAll { (s: String) =>
    roundTrip(s)
  }

  "Optional values" should "serialize properly" in forAll { (o: Option[Int]) =>
    roundTrip(o)
  }

  "Lists of values" should "serialize properly" in forAll { (s: Seq[Option[Long]]) =>
    roundTrip(s)
  }

  "Either values" should "serialize properly" in forAll { (e: Either[Byte, Option[String]]) =>
    roundTrip(e)
  }

  "Maps of values" should "serialize properly" in forAll { (m: Map[String, Int]) =>
    roundTrip(m)
  }

  "Single element tuples" should "serialize properly" in forAll { t: Tuple1[Int] =>
    roundTrip(t)
  }

  "Pairs of values" should "serialize properly" in forAll { (p: (Long, List[Byte])) =>
    roundTrip[(Long, Seq[Byte])](p)
  }

  "Triples of values" should "serialize properly" in forAll { (t: (Int, String, Long)) =>
    roundTrip(t)
  }

  private def roundTrip[T: ToBytes: FromBytes](t: T) =
    FromBytes.deserialize(ToBytes[T].toBytes(t)) shouldBe Right(t)
}
