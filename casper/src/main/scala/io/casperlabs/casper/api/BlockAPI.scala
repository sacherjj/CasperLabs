package io.casperlabs.casper.api

import cats.effect.concurrent.Semaphore
import cats.effect.{Bracket, Concurrent, Resource}
import cats.implicits._
import cats.{Functor, Monad}
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.{BlockStatus => _, _}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info._
import io.casperlabs.casper.finality.singlesweep.FinalityDetector
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.ServiceError
import io.casperlabs.comm.ServiceError._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import io.casperlabs.storage.StorageError
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageReader}

object BlockAPI {

  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CasperMetricsSource, "block-api")

  private def unsafeWithCasper[F[_]: MonadThrowable: Log: MultiParentCasperRef, A](
      msg: String
  )(f: MultiParentCasper[F] => F[A]): F[A] =
    MultiParentCasperRef
      .withCasper[F, A](
        f,
        msg,
        MonadThrowable[F].raiseError(Unavailable("Casper instance not available yet."))
      )

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics] =
    for {
      _ <- Metrics[F].incrementCounter("deploys", 0)
      _ <- Metrics[F].incrementCounter("deploys-success", 0)
      _ <- Metrics[F].incrementCounter("create-blocks", 0)
      _ <- Metrics[F].incrementCounter("create-blocks-success", 0)
    } yield ()

  def deploy[F[_]: MonadThrowable: MultiParentCasperRef: BlockStorage: Validation: FinalityDetector: Log: Metrics](
      d: Deploy
  ): F[Unit] = unsafeWithCasper[F, Unit]("Could not deploy.") { implicit casper =>
    def check(msg: String)(f: F[Boolean]): F[Unit] =
      f flatMap { ok =>
        MonadThrowable[F].raiseError(InvalidArgument(msg)).whenA(!ok)
      }

    for {
      _ <- Metrics[F].incrementCounter("deploys")
      _ <- check("Invalid deploy hash.")(Validation[F].deployHash(d))
      _ <- check("Invalid deploy signature.")(Validation[F].deploySignature(d))
      _ <- Validation[F].deployHeader(d) >>= { headerErrors =>
            MonadThrowable[F]
              .raiseError(InvalidArgument(headerErrors.map(_.errorMessage).mkString("\n")))
              .whenA(headerErrors.nonEmpty)
          }

      r <- MultiParentCasper[F].deploy(d)
      _ <- r match {
            case Right(_) =>
              Metrics[F].incrementCounter("deploys-success") *> ().pure[F]
            case Left(ex: IllegalArgumentException) =>
              MonadThrowable[F].raiseError[Unit](InvalidArgument(ex.getMessage))
            case Left(ex: IllegalStateException) =>
              MonadThrowable[F].raiseError[Unit](FailedPrecondition(ex.getMessage))
            case Left(ex) =>
              MonadThrowable[F].raiseError[Unit](ex)
          }
    } yield ()
  }

  def propose[F[_]: Bracket[?[_], Throwable]: MultiParentCasperRef: Log: Metrics](
      blockApiLock: Semaphore[F]
  ): F[ByteString] = {
    def raise[A](ex: ServiceError.Exception): F[ByteString] =
      MonadThrowable[F].raiseError(ex)

    unsafeWithCasper[F, ByteString]("Could not create block.") { implicit casper =>
      Resource.make(blockApiLock.tryAcquire)(blockApiLock.release.whenA).use {
        case true =>
          for {
            _          <- Metrics[F].incrementCounter("create-blocks")
            maybeBlock <- casper.createBlock
            result <- maybeBlock match {
                       case Created(block) =>
                         for {
                           status <- casper.addBlock(block)
                           res <- status match {
                                   case _: ValidBlock =>
                                     block.blockHash.pure[F]
                                   case _: InvalidBlock =>
                                     raise(InvalidArgument(s"Invalid block: $status"))
                                   case UnexpectedBlockException(ex) =>
                                     raise(Internal(s"Error during block processing: $ex"))
                                   case Processing | Processed =>
                                     raise(
                                       Aborted(
                                         "No action taken since other thread is already processing the block."
                                       )
                                     )
                                 }
                           _ <- Metrics[F].incrementCounter("create-blocks-success")
                         } yield res

                       case InternalDeployError(ex) =>
                         raise(Internal(ex.getMessage))

                       case ReadOnlyMode =>
                         raise(FailedPrecondition("Node is in read-only mode."))

                       case NoNewDeploys =>
                         raise(OutOfRange("No new deploys."))
                     }
          } yield result

        case false =>
          raise(Aborted("There is another propose in progress."))
      }
    }
  }

  def getDeployInfoOpt[F[_]: MonadThrowable: Log: MultiParentCasperRef: FinalityDetector: BlockStorage: DeployStorageReader](
      deployHashBase16: String
  ): F[Option[DeployInfo]] =
    if (deployHashBase16.length != 64) {
      Log[F].warn("Deploy hash must be 32 bytes long") >> none[DeployInfo].pure[F]
    } else {
      val deployHash = ByteString.copyFrom(Base16.decode(deployHashBase16))

      BlockStorage[F].findBlockHashesWithDeployHash(deployHash) flatMap {
        case blockHashes if blockHashes.nonEmpty =>
          for {
            blocks <- blockHashes.toList.traverse(ProtoUtil.unsafeGetBlock[F](_))
            blockInfos = blocks.map { block =>
              val summary =
                BlockSummary(block.blockHash, block.header, block.signature)
              makeBlockInfo(summary, block.some)
            }
            results = (blocks zip blockInfos).flatMap {
              case (block, info) =>
                block.getBody.deploys
                  .find(_.getDeploy.deployHash == deployHash)
                  .map(_ -> info)
            }
            info = DeployInfo(
              deploy = results.headOption.flatMap(_._1.deploy),
              processingResults = results.map {
                case (processedDeploy, blockInfo) =>
                  DeployInfo
                    .ProcessingResult(
                      cost = processedDeploy.cost,
                      isError = processedDeploy.isError,
                      errorMessage = processedDeploy.errorMessage
                    )
                    .withBlockInfo(blockInfo)
              }
            )
          } yield info.some

        case _ =>
          DeployStorageReader[F]
            .getPendingOrProcessed(deployHash)
            .map(_.map(DeployInfo().withDeploy))
      }
    }

  def getDeployInfo[F[_]: MonadThrowable: Log: MultiParentCasperRef: FinalityDetector: BlockStorage: DeployStorage](
      deployHashBase16: String
  ): F[DeployInfo] =
    getDeployInfoOpt[F](deployHashBase16).flatMap(
      _.fold(
        MonadThrowable[F]
          .raiseError[DeployInfo](NotFound(s"Cannot find deploy with hash $deployHashBase16"))
      )(_.pure[F])
    )

  def getBlockDeploys[F[_]: Functor: BlockStorage](
      blockHashBase16: String
  ): F[Seq[Block.ProcessedDeploy]] =
    BlockStorage[F]
      .getByPrefix(blockHashBase16)
      .map(_.fold(Seq.empty[Block.ProcessedDeploy])(_.getBlockMessage.getBody.deploys))

  def makeBlockInfo(
      summary: BlockSummary,
      maybeBlock: Option[Block]
  ): BlockInfo = {
    val maybeStats = maybeBlock.map { block =>
      BlockStatus
        .Stats()
        .withBlockSizeBytes(block.serializedSize)
        .withDeployErrorCount(
          block.getBody.deploys.count(_.isError)
        )
    }
    val status = BlockStatus(stats = maybeStats)
    BlockInfo()
      .withSummary(summary)
      .withStatus(status)
  }

  def makeBlockInfo[F[_]: Monad: BlockStorage: MultiParentCasper: FinalityDetector](
      summary: BlockSummary,
      full: Boolean
  ): F[(BlockInfo, Option[Block])] =
    for {
      maybeBlock <- if (full) {
                     BlockStorage[F]
                       .get(summary.blockHash)
                       .map(_.get.blockMessage)
                   } else {
                     none[Block].pure[F]
                   }
      info = makeBlockInfo(summary, maybeBlock)
    } yield (info, maybeBlock)

  def getBlockInfoWithBlock[F[_]: MonadThrowable: Log: MultiParentCasperRef: FinalityDetector: BlockStorage](
      blockHash: BlockHash,
      full: Boolean = false
  ): F[(BlockInfo, Option[Block])] =
    unsafeWithCasper[F, (BlockInfo, Option[Block])]("Could not show block.") { implicit casper =>
      BlockStorage[F].getBlockSummary(blockHash).flatMap { maybeSummary =>
        maybeSummary.fold(
          MonadThrowable[F]
            .raiseError[(BlockInfo, Option[Block])](
              NotFound(s"Cannot find block matching hash ${Base16.encode(blockHash.toByteArray)}")
            )
        )(makeBlockInfo[F](_, full))
      }
    }

  def getBlockInfoOpt[F[_]: MonadThrowable: Log: BlockStorage: MultiParentCasperRef: FinalityDetector](
      blockHashBase16: String,
      full: Boolean = false
  ): F[Option[(BlockInfo, Option[Block])]] =
    unsafeWithCasper[F, Option[(BlockInfo, Option[Block])]]("Could not show block") {
      implicit casper =>
        BlockStorage[F]
          .getSummaryByPrefix(blockHashBase16)
          .flatMap(
            _.fold(none[(BlockInfo, Option[Block])].pure[F])(
              makeBlockInfo[F](_, full).map(_.some)
            )
          )
    }

  def getBlockInfo[F[_]: MonadThrowable: Log: MultiParentCasperRef: FinalityDetector: BlockStorage](
      blockHashBase16: String,
      full: Boolean = false
  ): F[BlockInfo] =
    getBlockInfoOpt[F](blockHashBase16, full).flatMap(
      _.fold(
        MonadThrowable[F]
          .raiseError[BlockInfo](
            NotFound(s"Cannot find block matching hash $blockHashBase16")
          )
      )(_._1.pure[F])
    )

  /** Return block infos and maybe according blocks (if 'full' is true) in the a slice of the DAG.
    * Use `maxRank` 0 to get the top slice,
    * then we pass previous ranks to paginate backwards. */
  def getBlockInfosMaybeWithBlocks[F[_]: MonadThrowable: Log: MultiParentCasperRef: FinalityDetector: BlockStorage: Fs2Compiler](
      depth: Int,
      maxRank: Long = 0,
      full: Boolean = false
  ): F[List[(BlockInfo, Option[Block])]] =
    unsafeWithCasper[F, List[(BlockInfo, Option[Block])]]("Could not show blocks.") {
      implicit casper =>
        casper.dag flatMap { dag =>
          maxRank match {
            case 0 => dag.topoSortTail(depth).compile.toVector
            case r =>
              dag
                .topoSort(endBlockNumber = r, startBlockNumber = math.max(r - depth + 1, 0))
                .compile
                .toVector
          }
        } handleErrorWith {
          case ex: StorageError =>
            MonadThrowable[F].raiseError(InvalidArgument(StorageError.errorMessage(ex)))
          case ex: IllegalArgumentException =>
            MonadThrowable[F].raiseError(InvalidArgument(ex.getMessage))
        } flatMap { summariesByRank =>
          summariesByRank.flatten.reverse.toList.traverse(makeBlockInfo[F](_, full))
        }
    }

  /** Return block infos in the a slice of the DAG. Use `maxRank` 0 to get the top slice,
    * then we pass previous ranks to paginate backwards. */
  def getBlockInfos[F[_]: MonadThrowable: Log: MultiParentCasperRef: FinalityDetector: BlockStorage: Fs2Compiler](
      depth: Int,
      maxRank: Long = 0,
      full: Boolean = false
  ): F[List[BlockInfo]] =
    getBlockInfosMaybeWithBlocks[F](depth, maxRank, full).map(_.map(_._1))
}
