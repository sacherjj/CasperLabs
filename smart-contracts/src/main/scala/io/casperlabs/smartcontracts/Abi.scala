package io.casperlabs.smartcontracts
import simulacrum.typeclass
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import io.casperlabs.casper.consensus.state.Key
import io.casperlabs.casper.consensus.Deploy

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
    Abi.toBytes(x.length) ++ x
  }

  implicit val `String => ABI` = instance[String] { x =>
    val bytes = x.getBytes(StandardCharsets.UTF_8)
    Abi.toBytes(bytes.length) ++ bytes
  }

  implicit val `Key => ABI` = instance[Key] { x =>
    x.value match {
      case Key.Value.Hash(x)    => Array[Byte](0) ++ Abi.toBytes(x.hash.toByteArray)
      case Key.Value.Address(x) => Array[Byte](1) ++ Abi.toBytes(x.account.toByteArray)
      case Key.Value.Uref(x)    => Array[Byte](2) ++ Abi.toBytes(x.uref.toByteArray)
      case Key.Value.Local(x)   => Array[Byte](3) ++ Abi.toBytes(x.hash.toByteArray)
      case Key.Value.Empty =>
        throw new java.lang.IllegalArgumentException("Cannot serialize empty Key to ABI.")
    }
  }

  implicit def `Option => ABI`[T: Abi] = instance[Option[T]] { x =>
    x.fold(Array[Byte](0))(Array[Byte](1) ++ Abi.toBytes(_))
  }

  implicit def `Seq => ABI`[T: Abi] = instance[Seq[T]] { xs =>
    Abi.toBytes(xs.length) ++ xs.flatMap(Abi.toBytes(_))
  }

  // All None values are the same.
  val none = Abi.toBytes(None: Option[Int])

  implicit val `Deploy.Arg.Value => ABI`: Abi[Deploy.Arg.Value] = instance { x =>
    import Deploy.Arg.Value.Value
    x.value match {
      case Value.Empty =>
        // If kwargs were supported by the EE side we could treat Value.Empty as None,
        // but with positional arguments we can't as it would possibly lead to data corruption
        // if the EE for example expects a Long but we send just a single byte.
        throw new java.lang.IllegalArgumentException(
          "Empty deploy arguments are not supported yet, use the `optional_value` variant!"
        )
      case Value.OptionalValue(x) =>
        if (x.value.isEmpty) Abi.none else Abi.toBytes(Option(x))
      case Value.BytesValue(x)  => Abi.toBytes(x.toByteArray)
      case Value.IntValue(x)    => Abi.toBytes(x)
      case Value.IntList(x)     => Abi.toBytes(x.values)
      case Value.StringValue(x) => Abi.toBytes(x)
      case Value.StringList(x)  => Abi.toBytes(x.values)
      case Value.LongValue(x)   => Abi.toBytes(x)
      case Value.BigInt(_)      => ???
      case Value.Key(x)         => Abi.toBytes(x)
    }
  }

  def toBytes[T: Abi](x: T): Array[Byte] = Abi[T].toBytes(x)

  def args(args: Serializable[_]*): Array[Byte] = {
    val bytes = args.flatMap(x => Abi.toBytes(x.toBytes))
    Abi.toBytes(args.length) ++ bytes
  }
}
