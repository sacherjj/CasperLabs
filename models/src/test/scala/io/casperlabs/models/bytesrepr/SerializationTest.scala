package io.casperlabs.models.bytesrepr

import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.PropertyChecks
import SerializationTest.roundTrip

class SerializationTest extends FlatSpec with Matchers with PropertyChecks {

  "Booleans" should "serialize properly" in forAll { (b: Boolean) =>
    roundTrip(b, FromBytes.bool)
  }

  it should "follow the ABI spec" in {
    val tBytes = ToBytes.toBytes(true).toVector
    val fBytes = ToBytes.toBytes(false).toVector

    tBytes shouldBe Vector(Constants.Boolean.TRUE_TAG)
    fBytes shouldBe Vector(Constants.Boolean.FALSE_TAG)
  }

  "Bytes" should "serialize properly" in forAll { (b: Byte) =>
    roundTrip(b, FromBytes.byte)
  }

  it should "follow the ABI spec" in {
    // bytes serialize as themselves

    val b: Byte = 115
    ToBytes.toBytes(b).toVector shouldBe Vector(b)
  }

  "Integers" should "serialize properly" in forAll { (i: Int) =>
    roundTrip(i, FromBytes.int)
  }

  it should "follow the ABI spec" in {
    val a = 0x00000007
    val b = 0x0000AB13
    val c = 0x12345678

    // bytes should be in little endian order, i.e. from least significant to most significant
    ToBytes.toBytes(a).toVector shouldBe Vector(0x07, 0, 0, 0).map(_.toByte)
    ToBytes.toBytes(b).toVector shouldBe Vector(0x13, 0xAB, 0, 0).map(_.toByte)
    ToBytes.toBytes(c).toVector shouldBe Vector(0x78, 0x56, 0x34, 0x12).map(_.toByte)
  }

  "Long integers" should "serialize properly" in forAll { (i: Long) =>
    roundTrip(i, FromBytes.long)
  }

  it should "follow the ABI spec" in {
    val a = 0x000000000000000AL
    val b = 0xBB00000000000000L
    val c = 0x000000CA50000000L
    val d = 0X123456789ABCDEF0L

    // bytes should be in little endian order, i.e. from least significant to most significant
    ToBytes.toBytes(a).toVector shouldBe Vector(0x0A, 0, 0, 0, 0, 0, 0, 0).map(_.toByte)
    ToBytes.toBytes(b).toVector shouldBe Vector(0, 0, 0, 0, 0, 0, 0, 0xBB).map(_.toByte)
    ToBytes.toBytes(c).toVector shouldBe Vector(0, 0, 0, 0x50, 0xCA, 0, 0, 0).map(_.toByte)
    ToBytes.toBytes(d).toVector shouldBe Vector(0XF0, 0XDE, 0XBC, 0X9A, 0X78, 0X56, 0X34,
      0X12).map(_.toByte)
  }

  "BigInt" should "serialize properly" in forAll { (i: BigInt) =>
    whenever(i > 0) { roundTrip(i, FromBytes.bigInt) }
  }

  it should "follow the ABI spec" in {
    val a = BigInt("987654321", 16)
    val b = BigInt("31415926535897932384626433832795", 16)
    val c = BigInt(0)

    // number of bytes, then little endian representation
    ToBytes.toBytes(a).toVector shouldBe Vector(5, 0x21, 0x43, 0x65, 0x87, 0x09).map(_.toByte)
    ToBytes.toBytes(b).toVector shouldBe Vector(16, 0x95, 0x27, 0x83, 0x33, 0x64, 0x62, 0x84, 0x23,
      0x93, 0x97, 0x58, 0x53, 0x26, 0x59, 0x41, 0x31).map(_.toByte)
    // because we drop leading zeros off the binary representation, 0 is
    // represented as a number with 0 bytes in its little endian representation
    ToBytes.toBytes(c).toVector shouldBe Vector(0)
  }

  "Unit" should "serialize properly" in {
    roundTrip((), FromBytes.unit)
  }

  it should "follow the ABI spec" in {
    // We do not include unit in serialized representation because
    // we don't need any extra information to construct it.
    ToBytes.toBytes(()).toVector shouldBe Vector.empty
  }

  "Strings" should "serialize properly" in forAll { (s: String) =>
    roundTrip(s, FromBytes.string)
  }

  it should "follow the ABI spec" in {
    val nameBytes = Vector(0x43, 0x61, 0x73, 0x70, 0x65, 0x72, 0x4C, 0x61, 0x62, 0x73)
    val name      = nameBytes.map(_.toChar).mkString // == "CasperLabs"

    // strings are serialized as the length followed by the characters encoded in UTF-8
    val lengthBytes = ToBytes.toBytes(name.length).toVector
    lengthBytes.length shouldBe 4 // length is serialized as 4-byte number
    ToBytes.toBytes(name).toVector shouldBe (lengthBytes ++ nameBytes)
  }

  "Optional values" should "serialize properly" in forAll { (o: Option[Int]) =>
    roundTrip(o, FromBytes.option(FromBytes.int))
  }

  it should "follow the ABI spec" in {
    val noneBytes  = ToBytes[Option[Int]].toBytes(None).toVector
    val value: Int = 256
    val someBytes  = ToBytes[Option[Int]].toBytes(Some(value)).toVector

    // None serializes as just a tag; Some serializes as a tag followed by the
    // serialization of the value it contains
    noneBytes shouldBe Vector(Constants.Option.NONE_TAG)
    someBytes shouldBe Constants.Option.SOME_TAG +: ToBytes.toBytes(value).toVector
  }

