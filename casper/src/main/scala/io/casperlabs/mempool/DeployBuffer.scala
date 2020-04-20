package io.casperlabs.mempool

import cats._
import cats.implicits._
import io.casperlabs.casper.DeployFilters.filterDeploysNotInPast
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.{DeployEventEmitter, DeployFilters, DeployHash, PrettyPrinter}
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.consensus.info.DeployInfo
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.ipc.ChainSpec.DeployConfig
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageReader, DeployStorageWriter}
import io.casperlabs.shared.ByteStringPrettyPrinter._
import io.casperlabs.shared.Log
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage, FinalityStorage}
import simulacrum.typeclass
import scala.concurrent.duration.FiniteDuration

@typeclass trait DeployBuffer[F[_]] {

  /** If a deploy is valid (according to node rules), adds is the deploy buffer.
    * Otherwise returns an error.
    */
  def addDeploy(d: Deploy): F[Either[Throwable, Unit]]

  /** Returns deploys that are not present in the p-past-cone of chosen parents. */
  def remainingDeploys(
      dag: DagRepresentation[F],
      parents: Set[BlockHash],
      timestamp: Long,
      deployConfig: DeployConfig
  ): F[Set[DeployHash]]

  /** If another node proposed a block which orphaned something proposed by this node,
    * and we still have these deploys in the `processedDeploys` buffer then put them
    * back into the `pendingDeploys` so that the `AutoProposer` can pick them up again.
    *
    * This method requeues processed deploys not in the p-past cone of the tips. */
  def requeueOrphanedDeploys(
      tips: Set[BlockHash]
  ): F[Set[DeployHash]]

  /** This method requeues processed deploys in the given orphaned blocks. */
  def requeueOrphanedDeploysInBlocks(
      orphanedBlockHashes: Set[BlockHash]
  ): F[Set[DeployHash]]

  /** Remove deploys from the buffer which are included in blocks that are finalized.
    * Those deploys won't be requeued anymore. */
  def removeFinalizedDeploys(
      lfbs: Set[BlockHash]
  ): F[Unit]

  /** Discard deploys and emit the necessary events. */
  def discardDeploys(
      deploysWithReasons: List[(DeployHash, String)]
  ): F[Unit]

  /** Discard deploys that sat in the buffer for too long. */
  def discardExpiredDeploys(
      expirationPeriod: FiniteDuration
  ): F[Unit]
}

object DeployBuffer {
  implicit val DeployBufferMetricsSource: Metrics.Source =
    Metrics.Source(Metrics.BaseSource, "deploy_buffer")

  def create[F[_]: MonadThrowable: Fs2Compiler: Log: Metrics: DeployStorage: BlockStorage: DagStorage: FinalityStorage: DeployEventEmitter](
      chainName: String,
      minTtl: FiniteDuration
  ): DeployBuffer[F] =
    new DeployBuffer[F] {

      private def validateDeploy(deploy: Deploy): F[Unit] = {
        def illegal(msg: String): F[Unit] =
          MonadThrowable[F].raiseError(new IllegalArgumentException(msg))

        def check(msg: String)(f: F[Boolean]): F[Unit] =
          f flatMap { ok =>
            illegal(msg).whenA(!ok)
          }

        for {
          _ <- deploy.getBody.session match {
                case None | Some(Deploy.Code(_, Deploy.Code.Contract.Empty)) =>
                  illegal(s"Deploy was missing session code.")
                case _ => ().pure[F]
              }
          _ <- check("Invalid deploy hash.")(Validation.deployHash[F](deploy))
          _ <- check("Invalid deploy signature.")(Validation.deploySignature[F](deploy))
          _ <- check("Invalid chain name.")(
                Validation.validateChainName[F](deploy, chainName).map(_.isEmpty)
              )
          _ <- check(
                s"Invalid deploy TTL. Deploy TTL: ${deploy.getHeader.ttlMillis} ms, minimum TTL: ${minTtl.toMillis}."
              )(Validation.minTtl[F](deploy, minTtl).map(_.isEmpty))
        } yield ()
      }

      override def addDeploy(
          d: Deploy
      ): F[Either[Throwable, Unit]] =
        (for {
          _ <- validateDeploy(d)
          _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(d) -> "deploy" -> null}")
          _ <- DeployStorageWriter[F].addAsPending(List(d))
          _ <- DeployEventEmitter[F].deployAdded(d)
        } yield ()).attempt

      override def remainingDeploys(
          dag: DagRepresentation[F],
          parents: Set[BlockHash],
          timestamp: Long,
          deployConfig: DeployConfig
      ): F[Set[DeployHash]] = Metrics[F].timer("remainingDeploys") {
        // We have re-queued orphan deploys already, so we can just look at pending ones.
        val earlierPendingDeploys = DeployStorageReader[F].readPendingHashesAndHeaders
          .through(DeployFilters.Pipes.timestampBefore[F](timestamp))
          .timer("timestampBeforeFilter")
        val unexpired = earlierPendingDeploys
          .through(
            DeployFilters.Pipes.notExpired[F](timestamp, deployConfig.maxTtlMillis)
          )
          .through(DeployFilters.Pipes.validMaxTtl[F](deployConfig.maxTtlMillis))
          .timer("notExpiredFilter")

        for {
          // We compile `earlierPendingDeploys` here to avoid a race condition where
          // the stream may be updated by receiving a new deploy after `unexpired`
          // has been compiled, causing some deploys to be erroneously marked as DISCARDED.
          earlierPendingSet <- earlierPendingDeploys.map(_._1).compile.to[Set]
          unexpiredList     <- unexpired.map(_._1).compile.toList
          // Make sure pending deploys have never been processed in the past cone of the new parents.
          validDeploys <- DeployFilters
                           .filterDeploysNotInPast(dag, parents, unexpiredList)
                           .map(_.toSet)
                           .timer("remainingDeploys_filterDeploysNotInPast")
          // anything with timestamp earlier than now and not included in the valid deploys
          // can be discarded as a duplicate and/or expired deploy
          deploysToDiscard = earlierPendingSet diff validDeploys
          _                <- discardDeploys(deploysToDiscard.toList.map(_ -> "Duplicate or expired"))
        } yield validDeploys
      }

