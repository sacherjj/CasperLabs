package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.SerializationTest.roundTrip
import io.casperlabs.models.bytesrepr.{FromBytes, ToBytes}
import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import CLTypeSerializationTest.{arbCLType, bytesRoundTrip, nested}

class CLTypeSerializationTest extends FlatSpec with PropertyChecks {
  "CLTypes" should "serialize properly" in forAll { (t: CLType) =>
    roundTrip(t, CLType.deserializer)
  }

  it should "serialize in a stack safe way" in {
    val deepType      = nested(CLType.Unit, 500000)(t => CLType.List(t))
    val deepFixedList = CLType.FixedList(deepType, 17)
    val deeperType    = nested(deepFixedList, 500000)(t => CLType.Option(t))

    // We can't compare the actual type because it turns out the
    // auto-generated equality on case classes is not stack safe.
    bytesRoundTrip(deeperType, CLType.deserializer)

    val wideType      = nested(CLType.String, 5)(t => CLType.Tuple2(t, t))
    val wideFixedList = CLType.FixedList(wideType, 31)
    val widerType     = nested(wideFixedList, 5)(t => CLType.Tuple3(t, t, t))

    bytesRoundTrip(widerType, CLType.deserializer)
  }
}

object CLTypeSerializationTest extends Matchers {

  def genCLType: Gen[CLType] = Gen.choose(0, 21).flatMap {
    case 0  => Gen.const(CLType.Bool)
    case 1  => Gen.const(CLType.I32)
    case 2  => Gen.const(CLType.I64)
    case 3  => Gen.const(CLType.U8)
    case 4  => Gen.const(CLType.U32)
    case 5  => Gen.const(CLType.U64)
    case 6  => Gen.const(CLType.U128)
    case 7  => Gen.const(CLType.U256)
    case 8  => Gen.const(CLType.U512)
    case 9  => Gen.const(CLType.Unit)
    case 10 => Gen.const(CLType.String)
    case 11 => Gen.const(CLType.Key)
    case 12 => Gen.const(CLType.URef)

    case 13 => genCLType.map(inner => CLType.Option(inner))
    case 14 => genCLType.map(inner => CLType.List(inner))

    case 15 =>
      for {
        inner <- genCLType
        n     <- Gen.choose(1, 100)
      } yield CLType.FixedList(inner, n)

    case 16 =>
      for {
        ok  <- genCLType
        err <- genCLType
      } yield CLType.Result(ok, err)

    case 17 =>
      for {
        key   <- genCLType
        value <- genCLType
      } yield CLType.Map(key, value)

    case 18 => genCLType.map(inner => CLType.Tuple1(inner))

    case 19 =>
      for {
        t1 <- genCLType
        t2 <- genCLType
      } yield CLType.Tuple2(t1, t2)

    case 20 =>
      for {
        t1 <- genCLType
        t2 <- genCLType
        t3 <- genCLType
      } yield CLType.Tuple3(t1, t2, t3)

    case 21 => Gen.const(CLType.Any)

    // this should never happen since we generate from 0 to 21
    case _ => Gen.fail
  }

  implicit val arbCLType: Arbitrary[CLType] = Arbitrary(genCLType)

  def nested(base: CLType, n: Int)(nest: CLType => CLType): CLType = (1 to n).foldLeft(base) {
    case (acc, _) => nest(acc)
  }

  def bytesRoundTrip[T: ToBytes](t: T, des: FromBytes.Deserializer[T]) = {
    val bytes = ToBytes[T].toBytes(t)

    val bytes2 = FromBytes.deserialize(des, bytes).map(value => ToBytes[T].toBytes(value))

    bytes2.map(_.toSeq) shouldBe Right(bytes.toSeq)
  }
}
