package io.casperlabs.casper.api

import cats.effect.concurrent.Semaphore
import cats.effect.{Bracket, Concurrent, Resource}
import cats.implicits._
import cats.{Functor, Monad}
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
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
import cats.Applicative
import io.casperlabs.casper.util.execengine.ProcessedDeployResult

object BlockAPI {

  // GraphQL can serve processed deploys, if asked for.
  type BlockAndMaybeDeploys = (BlockInfo, Option[List[Block.ProcessedDeploy]])

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

  def deploy[F[_]: MonadThrowable: MultiParentCasperRef: BlockStorage: Validation: Log: Metrics](
      d: Deploy
  ): F[Unit] = unsafeWithCasper[F, Unit]("Could not deploy.") { implicit casper =>
    for {
      _ <- Metrics[F].incrementCounter("deploys")
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

  def getDeployInfoOpt[F[_]: MonadThrowable: Log: MultiParentCasperRef: BlockStorage: DeployStorage](
      deployHashBase16: String,
      deployView: DeployInfo.View
  ): F[Option[DeployInfo]] =
    if (deployHashBase16.length != 64) {
      Log[F].warn("Deploy hash must be 32 bytes long") >> none[DeployInfo].pure[F]
    } else {
      val deployHash = ByteString.copyFrom(Base16.decode(deployHashBase16))
      DeployStorage[F].reader(deployView).getDeployInfo(deployHash)
    }

  def getDeployInfo[F[_]: MonadThrowable: Log: MultiParentCasperRef: BlockStorage: DeployStorage](
      deployHashBase16: String,
      deployView: DeployInfo.View
  ): F[DeployInfo] =
    getDeployInfoOpt[F](deployHashBase16, deployView).flatMap(
      _.fold(
        MonadThrowable[F]
          .raiseError[DeployInfo](NotFound.deploy(deployHashBase16))
      )(_.pure[F])
    )

  def getBlockDeploys[F[_]: Monad: BlockStorage: DeployStorage](
      blockHashBase16: String,
      deployView: DeployInfo.View
  ): F[List[Block.ProcessedDeploy]] =
    BlockStorage[F]
      .getBlockInfoByPrefix(blockHashBase16)
      .flatMap {
        _.fold(List.empty[Block.ProcessedDeploy].pure[F]) { info =>
          DeployStorage[F].reader(deployView).getProcessedDeploys(info.getSummary.blockHash)
        }
      }

  def getBlockInfoWithDeploys[F[_]: MonadThrowable: MultiParentCasperRef: BlockStorage: DeployStorage](
      blockHash: BlockHash,
      maybeDeployView: Option[DeployInfo.View]
  ): F[BlockAndMaybeDeploys] =
    for {
      blockInfo <- BlockStorage[F]
                    .getBlockInfo(blockHash)
                    .flatMap(
                      _.fold(
                        MonadThrowable[F]
                          .raiseError[BlockInfo](
                            NotFound.block(blockHash)
                          )
                      )(_.pure[F])
                    )
      withDeploys <- maybeWithDeploys[F](blockInfo, maybeDeployView)
    } yield withDeploys

  def getBlockInfoWithDeploysOpt[F[_]: Monad: BlockStorage: DeployStorage](
      blockHashBase16: String,
      maybeDeployView: Option[DeployInfo.View]
  ): F[Option[BlockAndMaybeDeploys]] =
    BlockStorage[F]
      .getBlockInfoByPrefix(blockHashBase16)
      .flatMap(
        _.traverse(
          maybeWithDeploys[F](_, maybeDeployView)
        )
      )

  private def maybeWithDeploys[F[_]: Applicative: DeployStorage](
      blockInfo: BlockInfo,
      maybeDeployView: Option[DeployInfo.View]
  ): F[BlockAndMaybeDeploys] =
    maybeDeployView.fold((blockInfo -> none[List[Block.ProcessedDeploy]]).pure[F]) { implicit dv =>
      DeployStorageReader[F]
        .getProcessedDeploys(blockInfo.getSummary.blockHash)
        .map { deploys =>
          blockInfo -> deploys.some
        }
    }

  def getBlockInfo[F[_]: MonadThrowable: Log: MultiParentCasperRef: BlockStorage: DeployStorage](
      blockHashBase16: String
  ): F[BlockInfo] =
    getBlockInfoWithDeploysOpt[F](blockHashBase16, None).flatMap(
      _.fold(
        MonadThrowable[F]
          .raiseError[BlockInfo](
            NotFound(s"Cannot find block matching hash $blockHashBase16")
          )
      )(_._1.pure[F])
    )

  /** Return block infos and maybe the corresponding deploy summaries in the a slice of the DAG.
    * Use `maxRank` 0 to get the top slice,
    * then we pass previous ranks to paginate backwards. */
  def getBlockInfosWithDeploys[F[_]: MonadThrowable: Log: MultiParentCasperRef: DeployStorage: Fs2Compiler](
      depth: Int,
      maxRank: Long = 0,
      maybeDeployView: Option[DeployInfo.View]
  ): F[List[BlockAndMaybeDeploys]] =
    unsafeWithCasper[F, List[BlockAndMaybeDeploys]]("Could not show blocks.") { implicit casper =>
      casper.dag flatMap { dag =>
        maxRank match {
          case 0 =>
            dag.topoSortTail(depth).compile.toVector
          case r =>
            dag
              .topoSort(
                endBlockNumber = r,
                startBlockNumber = math.max(r - depth + 1, 0)
              )
              .compile
              .toVector
        }
      } handleErrorWith {
        case ex: StorageError =>
          MonadThrowable[F].raiseError(InvalidArgument(StorageError.errorMessage(ex)))
        case ex: IllegalArgumentException =>
          MonadThrowable[F].raiseError(InvalidArgument(ex.getMessage))
      } flatMap { infosByRank =>
        infosByRank.flatten.reverse.toList.traverse { info =>
          maybeWithDeploys[F](info, maybeDeployView)
        }
      }
    }

  /** Return block infos in the a slice of the DAG. Use `maxRank` 0 to get the top slice,
    * then we pass previous ranks to paginate backwards. */
  def getBlockInfos[F[_]: MonadThrowable: Log: MultiParentCasperRef: DeployStorage: Fs2Compiler](
      depth: Int,
      maxRank: Long = 0
  ): F[List[BlockInfo]] =
    getBlockInfosWithDeploys[F](depth, maxRank, None).map(_.map(_._1))
}
