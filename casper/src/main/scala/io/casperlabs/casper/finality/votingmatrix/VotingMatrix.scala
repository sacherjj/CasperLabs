package io.casperlabs.casper.finality.votingmatrix

import cats.Monad
import cats.effect.concurrent.Ref
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import cats.mtl.{DefaultMonadState, MonadState}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.finality.FinalityDetectorUtil
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.models.Message.{JRank, MainRank}
import io.casperlabs.storage.dag.{AncestorsStorage, DagRepresentation}
import scala.collection.mutable.{IndexedSeq => MutableSeq}

object VotingMatrix {
  // (Consensus value, DagLevel of the block)
  type Vote               = (BlockHash, JRank)
  type VotingMatrix[F[_]] = MonadState[F, VotingMatrixState]

  private[votingmatrix] def of[F[_]: Sync](
      votingMatrixState: VotingMatrixState
  ): F[VotingMatrix[F]] =
    Ref[F]
      .of(votingMatrixState)
      .map(
        state =>
          new DefaultMonadState[F, VotingMatrixState] {
            val monad: cats.Monad[F]               = implicitly[Monad[F]]
            def get: F[VotingMatrixState]          = state.get
            def set(s: VotingMatrixState): F[Unit] = state.set(s)
          }
      )

  /** Creates a new voting matrix basing new finalized block.
    */
  private[votingmatrix] def create[F[_]: Concurrent: AncestorsStorage](
      dag: DagRepresentation[F],
      lfbHash: BlockHash,
      isHighway: Boolean
  ): F[VotingMatrix[F]] =
    for {
      // Start a new round, get weightMap and validatorSet from the post-global-state of new finalized block's
      lfb <- dag.lookupUnsafe(lfbHash)
      block <- lfb match {
                case b: Message.Block => b.pure[F]
                case ballot: Message.Ballot =>
                  MonadThrowable[F].raiseError[Message.Block](
                    new IllegalArgumentException(
                      s"Cannot create an instance of VotingMatrix from a ballot ${PrettyPrinter
                        .buildString(ballot.messageHash)}"
                    )
                  )
              }
      weights    = block.weightMap
      validators = weights.keySet.toArray
      // Assigns numeric identifiers 0, ..., N-1 to all validators
      validatorsToIndex = validators.zipWithIndex.toMap
      n                 = validators.size
      latestMessagesOfHonestVoters <- if (!isHighway)
                                       dag.latestMessagesHonestValidators.map(_.toSeq)
                                     else {
                                       // Get the honest votes in eras that vote on a child of the LFB.
                                       for {
                                         childHashes <- dag.getMainChildren(lfbHash)
                                         childMessages <- childHashes.toList.traverse(
                                                           dag.lookupUnsafe(_)
                                                         )
                                         childEraIds = childMessages
                                           .filter(_.isBlock)
                                           .map(_.eraId)
                                           .distinct
                                         honestVoters <- childEraIds.traverse { eraId =>
                                                          dag
                                                            .latestInEra(eraId)
                                                            .flatMap(
                                                              _.latestMessagesHonestValidators
                                                            )
                                                        }
                                       } yield honestVoters.map(_.toSeq).flatten
                                     }
      // On which child of LFB validators vote on.
      voteOnLFBChild <- latestMessagesOfHonestVoters.toList
                         .traverse {
                           case (v, b) =>
                             io.casperlabs.casper.finality
                               .votedBranch[F](
                                 dag,
                                 lfbHash,
                                 b
                               )
                               .map {
                                 _.map(
                                   branch => (v, branch)
                                 )
                               }
                         }
                         .map(_.flatten)
      // Traverse down the swim lane of V(i) to find the earliest block voting
      // for the same Fm's child as V(i) latest does.
      // This way we can initialize first-zero-level-messages(i).
      firstLevelZeroVotes <- voteOnLFBChild
                              .traverse {
                                case (v, voteValue) =>
                                  FinalityDetectorUtil
                                    .levelZeroMsgsOfValidator(dag, v, voteValue, isHighway)
                                    .map(
                                      _.lastOption
                                        .map(b => (v, (voteValue.messageHash, b.jRank)))
                                    )
                              }
                              .map(_.flatten.toMap)
      firstLevelZeroVotesArray = FinalityDetectorUtil.fromMapToArray(
        validatorsToIndex,
        firstLevelZeroVotes.get
      )
      latestMessagesToUpdated = latestMessagesOfHonestVoters.collect {
        case (v, m) if firstLevelZeroVotes.contains(v) && validatorsToIndex.contains(v) => m
      }
      state = VotingMatrixState(
        MutableSeq.fill(n, n)(Message.asJRank(0L)),
        firstLevelZeroVotesArray,
        validatorsToIndex,
        weights,
        validators
      )

      implicit0(votingMatrix: VotingMatrix[F]) <- of[F](state)
      // Apply the incremental update step to update voting matrix by taking M := V(i)latest
      _ <- latestMessagesToUpdated.toList.traverse { b =>
            for {
              panorama <- FinalityDetectorUtil
                           .panoramaOfBlockByValidators[F](dag, b, lfb, validators.toSet)
              _ <- updateVotingMatrixOnNewBlock[F](dag, b, panorama, isHighway)
            } yield ()
          }
    } yield votingMatrix
}
