package io.casperlabs.models.bytesrepr

object Constants {
  object Boolean {
    val FALSE_TAG: Byte = 0
    val TRUE_TAG: Byte  = 1
  }

  object Option {
    val NONE_TAG: Byte = 0
    val SOME_TAG: Byte = 1
  }

  object Either {
    val LEFT_TAG: Byte  = 0
    val RIGHT_TAG: Byte = 1
  }
}
