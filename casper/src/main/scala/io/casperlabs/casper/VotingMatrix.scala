package io.casperlabs.casper

import cats.Monad
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import cats.mtl.{DefaultMonadState, MonadState}
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.FinalityDetector.CommitteeWithConsensusValue
import io.casperlabs.casper.VotingMatrixImpl.{_votingMatrix, Vote, VotingMatrixState}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.MonadStateOps._
import simulacrum.typeclass

import scala.annotation.tailrec
import scala.collection.mutable.{IndexedSeq => MutableSeq}

@typeclass trait VotingMatrix[F[_]] {

  /**
    * Update voting matrix when a new block added to dag
    * @param dag
    * @param blockMetadata the new block
    * @param currentVoteValue which branch the new block vote for
    * @return
    */
  def updateVoterPerspective(
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata,
      currentVoteValue: BlockHash
  ): F[Unit]

  /**
    * Check whether provide branch should be finalized
    * @param dag the block dag
    * @param rFTT the relative fault tolerance threshold
    * @return
    */
  def checkForCommittee(
      dag: DagRepresentation[F],
      rFTT: Double
  ): F[Option[CommitteeWithConsensusValue]]

  /**
    * Start a new game when finalized a block, rebuild voting matrix
    * @param dag
    * @param newFinalizedBlock
    * @return
    */
  def rebuildFromLatestFinalizedBlock(
      dag: DagRepresentation[F],
      newFinalizedBlock: BlockHash
  ): F[Unit]
}

class VotingMatrixImpl[F[_]: _votingMatrix] extends VotingMatrix[F] {

  // VotingMatrixS is a MonadState but it "has" `Monad` (not "is").
  implicit val monad                          = VotingMatrixImpl._votingMatrix[F].monad
  val state: MonadState[F, VotingMatrixState] = VotingMatrixImpl._votingMatrix[F]
  val lock: Semaphore[F]                      = VotingMatrixImpl._votingMatrix[F]

