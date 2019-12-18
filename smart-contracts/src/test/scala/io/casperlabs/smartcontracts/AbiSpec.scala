package io.casperlabs.smartcontracts
import org.scalatest._

class AbiSpec extends FlatSpec with Matchers {
  def wrap(bytes: Array[Byte]): java.nio.ByteBuffer = {
    val buffer = java.nio.ByteBuffer.allocate(bytes.length).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    buffer.put(bytes)
    buffer.flip() // Otherwise UnderflowException.
    buffer
  }

  behavior of "Abi"

  it should "serialize Long as 64 bits using little endiannes" in {
    val value = 1234567890L
    val bytes = Abi.toBytes(value).get
    bytes should have size (64 / 8)
    wrap(bytes).getLong() shouldBe value
  }

  it should "serialize Array[Byte] as size ++ content using little endiannes" in {
    val value = Array.range(0, 32).map(_.toByte)
    val bytes = Abi.toBytes(value).get
    bytes should have size (32)
    bytes(0) shouldBe 0
    bytes(1) shouldBe 1
    bytes(4) shouldBe value(4)
    bytes(31) shouldBe value(31)
  }

  it should "serialize multiple args with size ++ concatentation of parts" in {
    val a      = Array.range(0, 32).map(_.toByte)
    val b      = 500000L
    val bytes  = Abi.args(a, b).get
    val buffer = wrap(bytes)
    buffer.get(0) shouldBe 2
    buffer.get(1) shouldBe 0
    buffer.get(4) shouldBe 32
    buffer.get(5) shouldBe 0
    buffer.get(8) shouldBe 0
    buffer.get(40) shouldBe 8
    Array.range(8, 8 + 32).map(buffer.get) shouldBe a
    buffer.getLong(44) shouldBe b
  }

  it should "work with the hardcoded example" in {
    val a      = Array.fill(32)(1.toByte)
    val b      = 67305985L
    val result = Abi.args(a, b).get
    val expected = Array[Byte](
      2, 0, 0, 0,  // number of args
      32, 0, 0, 0, // length of contents of `a`
      1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
      1,                     // contents of `a`
      8, 0, 0, 0,            // length of `b` serialized as an arg
      1, 2, 3, 4, 0, 0, 0, 0 // bytes of `b` itself
    )
    result shouldBe expected
  }

  it should "serialize Some as 1 ++ value" in {
    val bytes = Abi.toBytes(Option(67305985L)).get
    bytes shouldBe Array[Byte](1, 1, 2, 3, 4, 0, 0, 0, 0)
  }

  it should "serialize None as 0" in {
    val bytes = Abi.toBytes(None: Option[Int]).get
    bytes shouldBe Array[Byte](0)
  }
}
