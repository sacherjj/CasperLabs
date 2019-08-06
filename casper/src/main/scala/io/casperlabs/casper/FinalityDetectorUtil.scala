package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import io.casperlabs.blockstorage.DagRepresentation
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.util.ProtoUtil

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
}
