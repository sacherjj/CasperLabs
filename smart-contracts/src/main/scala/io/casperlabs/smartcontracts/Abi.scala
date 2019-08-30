package io.casperlabs.smartcontracts
import simulacrum.typeclass
import java.nio.{ByteBuffer, ByteOrder}

@typeclass
trait Abi[T] {
  def toBytes(x: T): Array[Byte]
}

object Abi {
  def instance[T](to: T => Array[Byte]) = new Abi[T] {
    override def toBytes(x: T) = to(x)
  }

  /** Helper class to be used with `Abi.args(...)` */
  case class Serializable[T](value: T)(implicit ev: Abi[T]) {
    def toBytes = ev.toBytes(value)
  }
  object Serializable {
    implicit def fromValue[T: Abi](value: T) = Serializable(value)
  }

  implicit val `Int => ABI` = instance[Int] { x =>
    ByteBuffer
      .allocate(4)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putInt(x)
      .array()
  }

  implicit val `Long => ABI` = instance[Long] { x =>
    ByteBuffer
      .allocate(8)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putLong(x)
      .array()
  }

  implicit val `Bytes => ABI` = instance[Array[Byte]] { x =>
    Abi[Int].toBytes(x.length) ++ x
  }

  def args(args: Serializable[_]*): Array[Byte] = {
    val bytes = args.flatMap(_.toBytes)
    Abi[Int].toBytes(bytes.length) ++ bytes
  }
}
