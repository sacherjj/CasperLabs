package io.casperlabs.casper.finality

import cats.Monad
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}

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
      block: BlockMetadata,
      validators: Set[Validator]
  ): F[Map[Validator, BlockMetadata]] = {
    implicit val blockTopoOrdering: Ordering[BlockMetadata] =
      DagOperations.blockTopoOrderingDesc

    val stream = DagOperations.bfToposortTraverseF(List(block)) { b =>
      b.justifications
        .traverse(justification => {
          dag.lookup(justification.latestBlockHash)
        })
        .map(_.flatten)
    }

    stream
      .foldWhileLeft((validators, Map.empty[Validator, BlockMetadata])) {
        case ((remainingValidators, acc), b) =>
          if (remainingValidators.isEmpty) {
            // Stop traversal if all validators find its latest block
            Right((remainingValidators, acc))
          } else if (remainingValidators.contains(b.validatorPublicKey)) {
            Left(
              (remainingValidators - b.validatorPublicKey, acc + (b.validatorPublicKey -> b))
            )
          } else {
            Left((remainingValidators, acc))
          }
      }
      .map(_._2)
  }

  private[casper] def panoramaDagLevelsOfBlock[F[_]: Monad](
      blockDag: DagRepresentation[F],
      block: BlockMetadata,
      validators: Set[Validator]
  ): F[Map[Validator, Long]] =
    panoramaOfBlockByValidators(blockDag, block, validators)
      .map(_.mapValues(_.rank))

  /**
    * Get level zero messages of the specified validator and specified candidateBlock
    */
  private[casper] def levelZeroMsgsOfValidator[F[_]: Monad](
      dag: DagRepresentation[F],
      validator: Validator,
      candidateBlockHash: BlockHash
  ): F[List[BlockMetadata]] =
    dag.latestMessage(validator).flatMap {
      case Some(latestMsgByValidator) =>
        DagOperations
          .bfTraverseF[F, BlockMetadata](List(latestMsgByValidator))(
            previousAgreedBlockFromTheSameValidator(
              dag,
              _,
              candidateBlockHash,
              validator
            )
          )
          .toList
      case None => List.empty[BlockMetadata].pure[F]
    }

  /*
   * Traverses back the j-DAG of `block` (one step at a time), following `validator`'s blocks
   * and collecting them as long as they are descendants of the `candidateBlockHash`.
   */
  private[casper] def previousAgreedBlockFromTheSameValidator[F[_]: Monad](
      dag: DagRepresentation[F],
      block: BlockMetadata,
      candidateBlockHash: BlockHash,
      validator: Validator
  ): F[List[BlockMetadata]] = {
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
          .flatMap[List[BlockMetadata]](
            isActiveVote =>
              // If parent block of `block` is not in the main chain of `candidateBlockHash`
              // we don't include it in the set of level-0 messages.
              if (isActiveVote) dag.lookup(previousHash).map(_.toList)
              else List.empty[BlockMetadata].pure[F]
          )
      case None =>
        List.empty[BlockMetadata].pure[F]
    }
  }

  private[casper] def panoramaM[F[_]: Monad](
      dag: DagRepresentation[F],
      validatorsToIndex: Map[Validator, Int],
      blockMetadata: BlockMetadata
  ): F[MutableSeq[Long]] =
    FinalityDetectorUtil
      .panoramaDagLevelsOfBlock(
        dag,
        blockMetadata,
        validatorsToIndex.keySet
      )
      .map(
        latestBlockDagLevelsAsMap =>
          // In cases where latest message of V(i) is not well defined, put 0L in the corresponding cell
          fromMapToArray(
            validatorsToIndex,
            latestBlockDagLevelsAsMap.getOrElse(_, 0L)
          )
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
}
