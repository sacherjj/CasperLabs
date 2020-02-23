package io.casperlabs.crypto

import com.google.protobuf.ByteString

package object codec {
  implicit class StringSyntax(s: String) {
    /* Filters input from invalid characters */
    def base16Decode: Array[Byte]            = Base16.decode(s)
    def base64TryDecode: Option[Array[Byte]] = Base64.tryDecode(s)
    def tryBase64AndBase16Decode: Option[Array[Byte]] =
      Base16.tryDecode(s).orElse(Base64.tryDecode(s))
  }

  implicit class ByteArraySyntax(a: Array[Byte]) {
    def base16Encode: String     = Base16.encode(a)
    def base64Encode: String     = Base64.encode(a)
    def toByteString: ByteString = ByteString.copyFrom(a)
  }
}
