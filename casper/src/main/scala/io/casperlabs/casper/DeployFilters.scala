package io.casperlabs.casper

import cats.implicits._

import com.google.protobuf.ByteString

import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation

/**
  * Provides filters for dealing with streams of deploys. The intent is to
  * compose these filters when selecting deploys to include in a block.
  */
object DeployFilters {

  /**
    * Creates a function which returns true for deploys where `deploy.header.timestamp`
    * is less than or equal to the given timestamp.
    */
  def timestampBefore(timestamp: Long): Deploy.Header => Boolean = _.timestamp <= timestamp

  /**
    * Creates a function which returns true for deploys where
    * `deploy.header.timestamp + deploy.header.ttl_millis` is greater than or
    * equal to the given timestamp. I.e. this takes deploys that are not expired as of
    * the provided timestamp.
    */
  def notExpired(timestamp: Long, maxTTL: Int): Deploy.Header => Boolean = header => {
    val ttl = ProtoUtil.getTimeToLive(header, maxTTL)
    timestamp <= (header.timestamp + ttl)
  }

  /**
    * Creates a function which returns true for deploys where all
    * `deploy.header.dependencies` are contained in blocks in the
    * p-past-cone of one or more of the provided `parents`.
    */
  def dependenciesMet[F[_]: MonadThrowable: BlockStorage](
      dag: DagRepresentation[F],
      parents: Set[BlockHash]
  ): Deploy => F[Boolean] = { deploy =>
    val dependencies = deploy.getHeader.dependencies.toList

    if (dependencies.nonEmpty)
      filterDeploysNotInPast(dag, parents, dependencies).map(_.isEmpty)
    else true.pure[F]
  }

  object Pipes {
    def timestampBefore[F[_]](
        timestamp: Long
    ): fs2.Pipe[F, (DeployHash, Deploy.Header), (DeployHash, Deploy.Header)] =
      filter(tupleRight(DeployFilters.timestampBefore(timestamp)))

    def notExpired[F[_]](
        timestamp: Long,
        maxTTL: Int
    ): fs2.Pipe[F, (DeployHash, Deploy.Header), (DeployHash, Deploy.Header)] =
      filter(tupleRight(DeployFilters.notExpired(timestamp, maxTTL)))

    def dependenciesMet[F[_]: MonadThrowable: BlockStorage](
        dag: DagRepresentation[F],
        parents: Set[BlockHash]
    ): fs2.Pipe[F, Deploy, Deploy] =
      filterA(DeployFilters.dependenciesMet(dag, parents))

    def validMaxTtl[F[_]: MonadThrowable](
        maxTTL: Int
    ): fs2.Pipe[F, (DeployHash, Deploy.Header), (DeployHash, Deploy.Header)] =
      filter(tupleRight(d => d.ttlMillis < maxTTL))

    /**
      * Lifts a boolean function on A to one on (B, A) by acting only
      * on the right element of the tuple.
      */
    private def tupleRight[A, B](condition: A => Boolean): ((B, A)) => Boolean = {
      case (_, a) => condition(a)
    }

    /**
      * Convenience method for creating a Pipe from a boolean condition.
      * Retains only elements that meet the condition.
      */
    private def filter[F[_], A](condition: A => Boolean): fs2.Pipe[F, A, A] = _.filter(condition)

    /**
      * Convenience method for creating a Pipe from a boolean condition with effect.
      * Retains only elements that meet the condition.
      */
    private def filterA[F[_]: cats.Functor, A](condition: A => F[Boolean]): fs2.Pipe[F, A, A] =
      _.evalMap { a =>
        condition(a).map(result => if (result) a.some else none[A])
      }.unNone
  }

  /** Find deploys which either haven't been processed yet or are in blocks which are
    * not in the past cone of the chosen parents.
    */
  def filterDeploysNotInPast[F[_]: MonadThrowable: BlockStorage](
      dag: DagRepresentation[F],
      parents: Set[BlockHash],
      deployHashes: List[ByteString]
  ): F[List[ByteString]] =
    for {
      deployHashToBlocksMap <- BlockStorage[F].findBlockHashesWithDeployHashes(deployHashes)

      blockHashes = deployHashToBlocksMap.values.flatten.toList.distinct

      nonOrphanedBlockHashes <- DagOperations
                                 .collectWhereDescendantPathExists[F](
                                   dag,
                                   blockHashes.toSet,
                                   parents
                                 )

      deploysNotInPast = deployHashToBlocksMap.collect {
        case (deployHash, blockHashes)
            if blockHashes.isEmpty || !blockHashes.exists(nonOrphanedBlockHashes) =>
          deployHash
      }.toList

    } yield deploysNotInPast
}