  "Lists of values" should "serialize properly" in forAll { (s: Seq[Option[Long]]) =>
    roundTrip(s, FromBytes.seq(FromBytes.option(FromBytes.long)))
  }

  it should "follow the ABI spec" in {
    // lists are serialized as the length of the list (as a 4-byte number) followed
    // by the concatenation of the serialization of all the elements

    type T = Option[Long] // just some type to use in the test -- not important

    val emptyList = List.empty[T]
    ToBytes[Seq[T]].toBytes(emptyList).toVector shouldBe Vector(0, 0, 0, 0)

    val elem1: T        = None
    val elem2: T        = Some(1L)
    val elem3: T        = Some(7L)
    val elem4: T        = None
    val elem5: T        = None
    val nonEmptyList    = List(elem1, elem2, elem3, elem4, elem5)
    val listLengthBytes = ToBytes.toBytes(nonEmptyList.size).toVector
    listLengthBytes.length shouldBe 4
    ToBytes[Seq[T]].toBytes(nonEmptyList).toVector shouldBe listLengthBytes ++ Vector(
      elem1,
      elem2,
      elem3,
      elem4,
      elem5
    ).map(ToBytes[T].toBytes).flatten
  }

  "Either values" should "serialize properly" in forAll { (e: Either[Byte, Option[String]]) =>
    roundTrip(e, FromBytes.either(FromBytes.byte, FromBytes.option(FromBytes.string)))
  }

  it should "follow the ABI spec" in {
    // Either values are a tag (left or right) followed by the serialized inner value

    // some types for the test, specifics are not important
    type L = BigInt
    type R = String
    type E = Either[L, R]

    val left: L  = BigInt(123456789)
    val right: R = "Right value"

    val leftBytes  = ToBytes[E].toBytes(Left(left)).toVector
    val rightBytes = ToBytes[E].toBytes(Right(right)).toVector

    leftBytes shouldBe Constants.Either.LEFT_TAG +: ToBytes.toBytes(left)
    rightBytes shouldBe Constants.Either.RIGHT_TAG +: ToBytes.toBytes(right)
  }

  "Maps of values" should "serialize properly" in forAll { (m: Map[String, Int]) =>
    roundTrip(m, FromBytes.map(FromBytes.string, FromBytes.int))
  }

  it should "follow the ABI spec" in {
    // Maps are serialized as the length of the list (as a 4-byte number) followed
    // by the concatenation of the serialization of all the key-value pairs. The pairs
    // must be sorted by the key according to some well-understood ordering for determinism.

    // just some types to use in the test -- not important
    type K = String
    type V = Int

    val emptyMap = Map.empty[K, V]
    ToBytes.toBytes(emptyMap).toVector shouldBe Vector(0, 0, 0, 0)

    val key1: K   = "A"
    val key2: K   = "B"
    val key3: K   = "C"
    val value1: V = 17
    val value2: V = 31
    val value3: V = 7919
    val nonEmptyMap = Map(
      key1 -> value1,
      key2 -> value2,
      key3 -> value3
    )
    val mapLengthBytes = ToBytes.toBytes(nonEmptyMap.size).toVector
    mapLengthBytes.length shouldBe 4
    ToBytes.toBytes(nonEmptyMap).toVector shouldBe mapLengthBytes ++ Vector(
      ToBytes.toBytes(key1),
      ToBytes.toBytes(value1),
      ToBytes.toBytes(key2),
      ToBytes.toBytes(value2),
      ToBytes.toBytes(key3),
      ToBytes.toBytes(value3)
    ).flatten
  }

  "Single element tuples" should "serialize properly" in forAll { t: Tuple1[Int] =>
    roundTrip(t, FromBytes.tuple1(FromBytes.int))
  }

  it should "follow the ABI spec" in {
    // single element tuple serializes the same as the element it contains

    val value = 100
    val tuple = Tuple1(value)

    ToBytes.toBytes(tuple).toVector shouldBe ToBytes.toBytes(value).toVector
  }

  "Pairs of values" should "serialize properly" in forAll { (p: (Long, List[Byte])) =>
    roundTrip[(Long, Seq[Byte])](p, FromBytes.tuple2(FromBytes.long, FromBytes.seq(FromBytes.byte)))
  }

  it should "follow the ABI spec" in {
    // pairs serialize as the concatenation of the serialization of the
    // elements it contains.

    val value1 = "Hello, world!"
    val value2 = Seq(1, 2, 4, 8, 16)
    val tuple  = (value1, value2)

    ToBytes.toBytes(tuple).toVector shouldBe Vector(
      ToBytes.toBytes(value1),
      ToBytes.toBytes(value2)
    ).flatten
  }

  "Triples of values" should "serialize properly" in forAll { (t: (Int, String, Long)) =>
    roundTrip(t, FromBytes.tuple3(FromBytes.int, FromBytes.string, FromBytes.long))
  }

  it should "follow the ABI spec" in {
    // triples serialize as the concatenation of the serialization of the
    // elements it contains.

    val value1 = 987L
    val value2 = Option(Seq("x", "y", "z"))
    val value3 = BigInt("27182818284590452353602874713527", 16)
    val tuple  = (value1, value2, value3)

    ToBytes.toBytes(tuple).toVector shouldBe Vector(
      ToBytes.toBytes(value1),
      ToBytes.toBytes(value2),
      ToBytes.toBytes(value3)
    ).flatten
  }
}

object SerializationTest extends Matchers {
  def roundTrip[T: ToBytes](t: T, des: FromBytes.Deserializer[T]) =
    FromBytes.deserialize(des, ToBytes[T].toBytes(t)) shouldBe Right(t)
}
