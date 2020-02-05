package io.casperlabs.smartcontracts.cltype

import io.casperlabs.smartcontracts.bytesrepr.{BytesView, FromBytes, ToBytes}

sealed trait AccessRights {
  val tag: Byte
}

object AccessRights {
  case object Read extends AccessRights {
    val tag: Byte = 1 // 0b0001
  }

  case object Write extends AccessRights {
    val tag: Byte = 2 // 0b0010
  }

  case object ReadWrite extends AccessRights {
    val tag: Byte = 3 // 0b0011
  }

  case object Add extends AccessRights {
    val tag: Byte = 4 // 0b0100
  }

  case object ReadAdd extends AccessRights {
    val tag: Byte = 5 // 0b0101
  }

  case object AddWrite extends AccessRights {
    val tag: Byte = 6 // 0b0110
  }

  case object ReadAddWrite extends AccessRights {
    val tag: Byte = 7 // 0b0111
  }

  implicit val toBytesAccessRights: ToBytes[AccessRights] = new ToBytes[AccessRights] {
    override def toBytes(a: AccessRights): Array[Byte] = Array(a.tag)
  }

  implicit val fromBytesAccessRights: FromBytes[AccessRights] = new FromBytes[AccessRights] {
    override def fromBytes(bytes: BytesView): Either[FromBytes.Error, (AccessRights, BytesView)] =
      FromBytes.safePop(bytes).flatMap {
        case (tag, tail) if tag == Read.tag         => Right(Read -> tail)
        case (tag, tail) if tag == Write.tag        => Right(Write -> tail)
        case (tag, tail) if tag == ReadWrite.tag    => Right(ReadWrite -> tail)
        case (tag, tail) if tag == Add.tag          => Right(Add -> tail)
        case (tag, tail) if tag == ReadAdd.tag      => Right(ReadAdd -> tail)
        case (tag, tail) if tag == AddWrite.tag     => Right(AddWrite -> tail)
        case (tag, tail) if tag == ReadAddWrite.tag => Right(ReadAddWrite -> tail)
        case (other, _)                             => Left(FromBytes.Error.InvalidVariantTag(other, "AccessRights"))
      }
  }
}
