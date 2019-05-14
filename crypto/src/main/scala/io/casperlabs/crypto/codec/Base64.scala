package io.casperlabs.crypto.codec

/**
  * Uses [[java.util.Base64]] under the hood.
  * [[java.util.Base64.Encoder.RFC4648]] and [[java.util.Base64.Decoder.RFC4648]]
  * are defined as static variables, so it won't allocate new objects each time.
  */
object Base64 {
  def encode(a: Array[Byte]): String = java.util.Base64.getEncoder.encodeToString(a)

  def tryDecode(s: String): Option[Array[Byte]] =
    try {
      Some(java.util.Base64.getDecoder.decode(s.trim))
    } catch {
      case _: IllegalArgumentException => None
    }
}
