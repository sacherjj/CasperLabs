package io.casperlabs.storage.util

import com.google.protobuf.ByteString
import doobie._
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.{BlockSummary, Deploy}
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.ipc.TransformEntry

trait DoobieCodecs {
  protected implicit val metaByteString: Meta[ByteString] =
    Meta[Array[Byte]].imap(ByteString.copyFrom)(_.toByteArray)

  protected implicit val metaPublicKeyBS: Meta[PublicKeyBS] =
    Meta[Array[Byte]].imap(d => PublicKey(ByteString.copyFrom(d)))(_.toByteArray)

  protected implicit val readDeploy: Read[Deploy] =
    Read[(Array[Byte], Option[Array[Byte]])].map {
      case (deploySummary, maybeDeployBody) =>
        val deploy = Deploy.parseFrom(deploySummary)
        val maybeBody = maybeDeployBody
          .filterNot(_.isEmpty)
          .map(Deploy.Body.parseFrom) orElse deploy.body
        maybeBody.map(deploy.withBody).getOrElse(deploy)
    }

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

  protected implicit val readProcessedDeploy: Read[ProcessedDeploy] = {
    Read[(Deploy, Long, Option[String])].map {
      case (deploy, cost, maybeError) =>
        ProcessedDeploy(
          deploy = Option(deploy),
          cost = cost,
          isError = maybeError.nonEmpty,
          errorMessage = maybeError.getOrElse("")
        )
    }
  }

  protected implicit val readBlockInfo: Read[BlockInfo] = {
    Read[(Array[Byte], Int, Int, Long, Long)].map {
      case (blockSummaryData, blockSize, deployErrorCount, deployCostTotal, deployGasPriceAvg) =>
        val blockSummary = BlockSummary.parseFrom(blockSummaryData)
        val blockStatus = BlockInfo
          .Status()
          .withStats(
            BlockInfo.Status
              .Stats()
              .withBlockSizeBytes(blockSize)
              .withDeployErrorCount(deployErrorCount)
              .withDeployCostTotal(deployCostTotal)
              .withDeployGasPriceAvg(deployGasPriceAvg)
          )
        BlockInfo()
          .withSummary(blockSummary)
          .withStatus(blockStatus)
    }
  }

  protected implicit val readDeployAndProcessingResult: Read[ProcessingResult] = {
    Read[(Long, Option[String], BlockInfo)].map {
      case (cost, maybeError, blockInfo) =>
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
