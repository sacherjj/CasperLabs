package io.casperlabs.crypto.codec

object Base16 {
  def encode(input: Array[Byte]): String = bytes2hex(input, None)

  /* Filters input from invalid characters */
  def decode(input: String): Array[Byte] = {
    val paddedInput =
      if (input.length % 2 == 0) input
      else "0" + input

    hex2bytes(paddedInput)
  }

  /* Fails if can't decode, expects fully correct Base-16 String */
  def tryDecode(input: String): Option[Array[Byte]] =
    try {
      Option(input.sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte))
    } catch {
      case _: Throwable => None
    }

  private def bytes2hex(bytes: Array[Byte], sep: Option[String]): String =
    bytes.map("%02x".format(_)).mkString(sep.getOrElse(""))

  private def hex2bytes(hex: String): Array[Byte] =
    hex.replaceAll("[^0-9A-Fa-f]", "").sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)
}
