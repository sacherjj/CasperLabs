package io.casperlabs.shared

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message

object Sorting {

  implicit val byteArrayOrdering: Ordering[Array[Byte]] = Ordering.by(Base16.encode)

  implicit val byteStringOrdering: Ordering[ByteString] = Ordering.by(_.toByteArray)

  implicit val blockSummaryOrdering: Ordering[BlockSummary] = (x: BlockSummary, y: BlockSummary) =>
    x.rank.compare(y.rank) match {
      case 0 => Ordering[ByteString].compare(x.blockHash, y.blockHash)
      case n => n
    }

  implicit def messageSummaryOrdering[A <: Message]: Ordering[A] =
    (x: A, y: A) =>
      x.rank.compare(y.rank) match {
        case 0 => Ordering[ByteString].compare(x.messageHash, y.messageHash)
        case n => n
      }
}
