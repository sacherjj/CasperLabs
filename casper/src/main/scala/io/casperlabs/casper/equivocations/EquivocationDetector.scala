package io.casperlabs.casper.equivocations

import cats.Monad
import cats.implicits._
import cats.mtl.FunctorRaise
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.casper.{CasperState, EquivocatedBlock, InvalidBlock, PrettyPrinter}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.shared.{Cell, Log, LogSource, StreamT}
import io.casperlabs.storage.dag.DagRepresentation

import scala.collection.immutable.{Map, Set}

object EquivocationDetector {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  /**
    * Check whether a block creates equivocations when adding a new block to the block dag,
    * if so store the validator with the lowest rank of any base block from
    * the same validator to `EquivocationsTracker`, then an error is `EquivocatedBlock` returned.
    * The base block of equivocation record is the latest unequivocating block from the same validator.
    *
    * For example:
    *
    *    v0            v1             v2
    *
    *           |  b3    b4    |
    *           |     \   /    |
    *           |     b2     b5|
    *           |      |    /  |
    *           |      b1      |
    *
    * When the node receives b4, `checkEquivocations` will detect that b4 and b3 don't cite each other;
    * in other words, b4 creates an equivocation. Then the base block of b4 and b3 is b2, so we add
    * a record (v1,the rank of b2) to `equivocationsTracker`. After a while, the node receives b5,
    * since we had added all equivocating messages to the BlockDag, then once
    * a validator has been detected as equivocating, then for every message M1 he creates later,
    * we can find least one message M2 that M1 and M2 don't cite each other. In other words, a block
    * created by a validator who has equivocated will create another equivocation. In this way, b5
    * doesn't cite blocks (b2, b3, and b4), and blocks (b2, b3, and b4) don't cite b5 either. So b5
    * creates equivocations. And the base block of b5 is b1, whose rank is smaller than that of b2, so
    * we will update the `equivocationsTracker`, setting the value of key v1 to be rank of b1.
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

      message <- MonadThrowable[F].fromTry(Message.fromBlock(block))

      _ <- MonadThrowable[F].whenA(equivocated)(
            rankOfEarlierMessageFromCreator(dag, message)
              .flatMap { earlierRank =>
                state.modify { s =>
                  s.copy(
                    equivocationsTracker = s.equivocationsTracker.updated(creator, earlierRank)
                  )
                }
              }
          )
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
  private def checkEquivocations[F[_]: MonadThrowable: Log](
      dag: DagRepresentation[F],
      block: Block
  ): F[Boolean] =
    for {
      validatorLatestMessages <- dag.latestMessage(block.getHeader.validatorPublicKey)
      equivocated <- if (validatorLatestMessages.isEmpty || citesPreviousMsg(
                           block,
                           validatorLatestMessages
                         )) {
                      // It is the first message by that validator
                      // or cites previous one.
                      false.pure[F]
                    } else if (validatorLatestMessages.size > 1) {
                      Log[F]
                        .warn(
                          s"Validator ${PrettyPrinter.buildString(block.getHeader.validatorPublicKey)} has already equivocated in the past."
                        )
                        .as(true)
                    } else {
                      val creatorsMessagesInJPastCone = creatorJustificationHash(block)
                      // We've already tested whether `validatorLatestMessage` is empty
                      // or has more than one element.
                      val validatorLatestMessage = validatorLatestMessages.head
                      if (creatorsMessagesInJPastCone.size > 1)
                        // More than latest message from the creator of a block visible in the
                        // j-past-cone of the block.
                        // NOTE: Messages like that should not be accepted - merging of a swimlane is not allowed.
                        Log[F]
                          .warn(s"More than one latest message visible in the j-past-cone of the ${PrettyPrinter
                            .buildString(block.blockHash)} by the creator ${PrettyPrinter
                            .buildString(block.getHeader.validatorPublicKey)}")
                          .as(true)
                      else if (creatorsMessagesInJPastCone.isEmpty)
                        // We have seen a message from that validator (see `validatorLatestMessage`)
                        // but block's j-past-cone doesn't cite any message by that validator.
                        // This is an equivocation.
                        Log[F]
                          .warn(
                            s"Found equivocation: justifications of block ${PrettyPrinter
                              .buildString(block)} don't cite the latest message by validator ${PrettyPrinter
                              .buildString(block.getHeader.validatorPublicKey)}: ${PrettyPrinter
                              .buildString(validatorLatestMessage.messageHash)}"
                          )
                          .as(true)
                      else if (creatorsMessagesInJPastCone == validatorLatestMessages) {
                        // `creatorsMessagesInJPastCone` should have single element
                        // and if it's equal to what we have seen so far it means that block
                        // cites validator's previous message directly and that's not an equivocation.
                        false.pure[F]
                      } else
                        validatorLatestMessages.toList
                          .traverse { latestMessageOfCreator =>
                            for {
                              message <- MonadThrowable[F].fromTry(Message.fromBlock(block))
                              stream  = DagOperations.toposortJDagDesc(dag, List(message))
                              // Find whether the block cites latestMessageOfCreator
                              decisionPointBlock <- stream.find(
                                                     b =>
                                                       b == latestMessageOfCreator || b.rank < latestMessageOfCreator.rank
                                                   )
                              equivocated = decisionPointBlock != latestMessageOfCreator.some
                              _ <- Log[F]
                                    .warn(
                                      s"Found equivocation: justifications of block ${PrettyPrinter
                                        .buildString(block)} don't cite the latest message by validator ${PrettyPrinter
                                        .buildString(block.getHeader.validatorPublicKey)}: ${PrettyPrinter
                                        .buildString(latestMessageOfCreator.messageHash)}"
                                    )
                                    .whenA(equivocated)
                            } yield equivocated
                          }
                          .map(_.exists(identity))
                    }
    } yield equivocated

  // Check whether block cites previous message by the same creator.
  // Since citing multiple latest messages is prohibited `creatorsMessagesInJPastCone` should have
  // at most 1 element.
  private def citesPreviousMsg(block: Block, latestMessages: Set[Message]): Boolean = {
    val creatorsMessagesInJPastCone = creatorJustificationHash(block)
    creatorsMessagesInJPastCone.size == 1 && latestMessages.size == 1 && latestMessages.map(
      _.messageHash
    ) == creatorsMessagesInJPastCone
  }

  // Messages from the creator of the block in the j-past-cone of that block.
  private def creatorJustificationHash(block: Block): Set[BlockHash] =
    ProtoUtil.creatorJustification(block.getHeader).map(_.latestBlockHash)

  /**
    * Find equivocating validators that a block can see based on its direct justifications
    *
    * We use `bfToposortTraverseF` to traverse from `latestMessageHashes` down beyond the minimal rank
    * of base block of equivocationRecords. Since we have already validated `validatorBlockSeqNum`
    * equals 1 plus that of previous block created by the same validator, if we find a duplicated
    * value, we know the validator has equivocated.
    *
    * @param dag the block dag
    * @param justificationMsgHashes generate from direct justifications
    * @param equivocationsTracker local tracker of equivocations
    * @tparam F effect type
    * @return validators that can be seen equivocating from the view of latestMessages
    */
  def detectVisibleFromJustifications[F[_]: Monad](
      dag: DagRepresentation[F],
      justificationMsgHashes: Map[Validator, Set[BlockHash]],
      equivocationsTracker: EquivocationsTracker
  ): F[Set[Validator]] = {
    val minBaseRank = equivocationsTracker.min.getOrElse(0L)
    for {
      justificationMessages <- justificationMsgHashes.values.toList
                                .flatTraverse(_.toList.traverse(dag.lookup))
                                .map(_.flatten)
      implicit0(blockTopoOrdering: Ordering[Message]) = DagOperations.blockTopoOrderingDesc

      toposortJDagFromBlock = DagOperations.bfToposortTraverseF(justificationMessages)(
        _.justifications.toList.traverse(j => dag.lookup(j.latestBlockHash)).map(_.flatten)
      )

      acc <- toposortJDagFromBlock
              .foldWhileLeft(State()) {
                case (state, b) =>
                  val creator            = b.validatorId
                  val creatorBlockSeqNum = b.validatorMsgSeqNum
                  if (state.allDetected(equivocationsTracker.keySet) || b.rank <= minBaseRank) {
                    // Stop traversal if all known equivocations has been found in j-past-cone
                    // of `b` or we traversed beyond the minimum rank of all equivocations.
                    Right(state)
                  } else if (state.alreadyDetected(creator)) {
                    Left(state)
                  } else if (state.alreadyVisited(creator, creatorBlockSeqNum)) {
                    Left(state.addEquivocator(creator))
                  } else {
                    Left(state.addVisited(creator, creatorBlockSeqNum))
                  }
              }
    } yield acc.detectedEquivocators
  }

