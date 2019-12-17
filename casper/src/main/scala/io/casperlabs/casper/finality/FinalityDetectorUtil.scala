package io.casperlabs.casper.finality

import cats.Monad
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.DagRepresentation

import scala.collection.mutable.{IndexedSeq => MutableSeq}

object FinalityDetectorUtil {

  /**
    * Finds latest block per each validator as seen in the j-past-cone of a given block.
    * The search is however restricted to given subset of validators.
    *
    * Caution 1: For some validators there may be no blocks visible in j-past-cone(block).
    *            Hence the resulting map will not contain such validators.
    * Caution 2: the j-past-cone(b) includes block b, therefore if validators
    *            contains b.creator then the resulting map will include
    *            the entry b.creator ---> b
    *
    * TODO optimize it: when bonding new validator, it need search back to genesis
    *
    * @param dag
    * @param block
    * @param validators
    * @return
    */
  private[casper] def panoramaOfBlockByValidators[F[_]: Monad](
      dag: DagRepresentation[F],
      block: Message,
      validators: Set[Validator]
  ): F[Map[Validator, Message]] = {
    implicit val blockTopoOrdering: Ordering[Message] =
      DagOperations.blockTopoOrderingDesc

    val stream = DagOperations.bfToposortTraverseF(List(block)) { b =>
      b.justifications.toList
        .traverse(justification => {
          dag.lookup(justification.latestBlockHash)
        })
        .map(_.flatten)
    }

    stream
      .foldWhileLeft((validators, Map.empty[Validator, Message])) {
        case ((remainingValidators, acc), b) =>
          if (remainingValidators.isEmpty) {
            // Stop traversal if all validators find its latest block
            Right((remainingValidators, acc))
          } else if (remainingValidators.contains(b.validatorId)) {
            Left(
              (
                remainingValidators - b.validatorId,
                acc + (b.validatorId -> b)
              )
            )
          } else {
            Left((remainingValidators, acc))
          }
      }
      .map(_._2)
  }

  private[casper] def panoramaDagLevelsOfBlock[F[_]: Monad](
      blockDag: DagRepresentation[F],
      block: Message,
      validators: Set[Validator]
  ): F[Map[Validator, Level]] =
    panoramaOfBlockByValidators(blockDag, block, validators)
      .map(_.mapValues(_.rank))

  /**
    * Get level zero messages of the specified validator and specified candidateBlock
    */
  private[casper] def levelZeroMsgsOfValidator[F[_]: Monad](
      dag: DagRepresentation[F],
      validator: Validator,
      candidateBlockHash: BlockHash
  ): F[List[Message]] =
    dag.latestMessage(validator).map(s => if (s.size > 1) None else s.headOption).flatMap {
      case Some(latestMsgByValidator) =>
        DagOperations
          .bfTraverseF[F, Message](List(latestMsgByValidator))(
            previousAgreedBlockFromTheSameValidator(
              dag,
              _,
              candidateBlockHash,
              validator
            )
          )
          .toList
      case None => List.empty[Message].pure[F]
    }

  /*
   * Traverses back the j-DAG of `block` (one step at a time), following `validator`'s blocks
   * and collecting them as long as they are descendants of the `candidateBlockHash`.
   */
  private[casper] def previousAgreedBlockFromTheSameValidator[F[_]: Monad](
      dag: DagRepresentation[F],
      block: Message,
      candidateBlockHash: BlockHash,
      validator: Validator
  ): F[List[Message]] = {
    // Assumes that validator always includes his last message as justification.
    val previousHashO = block.justifications
      .find(
        _.validatorPublicKey == validator
      )
      .map(_.latestBlockHash)

    previousHashO match {
      case Some(previousHash) =>
        ProtoUtil
          .isInMainChain[F](dag, candidateBlockHash, previousHash)
          .flatMap[List[Message]](
            isActiveVote =>
              // If parent block of `block` is not in the main chain of `candidateBlockHash`
              // we don't include it in the set of level-0 messages.
              if (isActiveVote) dag.lookup(previousHash).map(_.toList)
              else List.empty[Message].pure[F]
          )
      case None =>
        List.empty[Message].pure[F]
    }
  }

  /**
    * Find the panorama of M and represent this panorama as an array
    * panoramaM, putting (for j=0,1,2,..., n-1)
    * panoramaM(j) := daglevel(latest message of V(j) seen from block M)
    * When V(j)-swimlane is empty or V(j) happens to be an equivocator - so in cases
    * where latest message of V(i) is not well defined concept - in these cases
    * put 0 (zero) in the corresponding cell.
    */
  private[casper] def panoramaM[F[_]: Monad](
      dag: DagRepresentation[F],
      validatorsToIndex: Map[Validator, Int],
      blockSummary: Message
  ): F[MutableSeq[Level]] =
    for {
      equivocators <- dag.getEquivocators
      latestBlockDagLevelAsMap <- FinalityDetectorUtil
                                   .panoramaDagLevelsOfBlock(
                                     dag,
                                     blockSummary,
                                     validatorsToIndex.keySet
                                   )
    } yield fromMapToArray(
      validatorsToIndex,
      validator => {
        // When V(j) happens to be an equivocator, put 0L in the corresponding cell
        if (equivocators.contains(validator)) {
          0L
        } else {
          // When V(j)-swimlane is empty, put 0L in the corresponding cell
          latestBlockDagLevelAsMap.getOrElse(validator, 0L)
        }
      }
    )
  // Returns an MutableSeq, whose size equals the size of validatorsToIndex and
  // For v in validatorsToIndex.key
  //   Arr[validatorsToIndex[v]] = mapFunction[v]
  def fromMapToArray[A](
      validatorsToIndex: Map[Validator, Int],
      mapFunction: Validator => A
  ): MutableSeq[A] =
    validatorsToIndex
      .map {
        case (v, i) =>
          (i, mapFunction(v))
      }
      .toArray[(Int, A)]
      .sortBy(_._1)
      .map(_._2)

  /** Returns a set of blocks that were finalized indirectly when a block from the main chain is finalized. */
  def finalizedIndirectly[F[_]: Sync](
      block: BlockHash,
      finalizedSoFar: Set[BlockHash],
      dag: DagRepresentation[F]
  ): F[Set[BlockHash]] =
    for {
      finalizedBlocksCache <- Ref[F].of(finalizedSoFar)
      finalizedImplicitly <- DagOperations
                              .bfTraverseF[F, BlockHash](List(block))(
                                hash =>
                                  for {
                                    finalizedBlocks <- finalizedBlocksCache.get
                                    notFinalized <- dag
                                                     .lookupUnsafe(hash)
                                                     .map(
                                                       _.parents.filterNot(finalizedBlocks.contains)
                                                     )
                                    _ <- finalizedBlocksCache.update(_ ++ notFinalized)
                                  } yield notFinalized.toList
                              )
                              .toList
    } yield finalizedImplicitly.toSet - block // We don't want to include `block`.
}
