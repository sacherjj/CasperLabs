package io.casperlabs.casper.util.execengine

import org.scalacheck.{Arbitrary, Gen}

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks

import Op.OpMap

class OpSpec extends FlatSpec with Matchers with PropertyChecks {

  implicit val arbOp: Arbitrary[Op] = Arbitrary(OpSpec.Gens.op)

  "Op addition" should "be commutative" in {
    forAll { (a: Op, b: Op) =>
      (a + b) shouldEqual (b + a)
    }
  }

  it should "have NoOp as an identity element" in {
    forAll { (a: Op) =>
      (a + Op.NoOp) shouldEqual a
    }
  }

  "Op commute relation" should "distribute over addition" in {
    // a ~ (b + c) ==> (a ~ b) && (a ~ c)
    forAll { (a: Op, b: Op, c: Op) =>
      whenever(a ~ (b + c)) { ((a ~ b) && (a ~ c)) shouldBe true }
    }

    // (a ~ b) && (a ~ c) ==> a ~ (b + c)
    forAll { (a: Op, b: Op, c: Op) =>
      whenever((a ~ b) && (a ~ c)) { (a ~ (b + c)) shouldBe true }
    }
  }

  it should "be transitive when applied to non-trivial Ops" in {
    // override outer scope implicit with one that excludes Op.NoOp
    implicit val arbOp: Arbitrary[Op] = Arbitrary(OpSpec.Gens.nonTrivialOp)

    forAll { (a: Op, b: Op, c: Op) =>
      // Can't use `whenever` here because the test gives up
      // due to the low density of cases with a successful premise.
      if ((a ~ b) && (b ~ c)) {
        (a ~ c) shouldBe true
      } else {
        true shouldBe true
      }
    }
  }

  "OpMap addition" should "be commutative" in {
    forAll { (a: OpMap[Int], b: OpMap[Int]) =>
      (a + b) shouldEqual (b + a)
    }
  }

  it should "retain all the keys of both maps" in {
    forAll { (a: OpMap[Int], b: OpMap[Int]) =>
      (a + b).keySet shouldEqual (a.keySet union b.keySet)
    }
  }

  it should "sum the Ops of duplicate keys" in {
    forAll { (a: OpMap[Int], b: OpMap[Int]) =>
      val c = a + b
      for (k <- (a.keySet intersect b.keySet)) {
        c(k) shouldEqual (a(k) + b(k))
      }
    }
  }

  "OpMap commute relation" should "always commute disjoint maps" in {
    val smallKeys    = OpSpec.Gens.opMap(Gen.choose[Int](0, 20))
    val largeKeys    = OpSpec.Gens.opMap(Gen.choose[Int](100, 120))
    val disjointMaps = Gen.zip(smallKeys, largeKeys)
    forAll(disjointMaps) {
      case (a: OpMap[Int], b: OpMap[Int]) =>
        a ~ b shouldBe true
    }
  }

  it should "commute maps if and only if all common keys commute" in {
    forAll { (a: OpMap[Int], b: OpMap[Int]) =>
      (a ~ b) shouldEqual (a.keySet intersect b.keySet).forall(k => a(k) ~ b(k))
    }
  }
}

object OpSpec {
  object Gens {
    val noop  = Gen.const(Op.NoOp)
    val write = Gen.const(Op.Write)
    val read  = Gen.const(Op.Read)
    val add   = Gen.const(Op.Add)

    val op           = Gen.oneOf(noop, write, read, add)
    val nonTrivialOp = Gen.oneOf(write, read, add)

    def opMap[Key](keyGen: Gen[Key]): Gen[OpMap[Key]] = Gen.mapOf(Gen.zip(keyGen, op))
  }
}