  override def updateVoterPerspective(
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata,
      currentVoteValue: BlockHash
  ): F[Unit] =
    lock.withPermit(for {
      validatorToIndex <- (state >> 'validatorToIdx).get
      voter            = blockMetadata.validatorPublicKey
      _ <- if (!validatorToIndex.contains(voter)) {
            // The creator of block isn't from the validatorsSet
            // e.g. It is bonded after creating the latestFinalizedBlock
            ().pure[F]
          } else {
            for {
              _ <- updateVotingMatrixOnNewBlock(dag, blockMetadata)
              _ <- updateFirstZeroLevelVote(voter, currentVoteValue, blockMetadata.rank)
            } yield ()
          }
    } yield ())

  private def updateVotingMatrixOnNewBlock(
      dag: DagRepresentation[F],
      blockMetadata: BlockMetadata
  ): F[Unit] =
    for {
      validatorToIndex <- (state >> 'validatorToIdx).get
      panoramaM        <- panoramaM(dag, validatorToIndex, blockMetadata)
      // Replace row i in voting-matrix by panoramaM
      _ <- (state >> 'votingMatrix).modify(
            _.updated(validatorToIndex(blockMetadata.validatorPublicKey), panoramaM)
          )
    } yield ()

  private def updateFirstZeroLevelVote(
      validator: Validator,
      newVote: BlockHash,
      dagLevel: Long
  ): F[Unit] =
    for {
      firstLevelZeroMsgs <- (state >> 'firstLevelZeroVotes).get
      validatorToIndex   <- (state >> 'validatorToIdx).get
      voterIdx           = validatorToIndex(validator)
      firstLevelZeroVote = firstLevelZeroMsgs(voterIdx)
      _ <- firstLevelZeroVote match {
            case None =>
              (state >> 'firstLevelZeroVotes).modify(_.updated(voterIdx, Some((newVote, dagLevel))))
            case Some((prevVote, _)) if prevVote != newVote =>
              (state >> 'firstLevelZeroVotes).modify(_.updated(voterIdx, Some((newVote, dagLevel))))
            case Some((prevVote, _)) if prevVote == newVote =>
              ().pure[F]
          }
    } yield ()

  override def checkForCommittee(
      dag: DagRepresentation[F],
      rFTT: Double
  ): F[Option[CommitteeWithConsensusValue]] =
    lock.withPermit(for {
      weightMap                 <- (state >> 'weightMap).get
      totalWeight               = weightMap.values.sum
      quorum                    = math.ceil(totalWeight * (rFTT + 0.5)).toLong
      committeeApproximationOpt <- findCommitteeApproximation(quorum)
      result <- committeeApproximationOpt match {
                 case Some(
                     CommitteeWithConsensusValue(committeeApproximation, _, consensusValue)
                     ) =>
                   for {
                     matrix              <- (state >> 'votingMatrix).get
                     firstLevelZeroVotes <- (state >> 'firstLevelZeroVotes).get
                     validatorToIndex    <- (state >> 'validatorToIdx).get
                     weightMap           <- (state >> 'weightMap).get
                     mask                = fromMapToArray(validatorToIndex, committeeApproximation.contains)
                     weight              = fromMapToArray(validatorToIndex, weightMap.getOrElse(_, 0L))
                     committeeOpt = pruneLoop(
                       matrix,
                       firstLevelZeroVotes,
                       consensusValue,
                       mask,
                       quorum,
                       weight
                     )
                     committee = committeeOpt.map {
                       case (mask, totalWeight) =>
                         val committee = validatorToIndex.filter { case (_, i) => mask(i) }.keySet
                         CommitteeWithConsensusValue(committee, totalWeight, consensusValue)
                     }
                   } yield committee
                 case None =>
                   none[CommitteeWithConsensusValue].pure[F]
               }
    } yield result)

  /**
    * Phase 1 - finding most supported consensus value,
    * if its supporting vote larger than quorum, return it and its supporter
		* else return None
    * @param quorum
    * @return
    */
  private def findCommitteeApproximation(
      quorum: Long
  ): F[Option[CommitteeWithConsensusValue]] =
    for {
      weightMap           <- (state >> 'weightMap).get
      validators          <- (state >> 'validators).get
      firstLevelZeroVotes <- (state >> 'firstLevelZeroVotes).get
      // Get Map[VoteBranch, List[Validator]] directly from firstLevelZeroVotes
      consensusValueToValidators = firstLevelZeroVotes.zipWithIndex
        .collect { case (Some((blockHash, _)), idx) => (blockHash, validators(idx)) }
        .groupBy(_._1)
        .mapValues(_.map(_._2))
      // Get most support voteBranch and its support weight
      mostSupport = consensusValueToValidators
        .mapValues(_.map(weightMap.getOrElse(_, 0L)).sum)
        .maxBy(_._2)
      (voteValue, supportingWeight) = mostSupport
      // Get the voteBranch's supporters
      supporters = consensusValueToValidators(voteValue)
    } yield
      if (supportingWeight > quorum) {
        Some(CommitteeWithConsensusValue(supporters.toSet, supportingWeight, voteValue))
      } else {
        None
      }

  @tailrec
  private def pruneLoop(
      matrix: MutableSeq[MutableSeq[Long]],
      firstLevelZeroVotes: MutableSeq[Option[Vote]],
      candidateBlockHash: BlockHash,
      mask: MutableSeq[Boolean],
      q: Long,
      weight: MutableSeq[Long]
  ): Option[(MutableSeq[Boolean], Long)] = {
    val (newMask, prunedValidator, maxTotalWeight) = matrix.zipWithIndex
      .filter { case (_, rowIndex) => mask(rowIndex) }
      .foldLeft((mask, false, 0L)) {
        case ((newMask, prunedValidator, maxTotalWeight), (row, rowIndex)) =>
          val voteSum = row.zipWithIndex
            .filter { case (_, columnIndex) => mask(columnIndex) }
            .map {
              case (latestDagLevelSeen, columnIndex) =>
                firstLevelZeroVotes(columnIndex).fold(0L) {
                  case (consensusValue, dagLevelOf1stLevel0) =>
                    if (consensusValue == candidateBlockHash && dagLevelOf1stLevel0 <= latestDagLevelSeen)
                      weight(columnIndex)
                    else 0L
                }
            }
            .sum
          if (voteSum < q) {
            (newMask.updated(rowIndex, false), true, maxTotalWeight)
          } else {
            (newMask, prunedValidator, maxTotalWeight + weight(rowIndex))
          }
      }
    if (prunedValidator) {
      if (maxTotalWeight < q)
        // Terminate finality detection, finality is not reached yet.
        None
      else
        pruneLoop(matrix, firstLevelZeroVotes, candidateBlockHash, newMask, q, weight)
    } else {
      (mask, maxTotalWeight).some
    }
  }

  override def rebuildFromLatestFinalizedBlock(
      dag: DagRepresentation[F],
      newFinalizedBlock: BlockHash
  ): F[Unit] =
    lock.withPermit(for {
      // Start a new round, get weightMap and validatorSet from the post-global-state of new finalized block's
      weights    <- dag.lookup(newFinalizedBlock).map(_.get.weightMap)
      _          <- (state >> 'weightMap).set(weights)
      validators = weights.keySet.toArray
      _          <- (state >> 'validators).set(validators)
      n          = validators.size
      _          <- (state >> 'votingMatrix).set(MutableSeq.fill(n, n)(0))
      _          <- (state >> 'firstLevelZeroVotes).set(MutableSeq.fill(n)(None))
      // Assigns numeric identifiers 0, ..., N-1 to all validators
      validatorsToIndex      = validators.zipWithIndex.toMap
      _                      <- (state >> 'validatorToIdx).set(validatorsToIndex)
      latestMessages         <- dag.latestMessages
      latestMessagesOfVoters = latestMessages.filterKeys(validatorsToIndex.contains)
      latestVoteValueOfVotesAsList <- latestMessagesOfVoters.toList
                                       .traverse {
                                         case (v, b) =>
                                           ProtoUtil
                                             .votedBranch[F](
                                               dag,
                                               newFinalizedBlock,
                                               b.blockHash
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
      firstLevelZeroVotes <- latestVoteValueOfVotesAsList
                              .traverse {
                                case (v, voteValue) =>
                                  FinalityDetectorUtil
                                    .levelZeroMsgsOfValidator(dag, v, voteValue)
                                    .map(
                                      _.lastOption
                                        .map(b => (v, (b.blockHash, b.rank)))
                                    )
                              }
                              .map(_.flatten.toMap)
      firstLevelZeroVotesArray = fromMapToArray(
        validatorsToIndex,
        firstLevelZeroVotes.get
      )
      _ <- (state >> 'firstLevelZeroVotes).set(firstLevelZeroVotesArray)
      latestMessagesToUpdated = latestMessagesOfVoters.filterKeys(
        firstLevelZeroVotes.contains
      )
      // Apply the incremental update step to update voting matrix by taking M := V(i)latest
      _ <- latestMessagesToUpdated.values.toList.traverse { b =>
            updateVotingMatrixOnNewBlock(dag, b)
          }
    } yield ())

  // Return an MutableSeq, whose size equals the size of validatorsToIndex and
  // For v in validatorsToIndex.key
  //   Arr[validatorsToIndex[v]] = mapFunction[v]
  private def fromMapToArray[A](
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

  private def panoramaM(
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
}

object VotingMatrixImpl {
  // (Consensus value, DagLevel of the block)
  type Vote                = (BlockHash, Long)
  type _votingMatrix[F[_]] = MonadState[F, VotingMatrixState] with Semaphore[F]
  def _votingMatrix[F[_]](implicit ev: _votingMatrix[F]): _votingMatrix[F] = ev

  def emptyState[F[_]: Concurrent](n: Long): F[_votingMatrix[F]] =
    for {
      lock  <- Semaphore[F](n)
      state <- Ref[F].of(emptyVotingMatrixState)
    } yield new Semaphore[F] with DefaultMonadState[F, VotingMatrixState] {
      val monad: cats.Monad[F]               = implicitly[Monad[F]]
      def get: F[VotingMatrixState]          = state.get
      def set(s: VotingMatrixState): F[Unit] = state.set(s)

      override def available: F[Long]               = lock.available
      override def count: F[Long]                   = lock.count
      override def acquireN(n: Long): F[Unit]       = lock.acquireN(n)
      override def tryAcquireN(n: Long): F[Boolean] = lock.tryAcquireN(n)
      override def releaseN(n: Long): F[Unit]       = lock.releaseN(n)
      override def withPermit[A](t: F[A]): F[A]     = lock.withPermit(t)
    }

  case class VotingMatrixState(
      votingMatrix: MutableSeq[MutableSeq[Long]],
      firstLevelZeroVotes: MutableSeq[Option[Vote]],
      validatorToIdx: Map[Validator, Int],
      weightMap: Map[Validator, Long],
      validators: MutableSeq[Validator]
  )

  def emptyVotingMatrixState: VotingMatrixState =
    VotingMatrixState(MutableSeq.empty, MutableSeq.empty, Map.empty, Map.empty, MutableSeq.empty)

  def empty[F[_]: Concurrent]: F[VotingMatrix[F]] =
    emptyState[F](1).map(state => new VotingMatrixImpl()(state))

}
