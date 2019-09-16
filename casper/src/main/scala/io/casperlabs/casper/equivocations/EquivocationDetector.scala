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

  type EquivocationTracker = Map[Validator, Long] // Map[Validator, Rank]

  private implicit val logSource: LogSource = LogSource(this.getClass)

  /**
    * Check whether a block creates equivocations, and if so add it and the rank of the lowest base block to `EquivocationsTracker`.
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
      s       <- state.read
      creator = block.getHeader.validatorPublicKey
      equivocated <- if (s.equivocationTracker.contains(creator)) {
                      Log[F].debug(
                        s"The creator of Block ${PrettyPrinter.buildString(block)} has equivocated before}"
                      ) *> true.pure[F]
                    } else {
                      checkEquivocations(dag, block)
                    }

      _ <- rankOfEarlierMessageFromCreator(dag, block)
            .flatMap { earlierRank =>
              state.modify { s =>
                s.equivocationTracker.get(creator) match {
                  case Some(lowestBaseSeqNum) if earlierRank < lowestBaseSeqNum =>
                    s.copy(
                      equivocationTracker = s.equivocationTracker.updated(creator, earlierRank)
                    )
                  case None =>
                    s.copy(
                      equivocationTracker = s.equivocationTracker.updated(creator, earlierRank)
                    )
                  case _ =>
                    s
                }
              }
            }
            .whenA(equivocated)
      _ <- FunctorRaise[F, InvalidBlock].raise[Unit](EquivocatedBlock).whenA(equivocated)
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
  private def checkEquivocations[F[_]: Monad: Log](
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
    * Find equivocating validators that a block can see based on its direct justifications
    *
    * This method is not used to detect new equivocations when receiving new blocks. It is used to help calculating main parent in the `forkchoice`.
    *
    * We use `bfToposortTraverseF` to traverse from `latestMessageHashes` down beyond the minimal rank
    * of base block of equivocationRecords. Since we have already validated `validatorBlockSeqNum`
    * equals 1 plus that of previous block created by the same validator, if we find a duplicated
    * value, we know the validator has equivocated.
    *
    * @param dag the block dag
    * @param justificationMsgHashes generate from direct justifications
    * @param equivocationTracker local tracker of equivocations
    * @tparam F effect type
    * @return validators that can be seen equivocating from the view of latestMessages
    */
  def detectVisibleFromJustificationMsgHashes[F[_]: Monad](
      dag: DagRepresentation[F],
      justificationMsgHashes: Map[Validator, BlockHash],
      equivocationTracker: EquivocationTracker
  ): F[Set[Validator]] =
    if (equivocationTracker.isEmpty) {
      Set.empty[Validator].pure[F]
    } else {
      val minRank = equivocationTracker.values.min

      for {
        justificationMessages <- justificationMsgHashes.values.toList
                                  .traverse(dag.lookup)
                                  .map(_.flatten)
        implicit0(blockTopoOrdering: Ordering[BlockMetadata]) = DagOperations.blockTopoOrderingDesc

        toposortJDagFromBlock = DagOperations.bfToposortTraverseF(justificationMessages)(
          _.justifications.traverse(j => dag.lookup(j.latestBlockHash)).map(_.flatten)
        )
        acc <- toposortJDagFromBlock
                .foldWhileLeft(
                  (Set.empty[Validator], Set.empty[(Validator, Int)])
                ) {
                  case (
                      (detectedEquivocators, visitedValidatorAndBlockSeqNums),
                      b
                      ) =>
                    val creator            = b.validatorPublicKey
                    val creatorBlockSeqNam = b.validatorBlockSeqNum
                    if (detectedEquivocators == equivocationTracker.keySet || b.rank <= minRank) {
                      // Stop traversal if all known equivocations has been found in j-past-cone of `b`
                      // or we traversed beyond the minimum rank of all equivocations.
                      Right(
                        (detectedEquivocators, visitedValidatorAndBlockSeqNums)
                      )
                    } else if (detectedEquivocators.contains(creator)) {
                      Left((detectedEquivocators, visitedValidatorAndBlockSeqNums))
                    } else if (visitedValidatorAndBlockSeqNums.contains(
                                 (creator, creatorBlockSeqNam)
                               )) {
                      Left(
                        (
                          detectedEquivocators + creator,
                          visitedValidatorAndBlockSeqNums
                        )
                      )
                    } else {
                      Left(
                        (
                          detectedEquivocators,
                          visitedValidatorAndBlockSeqNums + (creator -> creatorBlockSeqNam)
                        )
                      )
                    }
                }
        (detectedEquivocator, _) = acc
      } yield detectedEquivocator
    }

  /**
    * From j-past-cone of the block, find the maximum rank of blocks created by the same validator.
    *
    * @param dag The block dag
    * @param block Block to run
    * @tparam F Effect type
    * @return
    */
  private def rankOfEarlierMessageFromCreator[F[_]: Monad: Log](
      dag: DagRepresentation[F],
      block: Block
  ): F[Long] =
    toposortJDagFromBlock(dag, block)
      .filter(b => b.validatorPublicKey == block.getHeader.validatorPublicKey)
      .take(2)
      .toList
      .map(
        _.map(_.rank)
          .get(1)        // The first element is the block we start traversal, ignore it.
          .getOrElse(0L) // when reached genesis, return 0, which is the rank of genesis
      )
}
