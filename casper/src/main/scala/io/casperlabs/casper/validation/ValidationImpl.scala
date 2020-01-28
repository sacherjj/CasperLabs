package io.casperlabs.casper.validation

import cats.Applicative
import cats.implicits._
import cats.mtl.FunctorRaise
import cats.data.NonEmptyList
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.equivocations.EquivocationDetector
import io.casperlabs.casper.util.ProtoUtil.bonds
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{StateHash, TransformMap}
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Weight
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation
import Validation._
import cats.effect.Sync
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.smartcontracts.ExecutionEngineService.CommitResult

object ValidationImpl {
  def apply[F[_]](implicit ev: ValidationImpl[F]): Validation[F] = ev

  implicit val metricsSource = CasperMetricsSource / "validation"

  def metered[F[_]: Sync: FunctorRaise[*[_], InvalidBlock]: Log: Time: Metrics]: Validation[F] = {

    val underlying = new ValidationImpl[F]

    new Validation[F] {
      override def neglectedInvalidBlock(
          block: Block,
          invalidBlockTracker: Set[BlockHash]
      ): F[Unit] =
        Metrics[F].timer("neglectedInvalidBlock")(
          underlying.neglectedInvalidBlock(block, invalidBlockTracker)
        )

      override def parents(b: Block, dag: DagRepresentation[F])(
          implicit bs: BlockStorage[F]
      ): F[ExecEngineUtil.MergeResult[TransformMap, Block]] =
        Metrics[F].timer("parents")(underlying.parents(b, dag))

      override def transactions(
          block: Block,
          preStateHash: StateHash,
          effects: BlockEffects
      )(
          implicit ee: ExecutionEngineService[F],
          bs: BlockStorage[F],
          clp: CasperLabsProtocol[F]
      ): F[Unit] =
        Metrics[F].timer("transactions")(underlying.transactions(block, preStateHash, effects))

      override def blockFull(
          block: Block,
          dag: DagRepresentation[F],
          chainName: String,
          maybeGenesis: Option[Block]
      )(
          implicit bs: BlockStorage[F],
          versions: CasperLabsProtocol[F],
          compiler: Fs2Compiler[F]
      ): F[Unit] =
        Metrics[F].timer("blockFull")(underlying.blockFull(block, dag, chainName, maybeGenesis))

      override def blockSummary(summary: BlockSummary, chainName: String)(
          implicit versions: CasperLabsProtocol[F]
      ): F[Unit] =
        Metrics[F].timer("blockSummary")(underlying.blockSummary(summary, chainName))
    }
  }
}

