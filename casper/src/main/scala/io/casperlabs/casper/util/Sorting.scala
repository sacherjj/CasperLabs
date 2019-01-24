package io.casperlabs.casper.util

import io.casperlabs.crypto.codec.Base16

object Sorting {

  implicit val byteArrayOrdering = Ordering.by(Base16.encode)
}
