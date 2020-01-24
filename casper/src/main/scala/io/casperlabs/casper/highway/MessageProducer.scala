package io.casperlabs.casper.highway

import cats._
import cats.data.NonEmptyList
import cats.implicits._
import cats.effect.{Clock, Concurrent}
import cats.effect.concurrent.Semaphore
import com.google.protobuf.ByteString
import io.casperlabs.casper.{DeployFilters, Estimator, ValidatorIdentity}
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.catscontrib.{MonadThrowable}
import io.casperlabs.catscontrib.effect.implicits.fiberSyntax
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.models.Message
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.{BlockHash, BlockMsgWithTransform}
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageReader}
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{MergeResult, TransformMap}
import io.casperlabs.casper.util.{CasperLabsProtocol, DagOperations, ProtoUtil}
import io.casperlabs.casper.util.ProtocolVersions.Config
import io.casperlabs.shared.Log
import io.casperlabs.shared.Sorting.byteStringOrdering
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.ipc

/** Produce a signed message, persisted message.
  * The producer should the thread safe, so that when it's
  * called from multiple threads to produce ballots in response
  * to different messages it doesn't create an equivocation.
  */
trait MessageProducer[F[_]] {
  def validatorId: PublicKeyBS

  def ballot(
      keyBlockHash: BlockHash,
      roundId: Ticks,
      target: BlockHash,
      // For lambda responses we want to limit the justifications to just direct ones.
      justifications: Map[PublicKeyBS, Set[BlockHash]]
  ): F[Message.Ballot]

  /** Pick whatever secondary parents are compatible with the chosen main parent
    * and the justifications selected when the caller started their operation,
    * select deploys from the buffer, and create a (possibly empty) block,
    * persisting it to the block store.
    */
  def block(
      keyBlockHash: BlockHash,
      roundId: Ticks,
      mainParent: BlockHash,
      justifications: Map[PublicKeyBS, Set[BlockHash]],
      isBookingBlock: Boolean
  ): F[Message.Block]
}

object MessageProducer {
  def apply[F[_]: Concurrent: Clock: Log: Metrics: DagStorage: BlockStorage: DeployBuffer: DeployStorage: EraStorage: CasperLabsProtocol: ExecutionEngineService: DeploySelection](
      validatorIdentity: ValidatorIdentity,
      chainName: String,
      upgrades: Seq[ipc.ChainSpec.UpgradePoint]
  ): MessageProducer[F] =
    new MessageProducer[F] {
      override val validatorId =
        PublicKey(ByteString.copyFrom(validatorIdentity.publicKey))

      override def ballot(
          keyBlockHash: BlockHash,
          roundId: Ticks,
          target: BlockHash,
          justifications: Map[PublicKeyBS, Set[BlockHash]]
      ): F[Message.Ballot] =
        for {
          parent        <- BlockStorage[F].getBlockSummaryUnsafe(target)
          parentMessage <- MonadThrowable[F].fromTry(Message.fromBlockSummary(parent))
          props         <- messageProps(keyBlockHash, List(parentMessage), justifications)
          timestamp     <- Clock[F].currentTimeMillis

          signed = ProtoUtil.ballot(
            props.justifications,
            parent.getHeader.getState.postStateHash,
            parent.getHeader.getState.bonds,
            props.protocolVersion,
            parent.blockHash,
            props.validatorSeqNum,
            props.validatorPrevBlockHash,
            chainName,
            timestamp,
            props.rank,
            validatorIdentity.publicKey,
            validatorIdentity.privateKey,
            validatorIdentity.signatureAlgorithm,
            keyBlockHash,
            roundId
          )

          message <- MonadThrowable[F].fromTry(Message.fromBlock(signed))

          record = BlockMsgWithTransform()
            .withBlockMessage(signed)

          _ <- BlockStorage[F].put(record)

        } yield message.asInstanceOf[Message.Ballot]

      override def block(
          keyBlockHash: BlockHash,
          roundId: Ticks,
          mainParent: BlockHash,
          justifications: Map[PublicKeyBS, Set[BlockHash]],
          isBookingBlock: Boolean
      ): F[Message.Block] =
        for {
          dag          <- DagStorage[F].getRepresentation
          merged       <- selectParents(dag, keyBlockHash, mainParent, justifications)
          parentHashes = merged.parents.map(_.blockHash).toSet
          _            <- startRequeueingOrphanedDeploys(parentHashes)
          parentMessages <- MonadThrowable[F].fromTry {
                             merged.parents.toList.traverse(Message.fromBlock(_))
                           }
          props     <- messageProps(keyBlockHash, parentMessages, justifications)
          timestamp <- Clock[F].currentTimeMillis

          remainingHashes <- DeployBuffer.remainingDeploys[F](
                              dag,
                              parentHashes,
                              timestamp,
                              props.configuration.deployConfig
                            )

          deployStream = DeployStorageReader[F]
            .getByHashes(remainingHashes)
            .through(
              DeployFilters.Pipes.dependenciesMet[F](dag, parentHashes)
            )

          checkpoint <- ExecEngineUtil
                         .computeDeploysCheckpoint[F](
                           merged,
                           deployStream,
                           timestamp,
                           props.protocolVersion,
                           props.rank,
                           upgrades
                         )

          signed = ProtoUtil.block(
            props.justifications,
            checkpoint.preStateHash,
            checkpoint.postStateHash,
            checkpoint.bondedValidators,
            checkpoint.deploysForBlock,
            props.protocolVersion,
            merged.parents.map(_.blockHash),
            props.validatorSeqNum,
            props.validatorPrevBlockHash,
            chainName,
            timestamp,
            props.rank,
            validatorIdentity.publicKey,
            validatorIdentity.privateKey,
            validatorIdentity.signatureAlgorithm,
            keyBlockHash,
            roundId
          )

          message <- MonadThrowable[F].fromTry(Message.fromBlock(signed))

          record = BlockMsgWithTransform()
            .withBlockMessage(signed)
            .withTransformEntry(checkpoint.transformMap)

          _ <- BlockStorage[F].put(record)

        } yield message.asInstanceOf[Message.Block]

      // NOTE: Currently this will requeue deploys in the background, some will make it, some won't.
      // This made sense with the AutoProposer, since a new block could be proposed any time;
      // in Highway that's going to the next round, whenever that is.
      private def startRequeueingOrphanedDeploys(parentHashes: Set[BlockHash]): F[Unit] = {
        DeployBuffer.requeueOrphanedDeploys[F](parentHashes) >>= { requeued =>
          Log[F].info(s"Re-queued $requeued orphaned deploys.").whenA(requeued > 0)
        }
      }.forkAndLog

      private def messageProps(
          keyBlockHash: BlockHash,
          parents: Seq[Message],
          justifications: Map[PublicKeyBS, Set[BlockHash]]
      ): F[MessageProps] =
        // NCB used to filter justifications to be only the bonded ones.
        // In Highway, we must include the justifications of the parent era _when_ there's a
        // new message that that we haven't included before. Transitive elimination should
        // take care of it, eventually, when it's implemented.
        for {
          // NOTE: The validator sequence number restarts in each era, and `justifications`
          // can contain entries for the parent era as well as the child.
          justificationMessages <- justifications.values.flatten.toList.traverse { h =>
                                    BlockStorage[F]
                                      .getBlockSummaryUnsafe(h)
                                      .flatMap { s =>
                                        MonadThrowable[F].fromTry(Message.fromBlockSummary(s))
                                      }
                                  }
          // Find the latest justification we picked. We must make sure they don't change
          // due to concurrency between the time the justifications are collected and
          // the sequence number is calculated, otherwise we could be equivocating.
          // This, currently, is catered for by a Semaphore in the EraRuntime, which
          // spans the fork choice as well as the block production.
          ownLatests = justificationMessages.filter { j =>
            j.validatorId == validatorId && j.keyBlockHash == keyBlockHash
          }
          maybeOwnLatest = Option(ownLatests)
            .filterNot(_.isEmpty)
            .map(_.maxBy(_.validatorMsgSeqNum))

          validatorSeqNum        = maybeOwnLatest.fold(1)(_.validatorMsgSeqNum + 1)
          validatorPrevBlockHash = maybeOwnLatest.fold(ByteString.EMPTY)(_.messageHash)

          // Genesis is for example not part of the justifications, so to be safe include parents too.
          rank            = ProtoUtil.nextRank(parents ++ justificationMessages)
          config          <- CasperLabsProtocol[F].configAt(rank)
          protocolVersion <- CasperLabsProtocol[F].versionAt(rank)
        } yield MessageProps(
          validatorSeqNum,
          validatorPrevBlockHash,
          rank,
          config,
          protocolVersion,
          ProtoUtil.toJustification(justificationMessages)
        )
    }

