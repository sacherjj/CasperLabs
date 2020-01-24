package io.casperlabs.crypto.codec

import com.google.protobuf.ByteString

object Base16 {
  def encode(input: Array[Byte]): String = bytes2hex(input, None)

  /* Filters input from invalid characters */
  def decode(input: String): Array[Byte] = {
    val paddedInput =
      if (input.length % 2 == 0) input
      else "0" + input

    hex2bytes(paddedInput)
  }

  implicit class StringOps(s: String) {
    /* Filters input from invalid characters */
    def hexDecode: Array[Byte] = Base16.decode(s)
  }

  implicit class ByteArrayOps(a: Array[Byte]) {
    def hexEncode: String        = Base16.encode(a)
    def toByteString: ByteString = ByteString.copyFrom(a)
  }

  private def bytes2hex(bytes: Array[Byte], sep: Option[String]): String =
    bytes.map("%02x".format(_)).mkString(sep.getOrElse(""))

  private def hex2bytes(hex: String): Array[Byte] =
    hex.replaceAll("[^0-9A-Fa-f]", "").sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)
}
