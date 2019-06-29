package io.casperlabs.casper

import cats.{Applicative, Monad}
import cats.implicits._
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.EquivocationRecord.SequenceNumber
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.casper.consensus.{Block, Bond}, Block.Justification
import io.casperlabs.casper.util.{DoublyLinkedDag, ProtoUtil}
import io.casperlabs.casper.util.ProtoUtil.{
  bonds,
  findCreatorJustificationAncestorWithSeqNum,
  toLatestMessageHashes
}
import io.casperlabs.shared.{Cell, Log, LogSource}

import scala.collection.mutable

/**
  * A summary of the neglected equivocation algorithm is as follows.
  *
  * Every equivocation has one "base equivocation block" and multiple "children equivocation blocks" where the
  * "children equivocation blocks" have a sequence number that is one greater than the "base equivocation block".
  * To detect neglected equivocations, we keep a set of "equivocation record"s. An "equivocation record" is a tuple
  * containing equivocator's ID, the sequence number of the equivocation base block and a set of block hashes of blocks
  * that point to enough evidence to slash an equivocation corresponding to the "equivocation record".
  * Each time we discover an equivocation, we add a new "equivocation record" entry to the set with the validator's ID
  * and the base equivocation block's sequence number filled in. Each time we add a block to our view,
  * we loop through our "equivocations record"s and see if the block we want to add has enough information to detect
  * the equivocation corresponding to the "equivocation record". There are three cases:
  *
  * Case 1) The block has enough information and the block contains the equivocator in its justification,
  *         we slash the creator of that block
  * Case 2) The block has enough information and the block properly has rotated out the equivocator from its
  *         justification, we update the "equivocation record" so that the set contains this block.
  * Case 3) The block doesn't have enough information and so we do nothing.
  *
  * To ascertain whether a block has enough information to detect a particular equivocation, we loop through the
  * block's justifications and accumulate a set of children equivocation blocks that are reachable from
  * the block's justifications. If at any point while looping through the block's justifications, if we come across a
  * justification block that is in the set of block hashes, we immediately ascertain the block has enough information
  * to detect the equivocation corresponding to the "equivocation record". If at any point the set of children
  * equivocation blocks becomes larger than one in size, we also immediately ascertain the block has enough information
  * to detect the equivocation corresponding to the "equivocation record".
  */
sealed trait EquivocationDiscoveryStatus
final case object EquivocationNeglected extends EquivocationDiscoveryStatus
final case object EquivocationDetected  extends EquivocationDiscoveryStatus
final case object EquivocationOblivious extends EquivocationDiscoveryStatus

// This is the sequence number of the equivocator's base block
final case class EquivocationRecord(
    equivocator: Validator,
    equivocationBaseBlockSeqNum: SequenceNumber,
    equivocationDetectedBlockHashes: Set[BlockHash]
)

object EquivocationRecord {
  type SequenceNumber = Int
}

