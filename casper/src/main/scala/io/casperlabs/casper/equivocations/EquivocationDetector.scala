package io.casperlabs.casper.equivocations

import cats.{Applicative, Monad}
import cats.implicits._
import cats.mtl.FunctorRaise
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.casper.{CasperState, EquivocatedBlock, InvalidBlock, PrettyPrinter}
import io.casperlabs.shared.{Cell, Log, LogSource, StreamT}

import scala.collection.immutable.{Map, Set}

object EquivocationDetector {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  /**
    * Check whether block create equivocations and if so add it and rank of lowest base block to `EquivocationsTracker`.
    *
    * Since we had added all equivocating messages to the BlockDag, then once
    * a validator has been detected as equivocating, then for every message M1 he creates later,
    * we can find least one message M2 that M1 and M2 don't cite each other.
    */
  def checkEquivocationWithUpdate[F[_]: Monad: Log: FunctorRaise[?[_], InvalidBlock]](
      dag: DagRepresentation[F],
      block: Block
  )(
      implicit state: Cell[F, CasperState]
  ): F[Unit] =
    for {
      _ <- state.flatModify(s => {
            val creator = block.getHeader.validatorPublicKey
            s.equivocationsTracker.get(creator) match {
              case Some(lowestBaseSeqNum) =>
                for {
                  earlierRank <- rankOfEarlierMessageFromCreator(dag, block)
                  newState = if (earlierRank < lowestBaseSeqNum) {
                    s.copy(
                      equivocationsTracker = s.equivocationsTracker.updated(
                        creator,
                        earlierRank
                      )
                    )
                  } else {
                    s
                  }
                  _ <- Log[F].debug(
                        s"The creator of Block ${PrettyPrinter.buildString(block)} has equivocated before}"
                      )
                } yield newState

              case None =>
                checkEquivocations(dag, block).flatMap(
                  equivocated =>
                    if (equivocated) {
                      rankOfEarlierMessageFromCreator(dag, block).map(
                        earlierRank =>
                          s.copy(
                            equivocationsTracker = s.equivocationsTracker + (creator -> earlierRank)
                          )
                      )
                    } else {
                      s.pure[F]
                    }
                )
            }
          })
      s <- state.read
      _ <- if (s.equivocationsTracker.contains(block.getHeader.validatorPublicKey)) {
            FunctorRaise[F, InvalidBlock].raise[Unit](EquivocatedBlock)
          } else {
            Applicative[F].unit
          }
    } yield ()

