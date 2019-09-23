package io.casperlabs.casper.equivocations

import cats.Monad
import cats.implicits._
import cats.mtl.FunctorRaise
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.casper.{CasperState, EquivocatedBlock, InvalidBlock, PrettyPrinter}
import io.casperlabs.models.Message
import io.casperlabs.shared.{Cell, Log, LogSource}
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.catscontrib.MonadThrowable

object EquivocationDetector {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  /**
    * Check whether block create equivocations and if so add it to `EquivocationsTracker`.
    *
    * Since we had added all equivocating messages to the BlockDag, then once
    * a validator has been detected as equivocating, then for every message M1 he creates later,
    * we can find least one message M2 that M1 and M2 don't cite each other.
    */
  def checkEquivocationWithUpdate[F[_]: MonadThrowable: Log: FunctorRaise[?[_], InvalidBlock]](
      dag: DagRepresentation[F],
      block: Block
  )(
      implicit state: Cell[F, CasperState]
  ): F[Unit] =
    for {
      s       <- state.read
      creator = block.getHeader.validatorPublicKey
      equivocated <- if (s.equivocationsTracker.contains(creator)) {
                      Log[F].debug(
                        s"The creator of Block ${PrettyPrinter.buildString(block)} has equivocated before}"
                      ) *> true.pure[F]
                    } else {
                      checkEquivocations(dag, block)
                    }
      _ <- state
            .modify(
              s =>
                s.copy(
                  equivocationsTracker = s.equivocationsTracker + creator
                )
            )
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
  private[casper] def checkEquivocations[F[_]: MonadThrowable: Log](
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
                            message                                         <- MonadThrowable[F].fromTry(Message.fromBlock(block))
                            implicit0(blockTopoOrdering: Ordering[Message]) = DagOperations.blockTopoOrderingDesc
                            stream = DagOperations.bfToposortTraverseF(
                              List(message)
                            )(
                              b =>
                                b.justifications.toList
                                  .traverse(j => dag.lookup(j.latestBlockHash))
                                  .map(_.flatten)
                            )
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
}