  private case class State(
      detectedEquivocators: Set[Validator] = Set.empty,
      visitedBlocks: Map[Validator, Int] = Map.empty
  ) {
    def addEquivocator(v: Validator): State = copy(detectedEquivocators = detectedEquivocators + v)
    def addVisited(v: Validator, blockSeqNum: Int): State =
      copy(visitedBlocks = visitedBlocks + (v -> blockSeqNum))
    def alreadyVisited(v: Validator, blockSeqNum: Int): Boolean =
      visitedBlocks.get(v).contains(blockSeqNum)
    def alreadyDetected(v: Validator): Boolean   = detectedEquivocators.contains(v)
    def allDetected(vs: Set[Validator]): Boolean = detectedEquivocators == vs
  }

  /**
    * Returns rank of last but one message by the same validator, as seen in the j-past-cone of the block.
    *
    * This method assumes that the system has removed redundant justifications.
    *
    * @param dag The block dag
    * @param msg Block to run
    * @tparam F Effect type
    * @return
    */
  private def rankOfEarlierMessageFromCreator[F[_]: Monad: Log](
      dag: DagRepresentation[F],
      msg: Message
  ): F[Long] =
    DagOperations
      .toposortJDagDesc(dag, List(msg))
      .filter(_.validatorId == msg.validatorId)
      .take(2)
      .toList
      .map(
        _.map(_.rank)
          .get(1)        // The first element is the block we start traversal, ignore it.
          .getOrElse(0L) // when reached genesis, return 0, which is the rank of genesis
      )
}