  case class MessageProps(
      validatorSeqNum: Int,
      validatorPrevBlockHash: BlockHash,
      rank: Long,
      configuration: Config,
      protocolVersion: ProtocolVersion,
      justifications: Seq[Justification]
  )

  /** Pick secondary parents that don't conflict with the already chosen fork choice. */
  def selectParents[F[_]: MonadThrowable: Metrics: BlockStorage: DagStorage: EraStorage](
      dag: DagRepresentation[F],
      keyBlockHash: BlockHash,
      mainParent: BlockHash,
      justifications: Map[PublicKeyBS, Set[BlockHash]]
  ): F[MergeResult[TransformMap, Block]] =
    for {
      latestMessages <- NonEmptyList(mainParent, justifications.values.flatten.toList).pure[F]
      tips           <- Estimator.tipsOfLatestMessages[F](dag, latestMessages, stopHash = keyBlockHash)
      equivocators   <- getEquivocators[F](keyBlockHash)
      // TODO: There are no scores here for ordering secondary parents.
      secondaries = tips
        .filterNot(m => equivocators(m.validatorId) || m.messageHash == mainParent)
        .map(_.messageHash)
        .sorted
      candidates = NonEmptyList(mainParent, secondaries)
      blocks     <- candidates.traverse(BlockStorage[F].getBlockUnsafe)
      merged     <- ExecEngineUtil.merge[F](blocks, dag)
    } yield merged

  /** Gather all the equivocators that are in eras between the era of the key block and the era
    * defined _by_ the keyblock itself (typically that means grandparent, parent and child era).
    */
  def getEquivocators[F[_]: MonadThrowable: EraStorage: DagStorage](
      keyBlockHash: BlockHash
  ): F[Set[ByteString]] =
    for {
      dag      <- DagStorage[F].getRepresentation
      keyBlock <- dag.lookupUnsafe(keyBlockHash)

      keyBlockHashes <- DagOperations
                         .bfTraverseF(List(keyBlockHash)) { h =>
                           EraStorage[F].getEraUnsafe(h).map(e => List(e.parentKeyBlockHash))
                         }
                         .takeUntil(_ == keyBlock.keyBlockHash)
                         .toList

      equivocatorsPerEra <- keyBlockHashes.traverse { h =>
                             dag.latestInEra(h) >>= (_.getEquivocators)
                           }
      equivocators = equivocatorsPerEra.flatten.toSet
    } yield equivocators
}
