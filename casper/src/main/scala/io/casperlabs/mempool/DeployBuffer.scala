package io.casperlabs.mempool

import cats.implicits._
import io.casperlabs.casper.DeployFilters.filterDeploysNotInPast
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.{DeployFilters, DeployHash, PrettyPrinter}
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.ipc.ChainSpec.DeployConfig
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageReader, DeployStorageWriter}

import scala.concurrent.duration.FiniteDuration
import io.casperlabs.shared.Log
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}
import simulacrum.typeclass

@typeclass trait DeployBuffer[F[_]] {

  /** If a deploy is valid (according to node rules), adds is the deploy buffer.
    * Otherwise returns an error.
    */
  def addDeploy(d: Deploy): F[Either[Throwable, Unit]]
}

object DeployBuffer {
  implicit val DeployBufferMetricsSource: Metrics.Source =
    Metrics.Source(Metrics.BaseSource, "deploy_buffer")

  def create[F[_]: MonadThrowable: DeployStorage: Log](
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
          _ <- (deploy.getBody.session, deploy.getBody.payment) match {
                case (None, _) | (_, None) |
                    (Some(Deploy.Code(_, _, Deploy.Code.Contract.Empty)), _) |
                    (_, Some(Deploy.Code(_, _, Deploy.Code.Contract.Empty))) =>
                  illegal(s"Deploy was missing session and/or payment code.")
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

      override def addDeploy(d: Deploy): F[Either[Throwable, Unit]] =
        (for {
          _ <- validateDeploy(d)
          _ <- DeployStorageWriter[F].addAsPending(List(d))
          _ <- Log[F].info(s"Received ${PrettyPrinter.buildString(d) -> "deploy" -> null}")
        } yield ()).attempt
    }

  /** Returns deploys that are not present in the p-past-cone of chosen parents. */
  def remainingDeploys[F[_]: MonadThrowable: BlockStorage: DeployStorage: Metrics: Fs2Compiler](
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
      unexpiredList <- unexpired.map(_._1).compile.toList
      // Make sure pending deploys have never been processed in the past cone of the new parents.
      validDeploys <- DeployFilters
                       .filterDeploysNotInPast(dag, parents, unexpiredList)
                       .map(_.toSet)
                       .timer("remainingDeploys_filterDeploysNotInPast")
      // anything with timestamp earlier than now and not included in the valid deploys
      // can be discarded as a duplicate and/or expired deploy
      deploysToDiscard <- earlierPendingDeploys
                           .map(_._1)
                           .compile
                           .to[Set]
                           .map(_ diff validDeploys)
      _ <- DeployStorageWriter[F]
            .markAsDiscardedByHashes(deploysToDiscard.toList.map((_, "Duplicate or expired")))
            .whenA(deploysToDiscard.nonEmpty)
    } yield validDeploys
  }

  /** If another node proposed a block which orphaned something proposed by this node,
    * and we still have these deploys in the `processedDeploys` buffer then put them
    * back into the `pendingDeploys` so that the `AutoProposer` can pick them up again. */
  def requeueOrphanedDeploys[F[_]: MonadThrowable: DagStorage: BlockStorage: DeployStorage: Metrics](
      tips: Set[BlockHash]
  ): F[Int] =
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
      } yield orphanedDeploys.size
    }

  /** Remove deploys from the buffer which are included in blocks that are finalized.
    *
    * Those deploys won't be requeued anymore.
    */
  def removeFinalizedDeploys[F[_]: MonadThrowable: DeployStorage: BlockStorage: Log: Metrics](
      lfbs: Set[BlockHash]
  ): F[Unit] = Metrics[F].timer("removeFinalizedDeploys") {
    for {
      deployHashes <- DeployStorageReader[F].readProcessedHashes

      blockHashes <- BlockStorage[F]
                      .findBlockHashesWithDeployHashes(deployHashes)
                      .map(_.values.flatten.toList.distinct)

      finalizedBlockHashes = blockHashes.filter(lfbs.contains(_))
      _ <- finalizedBlockHashes.traverse { blockHash =>
            removeDeploysInBlock[F](blockHash) flatMap { removed =>
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
  private def removeDeploysInBlock[F[_]: MonadThrowable: DeployStorage: BlockStorage](
      blockHash: BlockHash
  ): F[Long] =
    for {
      block           <- ProtoUtil.unsafeGetBlock[F](blockHash)
      deploysToRemove = block.body.get.deploys.map(_.deploy.get).toList
      // NOTE: Do we really need this metric? It will make unncessary calls to the DB.
      initialHistorySize <- DeployStorageReader[F].sizePendingOrProcessed()
      _                  <- DeployStorageWriter[F].markAsFinalized(deploysToRemove)
      deploysRemoved <- DeployStorageReader[F]
                         .sizePendingOrProcessed()
                         .map(after => initialHistorySize - after)
    } yield deploysRemoved

}