      override def requeueOrphanedDeploys(
          tips: Set[BlockHash]
      ): F[Set[DeployHash]] =
        Metrics[F].timer("requeueOrphanedDeploys") {
          for {
            dag <- DagStorage[F].getRepresentation
            // Consider deploys which this node has processed but hasn't finalized yet.
            processedDeploys <- DeployStorageReader[F].readProcessedHashes
            orphanedDeploys <- filterDeploysNotInPast[F](
                                dag,
                                tips,
                                processedDeploys
                              ).timer("requeueOrphanedDeploys_filterDeploysNotInPast")
            _ <- DeployStorageWriter[F]
                  .markAsPendingByHashes(orphanedDeploys) whenA orphanedDeploys.nonEmpty
            _ <- DeployEventEmitter[F].deploysRequeued(orphanedDeploys)
          } yield orphanedDeploys.toSet
        }

      def requeueOrphanedDeploysInBlocks(
          orphanedBlockHashes: Set[BlockHash]
      ): F[Set[DeployHash]] =
        // Using the same timer so we don't have to update Grafana.
        // Only one of the variants is used, depending on mode.
        Metrics[F].timer("requeueOrphanedDeploys") {
          for {
            deployHashes <- orphanedBlockHashes.toList
                             .traverse { blockHash =>
                               DeployStorage[F]
                                 .reader(DeployInfo.View.BASIC)
                                 .getProcessedDeploys(blockHash)
                                 .map(_.map(_.getDeploy.deployHash))
                             }
                             .map(_.flatten)

            deployToBlocks <- BlockStorage[F].findBlockHashesWithDeployHashes(deployHashes)

            blockFinality <- deployToBlocks.values.flatten.toSet.toList
                              .traverse { blockHash =>
                                FinalityStorage[F].getFinalityStatus(blockHash).map(blockHash -> _)
                              }
                              .map(_.toMap)

            orphanedDeploys = deployToBlocks.collect {
              // If we have Finalized or Undecided blocks we don't have to requeue (yet).
              case (deployHash, blockHashes) if blockHashes.forall(blockFinality(_).isOrphaned) =>
                deployHash
            }.toList

            _ <- DeployStorageWriter[F]
                  .markAsPendingByHashes(orphanedDeploys) whenA orphanedDeploys.nonEmpty
            _ <- DeployEventEmitter[F].deploysRequeued(orphanedDeploys)
          } yield orphanedDeploys.toSet
        }

      override def removeFinalizedDeploys(
          lfbs: Set[BlockHash]
      ): F[Unit] = Metrics[F].timer("removeFinalizedDeploys") {
        for {
          deployHashes <- DeployStorageReader[F].readProcessedHashes

          blockHashes <- BlockStorage[F]
                          .findBlockHashesWithDeployHashes(deployHashes)
                          .map(_.values.flatten.toList.distinct)

          finalizedBlockHashes = blockHashes.filter(lfbs.contains(_))
          _ <- finalizedBlockHashes.traverse { blockHash =>
                removeDeploysInBlock(blockHash) flatMap { removed =>
                  Log[F]
                    .info(
                      s"Removed $removed deploys from deploy history as we finalized block ${PrettyPrinter
                        .buildString(blockHash)}"
                    )
                    .whenA(removed > 0L)
                }
              }
        } yield ()
      }

      /** Remove deploys from the history which are included in a just finalised block. */
      private def removeDeploysInBlock(
          blockHash: BlockHash
      ): F[Long] =
        for {
          block           <- ProtoUtil.unsafeGetBlock[F](blockHash)
          deploysToRemove = block.body.get.deploys.map(_.deploy.get).toList
          // NOTE: Do we really need this metric? It will make unncessary calls to the DB.
          initialHistorySize <- DeployStorageReader[F].countPendingOrProcessed
          _                  <- DeployStorageWriter[F].markAsFinalized(deploysToRemove)
          deploysRemoved <- DeployStorageReader[F].countPendingOrProcessed
                             .map(after => initialHistorySize - after)
        } yield deploysRemoved

      override def discardDeploys(
          deploysWithReasons: List[(DeployHash, String)]
      ): F[Unit] =
        for {
          _ <- DeployStorageWriter[F]
                .markAsDiscardedByHashes(deploysWithReasons)
                .whenA(deploysWithReasons.nonEmpty)
          _ <- DeployEventEmitter[F].deploysDiscarded(deploysWithReasons)
        } yield ()

      override def discardExpiredDeploys(
          expirationPeriod: FiniteDuration
      ): F[Unit] = {
        val msg = "TTL Expired"
        for {
          discarded <- DeployStorageWriter[F]
                        .markAsDiscarded(expirationPeriod, msg)
          _ <- DeployEventEmitter[F].deploysDiscarded(discarded.toList.map(_ -> msg))
        } yield ()
      }
    }
}
