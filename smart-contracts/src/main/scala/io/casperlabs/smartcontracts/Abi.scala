package io.casperlabs.smartcontracts
import simulacrum.typeclass
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import io.casperlabs.casper.consensus.state.{BigInt, Key}
import io.casperlabs.casper.consensus.Deploy
import scala.util.{Failure, Success, Try}

@typeclass
trait Abi[T] {
  def toBytes(x: T): Try[Array[Byte]]
}

object Abi {
  def instance[T](to: T => Try[Array[Byte]]) = new Abi[T] {
    override def toBytes(x: T) = to(x)
  }

  /** Helper class to be used with `Abi.args(...)` */
  case class Serializable[T](value: T)(implicit ev: Abi[T]) {
    def toBytes = ev.toBytes(value)
  }
  object Serializable {
    implicit def fromValue[T: Abi](value: T) = Serializable(value)
  }

  private implicit class TryOps[T](val ta: Try[Array[Byte]]) extends AnyVal {
    def ++(b: Array[Byte]): Try[Array[Byte]]          = ta.map(a => a ++ b)
    def ++(tb: => Try[Array[Byte]]): Try[Array[Byte]] = for { a <- ta; b <- tb } yield (a ++ b)
    def ++:(b: Array[Byte]): Try[Array[Byte]]         = ta.map(a => b ++ a)
  }

  implicit val `Int => ABI` = instance[Int] { x =>
    Success(
      ByteBuffer
        .allocate(4)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(x)
        .array()
    )
  }

  implicit val `Long => ABI` = instance[Long] { x =>
    Success(
      ByteBuffer
        .allocate(8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(x)
        .array()
    )
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
      case Key.Value.Address(x) => Array[Byte](0) ++: Abi.toBytes(x.account.toByteArray)
      case Key.Value.Hash(x)    => Array[Byte](1) ++: Abi.toBytes(x.hash.toByteArray)
      case Key.Value.Uref(x)    => Array[Byte](2) ++: Abi.toBytes(x.uref.toByteArray)
      case Key.Value.Local(x)   => Array[Byte](3) ++: Abi.toBytes(x.hash.toByteArray)
      case Key.Value.Empty =>
        Failure(new java.lang.IllegalArgumentException("Cannot serialize empty Key to ABI."))
    }
  }

  implicit val `BigInt => ABI` = instance[BigInt] { x =>
    // EE expects unsigned, so only positive values.
    // Have a look at https://github.com/JetBrains/jdk8u_jdk/blob/master/src/share/classes/java/math/BigInteger.java#L3976
    // to see what how BigInteger can serialize itself: two-complement big-endian values.
    val int   = new java.math.BigInteger(x.value)
    val bytes = int.toByteArray
    // We think if the values are positive then the leftmost value will be the sign bit and
    // then it's hopefully the same as what Rust expects.
    val unsignedLittleEndian = bytes.dropWhile(_ == 0.toByte).reverse
    // For some reason the BigInt expects the length of bytes to be in a 1 sized array,
    // instead of the usual 4.
    Abi.toBytes(unsignedLittleEndian).map(_.take(1) ++ unsignedLittleEndian)
  }

  implicit def `Option => ABI`[T: Abi] = instance[Option[T]] { x =>
    x.fold(Try(Array[Byte](0)))(Array[Byte](1) ++: Abi.toBytes(_))
  }

  implicit def `Seq => ABI`[T: Abi] = instance[Seq[T]] { xs =>
    xs.foldLeft(Abi.toBytes(xs.length))(_ ++ Abi.toBytes(_))
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
        Failure(
          new java.lang.IllegalArgumentException(
            "Empty deploy arguments are not supported yet, use the `optional_value` variant!"
          )
        )
      case Value.OptionalValue(x) =>
        if (x.value.isEmpty) Abi.none else Abi.toBytes(Option(x))
      case Value.BytesValue(x)  => Abi.toBytes(x.toByteArray)
      case Value.IntValue(x)    => Abi.toBytes(x)
      case Value.IntList(x)     => Abi.toBytes(x.values)
      case Value.StringValue(x) => Abi.toBytes(x)
      case Value.StringList(x)  => Abi.toBytes(x.values)
      case Value.LongValue(x)   => Abi.toBytes(x)
      case Value.BigInt(x)      => Abi.toBytes(x)
      case Value.Key(x)         => Abi.toBytes(x)
    }
  }

  def toBytes[T: Abi](x: T): Try[Array[Byte]] = Abi[T].toBytes(x)

  def args(args: Serializable[_]*): Try[Array[Byte]] =
    args.foldLeft(Abi.toBytes(args.length)) {
      case (acc, arg) => acc ++ arg.toBytes.flatMap(Abi.toBytes(_))
    }
}
