package io.casperlabs.shared

import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16

object Sorting {

  implicit val byteArrayOrdering: Ordering[Array[Byte]] = Ordering.by(Base16.encode)

  implicit val byteStringOrdering: Ordering[ByteString] = Ordering.by(_.toByteArray)
}
