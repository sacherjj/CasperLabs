package io.casperlabs.storage.util

import com.google.protobuf.ByteString
import doobie._
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.{BlockSummary, Deploy}
import io.casperlabs.ipc.TransformEntry

trait DoobieCodecs {
  protected implicit val metaByteString: Meta[ByteString] =
    Meta[Array[Byte]].imap(ByteString.copyFrom)(_.toByteArray)

  protected implicit val readDeploy: Read[Deploy] =
    Read[Array[Byte]].map(Deploy.parseFrom)

  protected implicit val readProcessingResult: Read[(ByteString, ProcessedDeploy)] = {
    Read[(Array[Byte], Long, Option[String])].map {
      case (blockHash, cost, maybeError) =>
        (
          ByteString.copyFrom(blockHash),
          ProcessedDeploy(
            deploy = None,
            cost = cost,
            isError = maybeError.nonEmpty,
            errorMessage = maybeError.getOrElse("")
          )
        )
    }
  }

  protected implicit val metaBlockSummary: Meta[BlockSummary] =
    Meta[Array[Byte]].imap(BlockSummary.parseFrom)(_.toByteString.toByteArray)

  protected implicit val metaTransformEntry: Meta[TransformEntry] =
    Meta[Array[Byte]].imap(TransformEntry.parseFrom)(_.toByteString.toByteArray)
}
