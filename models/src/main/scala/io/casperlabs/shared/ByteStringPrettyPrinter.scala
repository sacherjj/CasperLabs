package io.casperlabs.shared

import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys.{PublicKeyBS, PublicKeyHash}
import cats.Show

trait ByteStringPrettyPrinter {

  def limit(str: String, maxLength: Int): String =
    if (str.length > maxLength) {
      str.substring(0, maxLength) + "..."
    } else {
      str
    }

  def buildString(b: ByteString): String =
    limit(Base16.encode(b.toByteArray), 10)

  def buildStringNoLimit(b: ByteString): String = Base16.encode(b.toByteArray)

  implicit val byteStringShow: cats.Show[ByteString] =
    cats.Show.show[ByteString](buildStringNoLimit(_))

  implicit val publicKeyBSShow: cats.Show[PublicKeyBS] =
    cats.Show.show[PublicKeyBS](byteStringShow.show(_))

  implicit val publicKeyHashShow: cats.Show[PublicKeyHash] =
    cats.Show.show[PublicKeyHash](Base16.encode)
}

object ByteStringPrettyPrinter extends ByteStringPrettyPrinter
