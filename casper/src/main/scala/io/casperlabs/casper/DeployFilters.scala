package io.casperlabs.casper

import cats.implicits._

import com.google.protobuf.ByteString

import io.casperlabs.blockstorage.{BlockStorage, DagRepresentation}
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.casper.deploybuffer.DeployBuffer
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.casper.validation.ValidationImpl.MAX_TTL
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}

/**
  * This provides functions for selecting which deploys to attempt to execute
  * in a block. Note that they may not all be selected to be included in the block
  * (e.g. due to conflicts between effects or the block being too full). In the future
  * this will also provide the plan for which deploys to run sequentially, and which to
  * tun in parallel (though this feature does not yet exist).
  */
object DeployFilters {
  def timestampBefore[F[_]](
      timestamp: Long
  ): fs2.Pipe[F, (DeployHash, Deploy.Header), (DeployHash, Deploy.Header)] =
    _.filter {
      case (_, header) =>
        header.timestamp <= timestamp
    }

  def ttlAfter[F[_]](
      timestamp: Long
  ): fs2.Pipe[F, (DeployHash, Deploy.Header), (DeployHash, Deploy.Header)] =
    _.filter {
      case (_, header) =>
        val ttl = ProtoUtil.getTimeToLive(header, MAX_TTL)
        timestamp <= (header.timestamp + ttl)
    }

  def dependenciesMet[F[_]: MonadThrowable: BlockStorage](
      dag: DagRepresentation[F],
      parents: Seq[Block]
  ): fs2.Pipe[F, Deploy, Deploy] =
    _.evalMap { deploy =>
      val dependencies = deploy.getHeader.dependencies.toList
      val unmetDependenciesF =
        if (dependencies.nonEmpty)
          filterDeploysNotInPast(dag, parents, dependencies)
        else List.empty[ByteString].pure[F]

      unmetDependenciesF.map { unmetDependencies =>
        if (unmetDependencies.isEmpty) deploy.some
        else none[Deploy]
      }
    }.unNone

  /** Find deploys which either haven't been processed yet or are in blocks which are
    * not in the past cone of the chosen parents.
    */
  def filterDeploysNotInPast[F[_]: MonadThrowable: BlockStorage](
      dag: DagRepresentation[F],
      parents: Seq[Block],
      deployHashes: List[ByteString]
  ): F[List[ByteString]] =
    for {
      deployHashToBlocksMap <- deployHashes
                                .traverse { deployHash =>
                                  BlockStorage[F]
                                    .findBlockHashesWithDeployhash(deployHash)
                                    .map(deployHash -> _)
                                }
                                .map(_.toMap)

      blockHashes = deployHashToBlocksMap.values.flatten.toList.distinct

      // Find the blocks from which there's a way through the descendants to reach a tip.
      parentSet = parents.map(_.blockHash).toSet
      nonOrphanedBlockHashes <- DagOperations
                                 .collectWhereDescendantPathExists[F](
                                   dag,
                                   blockHashes.toSet,
                                   parentSet
                                 )

      deploysNotInPast = deployHashToBlocksMap.collect {
        case (deployHash, blockHashes)
            if blockHashes.isEmpty || !blockHashes.exists(nonOrphanedBlockHashes) =>
          deployHash
      }.toList

    } yield deploysNotInPast
}
