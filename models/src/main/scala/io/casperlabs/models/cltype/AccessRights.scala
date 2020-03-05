package io.casperlabs.models.cltype

import io.casperlabs.models.bytesrepr.{BytesView, FromBytes, ToBytes}

sealed trait AccessRights {
  val tag: Byte
}

object AccessRights {
  case object None extends AccessRights {
    val tag: Byte = 0 // 0b000
  }

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

  val deserializer: FromBytes.Deserializer[AccessRights] =
    FromBytes.byte.flatMap {
      case tag if tag == None.tag         => FromBytes.pure(None)
      case tag if tag == Read.tag         => FromBytes.pure(Read)
      case tag if tag == Write.tag        => FromBytes.pure(Write)
      case tag if tag == ReadWrite.tag    => FromBytes.pure(ReadWrite)
      case tag if tag == Add.tag          => FromBytes.pure(Add)
      case tag if tag == ReadAdd.tag      => FromBytes.pure(ReadAdd)
      case tag if tag == ReadAdd.tag      => FromBytes.pure(ReadAdd)
      case tag if tag == AddWrite.tag     => FromBytes.pure(AddWrite)
      case tag if tag == ReadAddWrite.tag => FromBytes.pure(ReadAddWrite)
      case other                          => FromBytes.raise(FromBytes.Error.InvalidVariantTag(other, "AccessRights"))
    }
}