class ValidationImpl[F[_]: Sync: FunctorRaise[*[_], InvalidBlock]: Log: Time: Metrics]
    extends Validation[F] {
  import io.casperlabs.models.BlockImplicits._

  type Data        = Array[Byte]
  type BlockHeight = Long

  /** Check the block without executing deploys. */
  override def blockFull(
      block: Block,
      dag: DagRepresentation[F],
      chainName: String,
      maybeGenesis: Option[Block]
  )(
      implicit bs: BlockStorage[F],
      versions: CasperLabsProtocol[F],
      compiler: Fs2Compiler[F]
  ): F[Unit] = {
    val summary = BlockSummary(block.blockHash, block.header, block.signature)
    for {
      _ <- checkDroppable(
            if (block.body.isEmpty)
              ignore[F](block.blockHash, s"block body is missing.").as(false)
            else true.pure[F],
            // Validate that the sender is a bonded validator.
            maybeGenesis.fold(summary.isGenesisLike.pure[F]) { _ =>
              blockSender[F](summary)
            }
          )
      _ <- blockSummary(summary, chainName)
      // Checks that need dependencies.
      _ <- missingBlocks[F](summary)
      _ <- timestamp[F](summary)
      _ <- blockRank[F](summary, dag)
      _ <- validatorPrevBlockHash[F](summary, dag)
      _ <- sequenceNumber[F](summary, dag)
      _ <- swimlane[F](summary, dag)
      // TODO: Validate that blocks only have block parents and ballots have a single parent which is a block.
      // Checks that need the body.
      _ <- blockHash[F](block)
      _ <- deployCount[F](block)
      _ <- deployHashes[F](block)
      _ <- deploySignatures[F](block)
      _ <- deployHeaders[F](block, dag, chainName)
      _ <- deployUniqueness[F](block, dag)
    } yield ()
  }

  /**
    * Checks that the parents of `b` were chosen correctly according to the
    * forkchoice rule. This is done by using the justifications of `b` as the
    * set of latest messages, so the justifications must be fully explicit.
    * For multi-parent blocks this requires doing commutativity checking, so
    * the combined effect of all parents except the first (i.e. the effect
    * which would need to be applied to the first parent's post-state to
    * obtain the pre-state of `b`) is given as the return value in order to
    * avoid repeating work downstream.
    */
  override def parents(
      b: Block,
      dag: DagRepresentation[F]
  )(
      implicit bs: BlockStorage[F]
  ): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]] = {
    def printHashes(hashes: NonEmptyList[ByteString]) =
      hashes.toList.map(PrettyPrinter.buildString).mkString("[", ", ", "]")

    val latestMessagesHashes = ProtoUtil
      .getJustificationMsgHashes(b.getHeader.justifications)

    for {
      equivocators <- EquivocationDetector.detectVisibleFromJustifications(
                       dag,
                       latestMessagesHashes
                     )
      tipHashes <- Estimator
                    .tips[F](dag, b.getHeader.keyBlockHash, latestMessagesHashes, equivocators)
      _                    <- Log[F].debug(s"Estimated tips are ${printHashes(tipHashes) -> "tips"}")
      tips                 <- tipHashes.traverse(ProtoUtil.unsafeGetBlock[F])
      merged               <- ExecEngineUtil.merge[F](tips, dag)
      computedParentHashes = merged.parents.map(_.blockHash)
      parentHashes         = ProtoUtil.parentHashes(b)
      _ <- if (parentHashes.isEmpty)
            raise(InvalidParents)
          else if (parentHashes == computedParentHashes)
            Applicative[F].unit
          else {
            val parentsString =
              parentHashes.map(PrettyPrinter.buildString).mkString(",")
            val estimateString =
              computedParentHashes.map(PrettyPrinter.buildString).mkString(",")
            val justificationString = latestMessagesHashes.values
              .map(hashes => hashes.map(PrettyPrinter.buildString).mkString("[", ",", "]"))
              .mkString(",")
            reject[F](
              b,
              InvalidParents,
              s"block parents $parentsString did not match estimate $estimateString based on justification $justificationString."
            )
          }
    } yield merged
  }

  // Validates whether received block is valid (according to that nodes logic):
  // 1) Validates whether pre state hashes match
  // 2) Runs deploys from the block
  // 3) Validates whether post state hashes match
  // 4) Validates whether bonded validators, as at the end of executing the block, match.
  override def transactions(
      block: Block,
      preStateHash: StateHash,
      blockEffects: BlockEffects
  )(
      implicit ee: ExecutionEngineService[F],
      bs: BlockStorage[F],
      clp: CasperLabsProtocol[F]
  ): F[Unit] = {
    val blockPreState  = ProtoUtil.preStateHash(block)
    val blockPostState = ProtoUtil.postStateHash(block)
    if (preStateHash == blockPreState) {
      for {
        possibleCommitResult <- ExecEngineUtil
                                 .commitEffects[F](
                                   preStateHash,
                                   block.getHeader.getProtocolVersion,
                                   blockEffects
                                 )
                                 .attempt
        //TODO: distinguish "internal errors" and "user errors"
        _ <- possibleCommitResult match {
              case Left(ex) =>
                Log[F].error(
                  s"Could not commit effects of ${PrettyPrinter.buildString(block.blockHash) -> "block"}: $ex"
                ) *>
                  raise(InvalidTransaction)
              case Right(commitResult) =>
                for {
                  _ <- reject[F](block, InvalidPostStateHash, "invalid post state hash")
                        .whenA(commitResult.postStateHash != blockPostState)
                  _ <- bondsCache[F](block, commitResult.bondedValidators)
                } yield ()
            }
      } yield ()
    } else {
      raise(InvalidPreStateHash)
    }
  }

  /**
    * If block contains an invalid justification block B and the creator of B is still bonded,
    * return a RejectableBlock. Otherwise return an IncludeableBlock.
    */
  override def neglectedInvalidBlock(
      block: Block,
      invalidBlockTracker: Set[BlockHash]
  ): F[Unit] = {
    val invalidJustifications = block.justifications.filter(
      justification => invalidBlockTracker.contains(justification.latestBlockHash)
    )
    val neglectedInvalidJustification = invalidJustifications.exists { justification =>
      val slashedValidatorBond =
        bonds(block).find(_.validatorPublicKey == justification.validatorPublicKey)
      slashedValidatorBond match {
        case Some(bond) => Weight(bond.stake) > 0
        case None       => false
      }
    }
    if (neglectedInvalidJustification) {
      reject[F](block, NeglectedInvalidBlock, "Neglected invalid justification.")
    } else {
      Applicative[F].unit
    }
  }

  /** Validate just the BlockSummary, assuming we don't have the block yet, or all its dependencies.
    * We can check that all the fields are present, the signature is fine, etc.
    * We'll need the full body to validate the block properly but this preliminary check can prevent
    * obviously corrupt data from being downloaded. */
  override def blockSummary(
      summary: BlockSummary,
      chainName: String
  )(implicit versions: CasperLabsProtocol[F]): F[Unit] = {
    val treatAsGenesis = summary.isGenesisLike
    for {
      _ <- checkDroppable[F](
            formatOfFields[F](summary, treatAsGenesis),
            version(
              summary,
              CasperLabsProtocol[F].versionAt(_)
            ),
            if (!treatAsGenesis) blockSignature[F](summary) else true.pure[F]
          )
      _ <- summaryHash[F](summary)
      _ <- chainIdentifier[F](summary, chainName)
      _ <- ballot(summary)
    } yield ()
  }

  // Validates that a message that is supposed to be a ballot adheres to ballot's specification.
  private def ballot(
      b: BlockSummary
  ): F[Unit] =
    FunctorRaise[F, InvalidBlock]
      .raise[Unit](InvalidTargetHash)
      .whenA(b.getHeader.messageType.isBallot && b.getHeader.parentHashes.size != 1)

}
