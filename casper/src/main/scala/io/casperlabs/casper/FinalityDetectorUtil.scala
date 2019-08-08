package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}

object FinalityDetectorUtil {

  /*
   * Returns a list of validators whose latest messages are votes for `candidateBlockHash`.
   * i.e. checks whether latest blocks from these validators are in the main chain of `candidateBlockHash`.
   */
  private def getAgreeingValidators[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[List[Validator]] =
    weights.keys.toList.filterA { validator =>
      for {
        latestMessageHash <- dag
                              .latestMessageHash(
                                validator
                              )
        result <- latestMessageHash match {
                   case Some(b) =>
                     ProtoUtil.isInMainChain[F](
                       dag,
                       candidateBlockHash,
                       b
                     )
                   case _ => false.pure[F]
                 }
      } yield result
    }

  // To have a committee of half the total weight,
  // you need at least twice the weight of the maxWeightApproximation to be greater than the total weight.
  // If that is false, we don't need to compute best committee
  // as we know the value is going to be below 0 and thus useless for finalization.
  def committeeApproximation[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[Option[(List[Validator], Long)]] =
    for {
      committee              <- getAgreeingValidators(dag, candidateBlockHash, weights)
      totalWeight            = weights.values.sum
      maxWeightApproximation = committee.map(weights).sum
      result = if (2 * maxWeightApproximation > totalWeight) {
        Some((committee, maxWeightApproximation))
      } else {
        None
      }
    } yield result

  /**
		* Finds latest block per each validator as seen in the j-past-cone of a given block.
		* The search is however restricted to given subset of validators.
		*
		* Caution 1: For some validators there may be no blocks visible in j-past-cone(block). Hence the resulting map will not contain such validators.
		* Caution 2: the j-past-cone(b) includes block b, therefore if validatorsSubsetWeAreRestrictingTheSearchTo contains b.creator
		* then the resulting mapping will include the entry b.creator ---> b
		*
		* TODO optimize it: when bonding new validator, it need search back to genesis
		*
		* @param blockDag
		* @param block
		* @param validators
		* @return
		*/
  private def panoramaOfBlockByValidators[F[_]: Monad](
      blockDag: DagRepresentation[F],
      block: BlockMetadata,
      validators: Set[Validator]
  ): F[Map[Validator, BlockMetadata]] = {
    val stream = DagOperations.bfToposortTraverseF(List(block), ascending = false) { b =>
      b.justifications
        .traverse(justification => {
          blockDag.lookup(justification.latestBlockHash)
        })
        .map(_.flatten)
    }

    stream
      .foldWhileLeft((validators, Map.empty[Validator, BlockMetadata])) {
        case ((remainingValidators, acc), b) => {
          if (remainingValidators.isEmpty) {
            Right((remainingValidators, acc))
          } else if (remainingValidators.contains(b.validatorPublicKey)) {
            Left((remainingValidators - b.validatorPublicKey, acc + (b.validatorPublicKey -> b)))
          } else {
            Left((remainingValidators, acc))
          }
        }
      }
      .map(_._2)
  }

  def panoramaDagLevelsOfBlock[F[_]: Monad](
      blockDag: DagRepresentation[F],
      block: BlockMetadata,
      validators: Set[Validator]
  ): F[Map[Validator, Long]] =
    for {
      panorama <- panoramaOfBlockByValidators(blockDag, block, validators)
      result = validators
        .map(
          v =>
            if (v == block.validatorPublicKey)
              v -> block.rank
            else
              v -> panorama.get(v).fold(0L)(_.rank)
        )
        .toMap
    } yield result

  // Get level zero messages of the specified validator and specified candidateBlock
  def levelZeroMsgsOfValidator[F[_]: Monad](
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
  private def previousAgreedBlockFromTheSameValidator[F[_]: Monad](
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
}
