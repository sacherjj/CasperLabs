package io.casperlabs.casper.util

import org.scalactic.Prettifier
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.models.PartialPrettifier
import com.google.protobuf.ByteString
import scala.util.Success
import scala.collection.{mutable, GenMap}
import scala.collection.GenTraversable

trait ByteStringPrettifier {
  // Shameless copy-paste of Scalactic's Prettifier that injects special pretty-printer for ByteString.
  implicit val byteStringPrettifier: Prettifier = PartialPrettifier {
    case bs: ByteString => PrettyPrinter.buildString(bs)
  }
}

object ByteStringPrettifier extends ByteStringPrettifier
