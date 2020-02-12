package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.cltype
import io.casperlabs.smartcontracts.bytesrepr._

sealed trait Key {
  protected val tag: Byte
  protected def innerToBytes: Array[Byte]
}

object Key {
  case class Account(address: ByteArray32) extends Key {
    override protected val tag: Byte        = Account.tag
    protected def innerToBytes: Array[Byte] = ToBytes[ByteArray32].toBytes(address)
  }

  object Account {
    val tag: Byte = 0

    def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Key, BytesView)] =
      FromBytes[ByteArray32].fromBytes(bytes).map {
        case (addr, tail) => Account(addr) -> tail
      }
  }

  case class Hash(address: ByteArray32) extends Key {
    override protected val tag: Byte        = Hash.tag
    protected def innerToBytes: Array[Byte] = ToBytes[ByteArray32].toBytes(address)
  }

  object Hash {
    val tag: Byte = 1

    def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Key, BytesView)] =
      FromBytes[ByteArray32].fromBytes(bytes).map {
        case (addr, tail) => Hash(addr) -> tail
      }
  }

  case class URef(uref: cltype.URef) extends Key {
    override protected val tag: Byte        = URef.tag
    protected def innerToBytes: Array[Byte] = ToBytes[cltype.URef].toBytes(uref)
  }

  object URef {
    val tag: Byte = 2

    def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Key, BytesView)] =
      FromBytes[cltype.URef].fromBytes(bytes).map {
        case (inner, tail) => (URef(inner), tail)
      }
  }

  case class Local(address: ByteArray32) extends Key {
    override protected val tag: Byte        = Local.tag
    protected def innerToBytes: Array[Byte] = ToBytes[ByteArray32].toBytes(address)
  }

  object Local {
    val tag: Byte = 3

    def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Key, BytesView)] =
      FromBytes[ByteArray32].fromBytes(bytes).map {
        case (addr, tail) => Local(addr) -> tail
      }
  }

  implicit val toBytesKey: ToBytes[Key] = new ToBytes[Key] {
    override def toBytes(k: Key): Array[Byte] =
      k.tag +: k.innerToBytes
  }

  implicit val fromBytesKey: FromBytes[Key] = new FromBytes[Key] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (Key, BytesView)] =
      FromBytes.safePop(bytes).flatMap {
        case (tag, tail) if tag == Account.tag => Account.fromBytes(tail)
        case (tag, tail) if tag == Hash.tag    => Hash.fromBytes(tail)
        case (tag, tail) if tag == URef.tag    => URef.fromBytes(tail)
        case (tag, tail) if tag == Local.tag   => Local.fromBytes(tail)
        case (other, _)                        => Left(FromBytes.Error.InvalidVariantTag(other, "Key"))
      }
  }

  implicit val clTypedKey: CLTyped[Key] = new CLTyped[Key] {
    override def clType: CLType = CLType.Key
  }
}
