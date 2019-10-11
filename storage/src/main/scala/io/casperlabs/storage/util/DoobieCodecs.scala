package io.casperlabs.storage.util

import com.google.protobuf.ByteString
import doobie._
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.{BlockSummary, Deploy}
import io.casperlabs.casper.consensus.info.{BlockInfo, BlockStatus}
import io.casperlabs.casper.consensus.info.BlockStatus.Stats
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.ipc.TransformEntry

trait DoobieCodecs {
  protected implicit val metaByteString: Meta[ByteString] =
    Meta[Array[Byte]].imap(ByteString.copyFrom)(_.toByteArray)

  protected implicit val readDeploy: Read[Deploy] =
    Read[Array[Byte]].map(Deploy.parseFrom)

  protected implicit val readDeployHeader: Read[Deploy.Header] =
    Read[Array[Byte]].map(Deploy.Header.parseFrom)

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

  protected implicit val readDeployAndProcessingResult: Read[ProcessingResult] = {
    Read[(Long, Option[String], Array[Byte], Option[Int], Option[Int])].map {
      case (cost, maybeError, blockSummaryData, maybeBlockSize, maybeDeployErrorCount) =>
        val blockSummary = BlockSummary.parseFrom(blockSummaryData)
        val blockStatus = BlockStatus().withStats(
          BlockStatus
            .Stats()
            .withBlockSizeBytes(maybeBlockSize.getOrElse(0))
            .withDeployErrorCount(maybeDeployErrorCount.getOrElse(0))
        )
        val blockInfo = BlockInfo()
          .withSummary(blockSummary)
          .withStatus(blockStatus)

        ProcessingResult(
          cost = cost,
          isError = maybeError.nonEmpty,
          errorMessage = maybeError.getOrElse("")
        ).withBlockInfo(blockInfo)
    }
  }

  protected implicit val metaBlockSummary: Meta[BlockSummary] =
    Meta[Array[Byte]].imap(BlockSummary.parseFrom)(_.toByteString.toByteArray)

  protected implicit val metaTransformEntry: Meta[TransformEntry] =
    Meta[Array[Byte]].imap(TransformEntry.parseFrom)(_.toByteString.toByteArray)
}
