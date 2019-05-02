package io.casperlabs.casper.api

import cats.{Id, Monad}
import cats.data.StateT
import cats.effect.{Concurrent, Sync}
import cats.effect.concurrent.Semaphore
import cats.implicits._
import cats.mtl._
import cats.mtl.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.ski._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b512Random
import io.casperlabs.graphz._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log

object BlockAPI {

  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CasperMetricsSource, "block-api")

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics] =
    for {
      _ <- Metrics[F].incrementCounter("deploys", 0)
      _ <- Metrics[F].incrementCounter("deploys-success", 0)
      _ <- Metrics[F].incrementCounter("create-blocks", 0)
      _ <- Metrics[F].incrementCounter("create-blocks-success", 0)
    } yield ()

  def deploy[F[_]: Monad: MultiParentCasperRef: Log: Metrics](
      d: DeployData
  ): F[DeployServiceResponse] = {
    def casperDeploy(implicit casper: MultiParentCasper[F]): F[DeployServiceResponse] =
      for {
        _ <- Metrics[F].incrementCounter("deploys")
        r <- MultiParentCasper[F].deploy(d)
        re <- r match {
               case Right(_) =>
                 Metrics[F].incrementCounter("deploys-success") *>
                   DeployServiceResponse(success = true, "Success!").pure[F]
               case Left(err) =>
                 DeployServiceResponse(success = false, err.getMessage).pure[F]
             }
      } yield re

    val errorMessage = "Could not deploy."

    MultiParentCasperRef
      .withCasper[F, DeployServiceResponse](
        casperDeploy(_),
        errorMessage,
        DeployServiceResponse(success = false, s"Error: $errorMessage").pure[F]
      )
  }

  def createBlock[F[_]: Concurrent: MultiParentCasperRef: Log: Metrics](
      blockApiLock: Semaphore[F]
  ): F[DeployServiceResponse] = {
    val errorMessage = "Could not create block."
    MultiParentCasperRef.withCasper[F, DeployServiceResponse](
      casper => {
        Sync[F].bracket(blockApiLock.tryAcquire) {
          case true =>
            for {
              _          <- Metrics[F].incrementCounter("create-blocks")
              maybeBlock <- casper.createBlock
              result <- maybeBlock match {
                         case err: NoBlock =>
                           DeployServiceResponse(
                             success = false,
                             s"Error while creating block: $err"
                           ).pure[F]
                         case Created(block) =>
                           Metrics[F].incrementCounter("create-blocks-success") *>
                             casper
                               .addBlock(block)
                               .map(addResponse(_, block))
                       }
            } yield result
          case false =>
            DeployServiceResponse(success = false, "Error: There is another propose in progress.")
              .pure[F]
        } { _ =>
          blockApiLock.release
        }
      },
      errorMessage,
      default = DeployServiceResponse(success = false, s"Error: $errorMessage").pure[F]
    )
  }

  // FIX: Not used at the moment - in RChain it's being used in method like `getListeningName*`
  private def getMainChainFromTip[F[_]: Sync: MultiParentCasper: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[IndexedSeq[BlockMessage]] =
    for {
      dag       <- MultiParentCasper[F].blockDag
      tipHashes <- MultiParentCasper[F].estimator(dag)
      tipHash   = tipHashes.head
      tip       <- ProtoUtil.unsafeGetBlock[F](tipHash)
      mainChain <- ProtoUtil.getMainChainUntilDepth[F](tip, IndexedSeq.empty[BlockMessage], depth)
    } yield mainChain

  def visualizeDag[
      F[_]: Monad: Sync: MultiParentCasperRef: Log: SafetyOracle: BlockStore,
      G[_]: Monad: GraphSerializer
  ](
      d: Option[Int] = None,
      visualizer: (Vector[Vector[BlockHash]], String) => F[G[Graphz[G]]],
      stringify: G[Graphz[G]] => String
  ): F[String] = {
    val errorMessage =
      "Could not visualize graph."

    def casperResponse(implicit casper: MultiParentCasper[F]): F[String] =
      for {
        dag                <- MultiParentCasper[F].blockDag
        maxHeight          <- dag.topoSort(0L).map(_.length - 1)
        depth              = d.getOrElse(maxHeight)
        topoSort           <- dag.topoSortTail(depth)
        lastFinalizedBlock <- MultiParentCasper[F].lastFinalizedBlock
        graph              <- visualizer(topoSort, PrettyPrinter.buildString(lastFinalizedBlock.blockHash))
      } yield stringify(graph)

    MultiParentCasperRef.withCasper[F, String](
      casperResponse(_),
      errorMessage,
      errorMessage.pure[F]
    )
  }

  // TOOD extract common code from show blocks
  def showBlocks[F[_]: Sync: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[List[BlockInfoWithoutTuplespace]] = {
    val errorMessage =
      "Could not show blocks."

    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag <- MultiParentCasper[F].blockDag
        flattenedBlockInfosUntilDepth <- getFlattenedBlockInfosUntilDepth[F](
                                          depth,
                                          dag
                                        )
      } yield flattenedBlockInfosUntilDepth.reverse

    MultiParentCasperRef.withCasper[F, List[BlockInfoWithoutTuplespace]](
      casperResponse(_),
      errorMessage,
      List.empty[BlockInfoWithoutTuplespace].pure[F]
    )
  }

  private def getFlattenedBlockInfosUntilDepth[F[_]: Sync: MultiParentCasper: Log: SafetyOracle: BlockStore](
      depth: Int,
      dag: BlockDagRepresentation[F]
  ): F[List[BlockInfoWithoutTuplespace]] =
    for {
      topoSort <- dag.topoSortTail(depth)
      result <- topoSort.foldM(List.empty[BlockInfoWithoutTuplespace]) {
                 case (blockInfosAtHeightAcc, blockHashesAtHeight) =>
                   for {
                     blocksAtHeight     <- blockHashesAtHeight.traverse(ProtoUtil.unsafeGetBlock[F])
                     blockInfosAtHeight <- blocksAtHeight.traverse(getBlockInfoWithoutTuplespace[F])
                   } yield blockInfosAtHeightAcc ++ blockInfosAtHeight
               }
    } yield result

  def showMainChain[F[_]: Sync: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      depth: Int
  ): F[List[BlockInfoWithoutTuplespace]] = {
    val errorMessage =
      "Could not show main chain."

    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag        <- MultiParentCasper[F].blockDag
        tipHashes  <- MultiParentCasper[F].estimator(dag)
        tipHash    = tipHashes.head
        tip        <- ProtoUtil.unsafeGetBlock[F](tipHash)
        mainChain  <- ProtoUtil.getMainChainUntilDepth[F](tip, IndexedSeq.empty[BlockMessage], depth)
        blockInfos <- mainChain.toList.traverse(getBlockInfoWithoutTuplespace[F])
      } yield blockInfos

    MultiParentCasperRef.withCasper[F, List[BlockInfoWithoutTuplespace]](
      casperResponse(_),
      errorMessage,
      List.empty[BlockInfoWithoutTuplespace].pure[F]
    )
  }

  // TODO: Replace with call to BlockStore
  def findBlockWithDeploy[F[_]: Sync: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      user: ByteString,
      timestamp: Long
  ): F[BlockQueryResponse] = {
    val errorMessage =
      "Could not find block with deploy."

    def casperResponse(implicit casper: MultiParentCasper[F]): F[BlockQueryResponse] =
      for {
        dag                <- MultiParentCasper[F].blockDag
        allBlocksTopoSort  <- dag.topoSort(0L)
        maybeBlock         <- findBlockWithDeploy[F](allBlocksTopoSort.flatten.reverse, user, timestamp)
        blockQueryResponse <- maybeBlock.traverse(getFullBlockInfo[F])
      } yield
        blockQueryResponse.fold(
          BlockQueryResponse(
            status = s"Error: Failure to find block containing deploy signed by ${PrettyPrinter
              .buildString(user)} with timestamp ${timestamp.toString}"
          )
        )(
          blockInfo =>
            BlockQueryResponse(
              status = "Success",
              blockInfo = Some(blockInfo)
            )
        )

    MultiParentCasperRef.withCasper[F, BlockQueryResponse](
      casperResponse(_),
      errorMessage,
      BlockQueryResponse(status = s"Error: errorMessage").pure[F]
    )
  }

  private def findBlockWithDeploy[F[_]: Sync: Log: BlockStore](
      blockHashes: Vector[BlockHash],
      user: ByteString,
      timestamp: Long
  ): F[Option[BlockMessage]] =
    blockHashes.toStream
      .traverse(ProtoUtil.unsafeGetBlock[F](_))
      .map(blocks => blocks.find(ProtoUtil.containsDeploy(_, user, timestamp)))

  def showBlock[F[_]: Monad: MultiParentCasperRef: Log: SafetyOracle: BlockStore](
      q: BlockQuery
  ): F[BlockQueryResponse] = {
    val errorMessage =
      "Could not show block."

    def casperResponse(implicit casper: MultiParentCasper[F]) =
      for {
        dag        <- MultiParentCasper[F].blockDag
        maybeBlock <- getBlock[F](q, dag)
        blockQueryResponse <- maybeBlock match {
                               case Some(block) =>
                                 for {
                                   blockInfo <- getFullBlockInfo[F](block)
                                 } yield
                                   BlockQueryResponse(
                                     status = "Success",
                                     blockInfo = Some(blockInfo)
                                   )
                               case None =>
                                 BlockQueryResponse(
                                   status = s"Error: Failure to find block with hash ${q.hash}"
                                 ).pure[F]
                             }
      } yield blockQueryResponse

    MultiParentCasperRef.withCasper[F, BlockQueryResponse](
      casperResponse(_),
      errorMessage,
      BlockQueryResponse(status = s"Error: $errorMessage").pure[F]
    )
  }

  private def getBlockInfo[A, F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage,
      constructor: (
          BlockMessage,
          Long,
          Int,
          BlockHash,
          Long,
          BlockHash,
          Seq[BlockHash],
          Float,
          Float
      ) => F[A]
  ): F[A] =
    for {
      dag                      <- MultiParentCasper[F].blockDag
      header                   = block.header.getOrElse(Header.defaultInstance)
      protocolVersion          = header.protocolVersion
      deployCount              = header.deployCount
      postStateHash            = ProtoUtil.postStateHash(block)
      timestamp                = header.timestamp
      mainParent               = header.parentsHashList.headOption.getOrElse(ByteString.EMPTY)
      parentsHashList          = header.parentsHashList
      normalizedFaultTolerance <- SafetyOracle[F].normalizedFaultTolerance(dag, block.blockHash)
      initialFault             <- MultiParentCasper[F].normalizedInitialFault(ProtoUtil.weightMap(block))
      blockInfo <- constructor(
                    block,
                    protocolVersion,
                    deployCount,
                    postStateHash,
                    timestamp,
                    mainParent,
                    parentsHashList,
                    normalizedFaultTolerance,
                    initialFault
                  )
    } yield blockInfo

  private def getFullBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage
  ): F[BlockInfo] = getBlockInfo[BlockInfo, F](block, constructBlockInfo[F])
  private def getBlockInfoWithoutTuplespace[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage
  ): F[BlockInfoWithoutTuplespace] =
    getBlockInfo[BlockInfoWithoutTuplespace, F](block, constructBlockInfoWithoutTuplespace[F])

  private def constructBlockInfo[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage,
      protocolVersion: Long,
      deployCount: Int,
      postStateHash: BlockHash,
      timestamp: Long,
      mainParent: BlockHash,
      parentsHashList: Seq[BlockHash],
      normalizedFaultTolerance: Float,
      initialFault: Float
  ): F[BlockInfo] =
    for {
      tsDesc <- MultiParentCasper[F].storageContents(postStateHash)
    } yield
      BlockInfo(
        blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
        blockSize = block.serializedSize.toString,
        blockNumber = ProtoUtil.blockNumber(block),
        protocolVersion = protocolVersion,
        deployCount = deployCount,
        tupleSpaceHash = PrettyPrinter.buildStringNoLimit(postStateHash),
        tupleSpaceDump = tsDesc,
        timestamp = timestamp,
        faultTolerance = normalizedFaultTolerance - initialFault,
        mainParentHash = PrettyPrinter.buildStringNoLimit(mainParent),
        parentsHashList = parentsHashList.map(PrettyPrinter.buildStringNoLimit),
        sender = PrettyPrinter.buildStringNoLimit(block.sender),
        shardId = block.shardId
      )

  private def constructBlockInfoWithoutTuplespace[F[_]: Monad: MultiParentCasper: SafetyOracle: BlockStore](
      block: BlockMessage,
      protocolVersion: Long,
      deployCount: Int,
      postStateHash: BlockHash,
      timestamp: Long,
      mainParent: BlockHash,
      parentsHashList: Seq[BlockHash],
      normalizedFaultTolerance: Float,
      initialFault: Float
  ): F[BlockInfoWithoutTuplespace] =
    BlockInfoWithoutTuplespace(
      blockHash = PrettyPrinter.buildStringNoLimit(block.blockHash),
      blockSize = block.serializedSize.toString,
      blockNumber = ProtoUtil.blockNumber(block),
      protocolVersion = protocolVersion,
      deployCount = deployCount,
      tupleSpaceHash = PrettyPrinter.buildStringNoLimit(postStateHash),
      timestamp = timestamp,
      faultTolerance = normalizedFaultTolerance - initialFault,
      mainParentHash = PrettyPrinter.buildStringNoLimit(mainParent),
      parentsHashList = parentsHashList.map(PrettyPrinter.buildStringNoLimit),
      sender = PrettyPrinter.buildStringNoLimit(block.sender)
    ).pure[F]

  private def getBlock[F[_]: Monad: MultiParentCasper: BlockStore](
      q: BlockQuery,
      dag: BlockDagRepresentation[F]
  ): F[Option[BlockMessage]] =
    for {
      findResult <- BlockStore[F].find(h => {
                     Base16.encode(h.toByteArray).startsWith(q.hash)
                   })
    } yield
      findResult.headOption.flatMap {
        case (_, blockWithTransform) => blockWithTransform.blockMessage
      }

  private def addResponse(status: BlockStatus, block: BlockMessage): DeployServiceResponse =
    status match {
      case _: InvalidBlock =>
        DeployServiceResponse(success = false, s"Failure! Invalid block: $status")
      case _: ValidBlock =>
        val hash = PrettyPrinter.buildString(block.blockHash)
        DeployServiceResponse(success = true, s"Success! Block $hash created and added.")
      case BlockException(ex) =>
        DeployServiceResponse(success = false, s"Error during block processing: $ex")
      case Processing =>
        DeployServiceResponse(
          success = false,
          "No action taken since other thread is already processing the block."
        )
    }
}
