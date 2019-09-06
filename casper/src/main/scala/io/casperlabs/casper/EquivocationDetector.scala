package io.casperlabs.casper

import cats.{Applicative, Monad}
import cats.implicits._
import cats.mtl.FunctorRaise
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.shared.{Cell, Log, LogSource}

object EquivocationDetector {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  /**
    * Check whether block create equivocations and if so add it to `EquivocationsTracker`.
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
      s <- state.read
      equivocated <- if (s.equivocationsTracker.contains(block.getHeader.validatorPublicKey)) {
                      Log[F].debug(
                        s"The creator of Block ${PrettyPrinter.buildString(block)} has equivocated before}"
                      ) *> true.pure[F]
                    } else {
                      checkEquivocations(dag, block) //.map(
                    }
      _ <- state
            .modify(
              s =>
                s.copy(
                  equivocationsTracker = s.equivocationsTracker + block.getHeader.validatorPublicKey
                )
            )
            .whenA(equivocated)
      _ <- FunctorRaise[F, InvalidBlock].raise[Unit](EquivocatedBlock).whenA(equivocated)
    } yield ()

  /**
    * check whether block creates equivocations
    *
    * Caution:
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
                        false.pure[F]
                      case Some(latestMessageHashOfCreator) =>
                        val maybeCreatorJustification = creatorJustificationHash(block)
                        if (maybeCreatorJustification == maybeLatestMessageOfCreator) {
                          Applicative[F].unit
                          false.pure[F]
                        } else
                          for {
                            latestMessageOfCreator <- dag
                                                       .lookup(latestMessageHashOfCreator)
                                                       .map(_.get)
                            implicit0(blockTopoOrdering: Ordering[BlockMetadata]) = DagOperations.blockTopoOrderingDesc
                            stream = DagOperations.bfToposortTraverseF(
                              List(BlockMetadata.fromBlock(block))
                            )(
                              b =>
                                b.justifications
                                  .traverse(j => dag.lookup(j.latestBlockHash))
                                  .map(_.flatten)
                            )
                            decisionPointBlock <- stream.find(
                                                   b =>
                                                     b == latestMessageOfCreator || b.rank < latestMessageOfCreator.rank
                                                 )

                            equivocated = decisionPointBlock != latestMessageOfCreator.some
                            decisionPrintString = decisionPointBlock
                              .map(b => PrettyPrinter.buildString(b.blockHash))
                              .getOrElse("None")
                            _ <- Log[F]
                                  .warn(
                                    s"Find equivocation: The previous creator justification of Block ${PrettyPrinter
                                      .buildString(block)} is ${decisionPrintString}, local latestBlockMessage of creator is ${PrettyPrinter
                                      .buildString(latestMessageHashOfCreator)}"
                                  )
                                  .whenA(equivocated)
                          } yield equivocated
                    }
    } yield equivocated

  private def creatorJustificationHash(block: Block): Option[BlockHash] =
    ProtoUtil.creatorJustification(block).map(_.latestBlockHash)
}
