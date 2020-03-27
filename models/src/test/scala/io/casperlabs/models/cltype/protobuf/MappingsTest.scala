package io.casperlabs.models.cltype.protobuf

import org.scalacheck.{Arbitrary, Gen}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import io.casperlabs.models.cltype.{CLType, CLValueInstance}
import io.casperlabs.models.cltype.CLTypeSerializationTest.arbCLType
import io.casperlabs.models.cltype.CLValueInstanceTest.arbCLInstance

class MappingsTest extends FlatSpec with Matchers with PropertyChecks {
  "CLType to/fromProto" should "work" in forAll { (t: CLType) =>
    val p = Mappings.toProto(t)
    val q = Mappings.fromProto(p)

    Right(t) shouldBe q
  }

  it should "be stack safe (deep nesting)" in {
    val deepType = (1 to 1000000).foldLeft[CLType](CLType.Unit) {
      case (acc, _) => CLType.Option(acc)
    }
    val deepProto = Mappings.toProto(deepType)
    val _         = Mappings.fromProto(deepProto)
  }

  it should "be stack safe (wide nesting)" in {
    val wideType = (1 to 20).foldLeft[CLType](CLType.Unit) {
      case (acc, _) => CLType.Tuple2(acc, acc)
    }
    val wideProto = Mappings.toProto(wideType)
    val _         = Mappings.fromProto(wideProto)
  }

  "CLValueInstance to/fromProto" should "work" in forAll { (v: CLValueInstance) =>
    val p = Mappings.toProto(v)
    val q = Mappings.fromProto(p)

    Right(v) shouldBe q
  }

  it should "be stack safe (deep nesting)" in {
    val deepType = (1 to 1000000).foldLeft[CLValueInstance](CLValueInstance.Unit) {
      case (acc, _) => CLValueInstance.Option(Some(acc), acc.clType).right.get
    }
    val deepProto = Mappings.toProto(deepType)
    val _         = Mappings.fromProto(deepProto)
  }

  it should "be stack safe (wide nesting)" in {
    val wideType = (1 to 20).foldLeft[CLValueInstance](CLValueInstance.Unit) {
      case (acc, _) => CLValueInstance.Tuple2(acc, acc)
    }
    val wideProto = Mappings.toProto(wideType)
    val _         = Mappings.fromProto(wideProto)
  }

  "CLValueInstance.List(U8) and CLValueInstance.FixedList(U8)" should "convert into bytes" in {
    val bytes          = Array.range(0, 32).map(_.toByte)
    val bytesInstances = bytes.map(CLValueInstance.U8.apply)
    val list           = CLValueInstance.List(bytesInstances, CLType.U8).right.get
    val fixedList      = CLValueInstance.FixedList(bytesInstances, CLType.U8, bytes.length).right.get

    Mappings.toProto(list) shouldBe dsl.instances.bytes(bytes)
    Mappings.toProto(fixedList) shouldBe dsl.instances.bytesFixedLength(bytes)
  }
}