object EquivocationDetector {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  def checkEquivocations[F[_]: Monad: Log: FunctorRaise[?[_], InvalidBlock]](
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash],
      block: Block,
      dag: BlockDagRepresentation[F]
  ): F[Unit] =
    for {
      maybeLatestMessageOfCreatorHash <- dag.latestMessageHash(block.getHeader.validatorPublicKey)
      maybeCreatorJustification       = creatorJustificationHash(block)
      isNotEquivocation               = maybeCreatorJustification == maybeLatestMessageOfCreatorHash
      _ <- if (isNotEquivocation) {
            Applicative[F].unit
          } else if (requestedAsDependency(block, blockBufferDependencyDag)) {
            FunctorRaise[F, InvalidBlock].raise[Unit](AdmissibleEquivocation)
          } else {
            for {
              validator <- PrettyPrinter.buildString(block.getHeader.validatorPublicKey).pure[F]
              creatorJustificationHash = PrettyPrinter.buildString(
                maybeCreatorJustification.getOrElse(ByteString.EMPTY)
              )
              latestMessageOfCreator = PrettyPrinter.buildString(
                maybeLatestMessageOfCreatorHash.getOrElse(ByteString.EMPTY)
              )
              _ <- Log[F].warn(
                    s"Ignorable equivocation: validator is $validator, creator justification is $creatorJustificationHash, latest message of creator is $latestMessageOfCreator"
                  )
              _ <- FunctorRaise[F, InvalidBlock].raise[Unit](IgnorableEquivocation)
            } yield ()
          }
    } yield ()

  private def requestedAsDependency(
      block: Block,
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash]
  ): Boolean =
    blockBufferDependencyDag.parentToChildAdjacencyList.contains(block.blockHash)

  private def creatorJustificationHash(block: Block): Option[BlockHash] =
    for {
      maybeCreatorJustification <- ProtoUtil.creatorJustification(block)
    } yield maybeCreatorJustification.latestBlockHash

  // See summary of algorithm above
  def checkNeglectedEquivocationsWithUpdate[F[_]: MonadThrowable: BlockStore: FunctorRaise[
    ?[_],
    InvalidBlock
  ]](
      block: Block,
      genesis: Block
  )(implicit state: Cell[F, CasperState]): F[Unit] =
    Monad[F].ifM(
      isNeglectedEquivocationDetectedWithUpdate[F](
        block,
        genesis
      )
    )(FunctorRaise[F, InvalidBlock].raise[Unit](NeglectedEquivocation), Monad[F].unit)

  private def isNeglectedEquivocationDetectedWithUpdate[F[_]: MonadThrowable: BlockStore](
      block: Block,
      genesis: Block
  )(implicit state: Cell[F, CasperState]): F[Boolean] =
    for {
      s <- Cell[F, CasperState].read
      neglectedEquivocationDetected <- s.equivocationsTracker.toList.existsM { equivocationRecord =>
                                        updateEquivocationsTracker[F](
                                          block,
                                          equivocationRecord,
                                          genesis
                                        )
                                      }
    } yield neglectedEquivocationDetected

  /**
    * If an equivocation is detected, it is added to the equivocationDetectedBlockHashes, which keeps track
    * of the block hashes that correspond to the blocks from which an equivocation can be justified.
    *
    * @return Whether a neglected equivocation was discovered.
    */
  private def updateEquivocationsTracker[F[_]: MonadThrowable: BlockStore](
      block: Block,
      equivocationRecord: EquivocationRecord,
      genesis: Block
  )(implicit state: Cell[F, CasperState]): F[Boolean] =
    for {
      equivocationDiscoveryStatus <- getEquivocationDiscoveryStatus[F](
                                      block,
                                      equivocationRecord,
                                      genesis
                                    )
      neglectedEquivocationDetected = equivocationDiscoveryStatus match {
        case EquivocationNeglected =>
          true
        case EquivocationDetected =>
          false
        case EquivocationOblivious =>
          false
      }
      _ <- if (equivocationDiscoveryStatus == EquivocationDetected) {
            Cell[F, CasperState].modify { s =>
              val updatedEquivocationDetectedBlockHashes = equivocationRecord.equivocationDetectedBlockHashes + block.blockHash
              val newEquivocationsTracker = s.equivocationsTracker - equivocationRecord + (
                equivocationRecord
                  .copy(equivocationDetectedBlockHashes = updatedEquivocationDetectedBlockHashes)
                )
              s.copy(equivocationsTracker = newEquivocationsTracker)
            }
          } else ().pure[F]
    } yield neglectedEquivocationDetected

  private def getEquivocationDiscoveryStatus[F[_]: MonadThrowable: BlockStore](
      block: Block,
      equivocationRecord: EquivocationRecord,
      genesis: Block
  ): F[EquivocationDiscoveryStatus] = {
    val equivocatingValidator = equivocationRecord.equivocator
    val latestMessages        = toLatestMessageHashes(block.getHeader.justifications)
    val maybeEquivocatingValidatorBond =
      bonds(block).find(_.validatorPublicKey == equivocatingValidator)
    maybeEquivocatingValidatorBond match {
      case Some(Bond(_, stake)) =>
        getEquivocationDiscoveryStatusForBondedValidator[F](
          equivocationRecord,
          latestMessages,
          stake,
          genesis
        )
      case None =>
        /*
         * Since block has dropped equivocatingValidator from the bonds, it has acknowledged the equivocation.
         * The combination of Validate.transactions and Validate.bondsCache ensure that you can only drop
         * validators through transactions to the proof of stake contract.
         */
        Applicative[F].pure(EquivocationDetected)
    }
  }

  private def getEquivocationDiscoveryStatusForBondedValidator[F[_]: MonadThrowable: BlockStore](
      equivocationRecord: EquivocationRecord,
      latestMessages: Map[Validator, BlockHash],
      stake: Long,
      genesis: Block
  ): F[EquivocationDiscoveryStatus] =
    if (stake > 0L) {
      for {
        equivocationDetectable <- isEquivocationDetectable[F](
                                   latestMessages.toSeq,
                                   equivocationRecord,
                                   Set.empty[Block],
                                   genesis
                                 )
      } yield
        if (equivocationDetectable) {
          EquivocationNeglected
        } else {
          EquivocationOblivious
        }
    } else {
      // TODO: This case is not necessary if assert(stake > 0) in the PoS contract
      Applicative[F].pure(EquivocationDetected)
    }

  private def isEquivocationDetectable[F[_]: MonadThrowable: BlockStore](
      latestMessages: Seq[(Validator, BlockHash)],
      equivocationRecord: EquivocationRecord,
      equivocationChildren: Set[Block],
      genesis: Block
  ): F[Boolean] =
    latestMessages match {
      case Nil => false.pure[F]
      case (_, justificationBlockHash) +: remainder =>
        isEquivocationDetectableAfterViewingBlock[F](
          justificationBlockHash,
          equivocationRecord,
          equivocationChildren,
          remainder,
          genesis
        )
    }

  private def isEquivocationDetectableAfterViewingBlock[F[_]: MonadThrowable: BlockStore](
      justificationBlockHash: BlockHash,
      equivocationRecord: EquivocationRecord,
      equivocationChildren: Set[Block],
      remainder: Seq[(Validator, BlockHash)],
      genesis: Block
  ): F[Boolean] =
    if (equivocationRecord.equivocationDetectedBlockHashes.contains(justificationBlockHash)) {
      true.pure[F]
    } else {
      for {
        justificationBlock <- ProtoUtil.unsafeGetBlock[F](justificationBlockHash)
        equivocationDetected <- isEquivocationDetectableThroughChildren[F](
                                 equivocationRecord,
                                 equivocationChildren,
                                 remainder,
                                 justificationBlock,
                                 genesis
                               )
      } yield equivocationDetected
    }

  private def isEquivocationDetectableThroughChildren[F[_]: MonadThrowable: BlockStore](
      equivocationRecord: EquivocationRecord,
      equivocationChildren: Set[Block],
      remainder: Seq[(Validator, BlockHash)],
      justificationBlock: Block,
      genesis: Block
  ): F[Boolean] = {
    val equivocatingValidator = equivocationRecord.equivocator
    val equivocationBaseBlockSeqNum =
      equivocationRecord.equivocationBaseBlockSeqNum
    for {
      updatedEquivocationChildren <- maybeAddEquivocationChild[F](
                                      justificationBlock,
                                      equivocatingValidator,
                                      equivocationBaseBlockSeqNum,
                                      equivocationChildren,
                                      genesis
                                    )
      equivocationDetected <- if (updatedEquivocationChildren.size > 1) {
                               true.pure[F]
                             } else {
                               isEquivocationDetectable[F](
                                 remainder,
                                 equivocationRecord,
                                 updatedEquivocationChildren,
                                 genesis
                               )
                             }
    } yield equivocationDetected
  }

  private def maybeAddEquivocationChild[F[_]: MonadThrowable: BlockStore](
      justificationBlock: Block,
      equivocatingValidator: Validator,
      equivocationBaseBlockSeqNum: SequenceNumber,
      equivocationChildren: Set[Block],
      genesis: Block
  ): F[Set[Block]] =
    if (justificationBlock.blockHash == genesis.blockHash) {
      equivocationChildren.pure[F]
    } else if (justificationBlock.getHeader.validatorPublicKey == equivocatingValidator) {
      // This is a special case as the justificationBlock might be the equivocation child
      if (justificationBlock.getHeader.validatorBlockSeqNum > equivocationBaseBlockSeqNum) {
        addEquivocationChild[F](
          justificationBlock,
          equivocationBaseBlockSeqNum,
          equivocationChildren
        )
      } else {
        equivocationChildren.pure[F]
      }
    } else {
      // Latest message according to the justificationBlock
      val maybeLatestEquivocatingValidatorBlockHash: Option[BlockHash] =
        toLatestMessageHashes(justificationBlock.getHeader.justifications)
          .get(equivocatingValidator)
      maybeLatestEquivocatingValidatorBlockHash match {
        case Some(blockHash) =>
          for {
            latestEquivocatingValidatorBlock <- ProtoUtil.unsafeGetBlock[F](blockHash)
            updatedEquivocationChildren <- if (latestEquivocatingValidatorBlock.getHeader.validatorBlockSeqNum > equivocationBaseBlockSeqNum) {
                                            addEquivocationChild[F](
                                              latestEquivocatingValidatorBlock,
                                              equivocationBaseBlockSeqNum,
                                              equivocationChildren
                                            )
                                          } else {
                                            equivocationChildren.pure[F]
                                          }
          } yield updatedEquivocationChildren
        case None =>
          throw new Exception(
            "justificationBlock is missing justification pointers to equivocatingValidator even though justificationBlock isn't a part of equivocationDetectedBlockHashes for this equivocation record."
          )
      }
    }

  private def addEquivocationChild[F[_]: Monad: BlockStore](
      justificationBlock: Block,
      equivocationBaseBlockSeqNum: SequenceNumber,
      equivocationChildren: Set[Block]
  ): F[Set[Block]] =
    for {
      maybeJustificationParentWithSeqNum <- findCreatorJustificationAncestorWithSeqNum[F](
                                             justificationBlock,
                                             equivocationBaseBlockSeqNum + 1
                                           )
      updatedEquivocationChildren = maybeJustificationParentWithSeqNum match {
        case Some(equivocationChild) => equivocationChildren + equivocationChild
        case None =>
          throw new Exception(
            "creator justification ancestor with lower sequence number hasn't been added to the blockDAG yet."
          )
      }
    } yield updatedEquivocationChildren
}