  /**
    * check whether block creates equivocations
    *
    * Caution:
    *   Always use method `checkEquivocationWithUpdate`.
    *   It may not work when receiving a block created by a validator who has equivocated.
    *   For example:
    *
    *       |   v0   |
    *       |        |
    *       |        |
    *       |     B4 |
    *       |     |  |
    *       | B2  B3 |
    *       |  \  /  |
    *       |   B1   |
    *
    *   Local node could detect that Validator v0 has equivocated after receiving B3,
    *   then when adding B4, this method doesn't work, it return false but actually B4
    *   equivocated with B2.
    */
  private[casper] def checkEquivocations[F[_]: Monad: Log](
      dag: DagRepresentation[F],
      block: Block
  ): F[Boolean] =
    for {
      maybeLatestMessageOfCreator <- dag.latestMessageHash(block.getHeader.validatorPublicKey)
      equivocated <- maybeLatestMessageOfCreator match {
                      case None =>
                        // It is the first block by that validator
                        false.pure[F]
                      case Some(latestMessageHashOfCreator) =>
                        val maybeCreatorJustification = creatorJustificationHash(block)
                        if (maybeCreatorJustification == maybeLatestMessageOfCreator) {
                          // Directly reference latestMessage of creator of the block
                          false.pure[F]
                        } else
                          for {
                            latestMessageOfCreator <- dag
                                                       .lookup(latestMessageHashOfCreator)
                                                       .map(_.get)
                            stream = toposortJDagFromBlock(dag, block)
                            // Find whether the block cite latestMessageOfCreator
                            decisionPointBlock <- stream.find(
                                                   b =>
                                                     b == latestMessageOfCreator || b.rank < latestMessageOfCreator.rank
                                                 )
                            equivocated = decisionPointBlock != latestMessageOfCreator.some
                            _ <- Log[F]
                                  .warn(
                                    s"Find equivocation: justifications of block ${PrettyPrinter.buildString(block)} don't cite the latest message by validator ${PrettyPrinter
                                      .buildString(block.getHeader.validatorPublicKey)}: ${PrettyPrinter
                                      .buildString(latestMessageHashOfCreator)}"
                                  )
                                  .whenA(equivocated)
                          } yield equivocated
                    }
    } yield equivocated

  private def creatorJustificationHash(block: Block): Option[BlockHash] =
    ProtoUtil.creatorJustification(block.getHeader).map(_.latestBlockHash)

  private def toposortJDagFromBlock[F[_]: Monad: Log](
      dag: DagRepresentation[F],
      block: Block
  ): StreamT[F, BlockMetadata] = {
    implicit val blockTopoOrdering: Ordering[BlockMetadata] = DagOperations.blockTopoOrderingDesc
    DagOperations.bfToposortTraverseF(
      List(BlockMetadata.fromBlock(block))
    )(
      _.justifications
        .traverse(j => dag.lookup(j.latestBlockHash))
        .map(_.flatten)
    )
  }

  /**
    * Find equivocators basing latestMessageHashes
		*
    * We use `bfToposortTraverseF` to traverse from `latestMessageHashes` down to minimal rank
    * of base block of equivocationRecord. `bfToposortTraverseF` guarantee that we will only
    * meet a specific block only once, and `validatorBlockSeqNum` is equal to 1 plus
    * validatorBlock of creator's previous created block. So that once we find duplicated
    * (Validator, validatorBlockSeqNum), we know the validator has equivocated.
		*
    * @param dag the block dag
    * @param latestMessagesHashes generate from direct justifications
    * @param equivocationTracker local tracker of equivocations
    * @tparam F effect type
    * @return equivocators that can be seen from view of latestMessages
    */
  def equivocatorDetectFromLatestMessage[F[_]: Monad](
      dag: DagRepresentation[F],
      latestMessagesHashes: Map[Validator, BlockHash],
      equivocationTracker: Map[Validator, Long]
  ): F[Set[Validator]] = {
    val equivocationRecords = equivocationTracker.filterKeys(latestMessagesHashes.contains)
    val minRank             = equivocationRecords.values.fold(0L)(_ min _)
    for {
      latestMessage                                         <- latestMessagesHashes.values.toList.traverse(dag.lookup).map(_.flatten)
      implicit0(blockTopoOrdering: Ordering[BlockMetadata]) = DagOperations.blockTopoOrderingDesc

      stream = DagOperations.bfToposortTraverseF(latestMessage)(
        _.justifications.traverse(j => dag.lookup(j.latestBlockHash)).map(_.flatten)
      )
      acc <- stream
              .foldWhileLeft(
                (Set.empty[Validator], Set.empty[(Validator, Int)])
              ) {
                case (
                    (detectedEquivocator, visitedValidatorAndBlockSeqNum),
                    b
                    ) =>
                  val creator            = b.validatorPublicKey
                  val creatorBlockSeqNam = b.validatorBlockSeqNum
                  if (detectedEquivocator == equivocationRecords.keySet || b.rank <= minRank) {
                    // Stop traversal if all equivocator has occurred in j-post-dag of latestMessages
                    // or we traversal down to minimal of rank of base block of all equivocator
                    Right(
                      (detectedEquivocator, visitedValidatorAndBlockSeqNum)
                    )
                  } else if (detectedEquivocator.contains(creator)) {
                    Left((detectedEquivocator, visitedValidatorAndBlockSeqNum))
                  } else if (visitedValidatorAndBlockSeqNum.contains(
                               (creator, creatorBlockSeqNam)
                             )) {
                    Left(
                      (
                        detectedEquivocator + creator,
                        visitedValidatorAndBlockSeqNum
                      )
                    )
                  } else {
                    Left(
                      (
                        detectedEquivocator,
                        visitedValidatorAndBlockSeqNum + (creator -> creatorBlockSeqNam)
                      )
                    )
                  }
              }
      (detectedEquivocator, _) = acc
    } yield detectedEquivocator
  }

  private def rankOfEarlierMessageFromCreator[F[_]: Monad: Log](
      dag: DagRepresentation[F],
      block: Block
  ): F[Long] =
    toposortJDagFromBlock(dag, block)
      .find(b => b.validatorPublicKey == block.getHeader.validatorPublicKey)
      .map(_.map(_.rank).getOrElse(0L))
}
