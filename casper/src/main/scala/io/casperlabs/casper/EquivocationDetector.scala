package io.casperlabs.casper

import cats.{Applicative, Monad}
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.shared.{Cell, Log, LogSource}

object EquivocationDetector {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  def checkEquivocatorWithUpdate[F[_]: Monad: Log](dag: DagRepresentation[F], block: Block)(
      implicit state: Cell[F, CasperState]
  ): F[Unit] =
    for {
      _ <- state.flatModify(s => {
            if (s.equivocationsTracker.contains(block.getHeader.validatorPublicKey)) {
              Log[F].debug(
                s"The creator of Block ${PrettyPrinter.buildString(block)} has equivocated before}"
              ) *> s.pure[F]
            } else {
              checkEquivocations(dag, block).map(
                equivocated =>
                  if (equivocated) {
                    s.copy(
                      equivocationsTracker = s.equivocationsTracker + block.getHeader.validatorPublicKey
                    )
                  } else {
                    s
                  }
              )
            }
          })
    } yield ()

  private def checkEquivocations[F[_]: Monad: Log](
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
