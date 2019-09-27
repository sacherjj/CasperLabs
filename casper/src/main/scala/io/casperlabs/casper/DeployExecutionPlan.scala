package io.casperlabs.casper

import cats.implicits._

import com.google.protobuf.ByteString

import io.casperlabs.blockstorage.{BlockStorage, DagRepresentation}
import io.casperlabs.casper.consensus.Block
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
object DeployExecutionPlan {
  def chooseDeploys[F[_]: MonadThrowable: Fs2Compiler: DeployBuffer: BlockStorage](
      dag: DagRepresentation[F],
      parents: Seq[Block],
      timestamp: Long
  ): F[Set[DeployHash]] =
    DeployBuffer[F].readPendingHashesAndHeaders
      .filter { // can only pick deploys where we are in their TTL window
        case (_, header) =>
          val ttl = ProtoUtil.getTimeToLive(header, MAX_TTL)
          header.timestamp <= timestamp && timestamp <= (header.timestamp + ttl)
      }
      .evalMap { // can only pick deploys where all the dependencies are in the past
        case (hash, header) =>
          val dependencies = header.dependencies.toList
          val unmetDependenciesF =
            if (dependencies.nonEmpty)
              filterDeploysNotInPast(dag, parents, dependencies)
            else List.empty[ByteString].pure[F]

          unmetDependenciesF.map { unmetDependencies =>
            if (unmetDependencies.isEmpty) hash.some
            else none[DeployHash]
          }
      }
      .compile
      .toList
      .flatMap { hashes =>
        // can only pick deploys that are not already in the DAG
        filterDeploysNotInPast(dag, parents, hashes.flatten)
      }
      .map(_.toSet)

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
