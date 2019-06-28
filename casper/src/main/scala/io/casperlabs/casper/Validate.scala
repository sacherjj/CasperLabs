package io.casperlabs.casper

import cats.implicits._
import cats.mtl.FunctorRaise
import cats.{Applicative, ApplicativeError, Functor, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus.{state, Block, BlockSummary, Bond}
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, ProtoUtil}
import io.casperlabs.casper.util.ProtoUtil.bonds
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS, Signature}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.ipc
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService

import scala.util.{Success, Try}

object Validate {
  type Data        = Array[Byte]
  type BlockHeight = Long

  type RaiseValidationError[F[_]] = FunctorRaise[F, InvalidBlock]
  object RaiseValidationError {
    def apply[F[_]](implicit ev: RaiseValidationError[F]): RaiseValidationError[F] = ev
  }

  val DRIFT = 15000 // 15 seconds

  def raiseValidateErrorThroughApplicativeError[F[_]: ApplicativeError[?[_], Throwable]]
      : FunctorRaise[F, InvalidBlock] =
    new FunctorRaise[F, InvalidBlock] {
      override val functor: Functor[F] =
        Functor[F]

      override def raise[A](e: InvalidBlock): F[A] =
        ValidateErrorWrapper(e).raiseError[F, A]
    }

  final case class ValidateErrorWrapper(status: InvalidBlock) extends Exception(status.toString)

  // Wrapper for the tests that were originally outside the `attemptAdd` method
  // and meant the block was not getting saved.
  final case class DropErrorWrapper(status: InvalidBlock) extends Exception

  implicit class BlockSummaryOps(summary: BlockSummary) {
    def isGenesisLike =
      summary.getHeader.parentHashes.isEmpty &&
        summary.getHeader.validatorPublicKey.isEmpty &&
        summary.getSignature.sig.isEmpty
  }

  private implicit val logSource: LogSource = LogSource(this.getClass)

  private def checkDroppable[F[_]: MonadThrowable](checks: F[Boolean]*): F[Unit] =
    checks.toList
      .traverse(identity)
      .map(_.forall(identity))
      .ifM(
        ().pure[F],
        MonadThrowable[F]
          .raiseError[Unit](DropErrorWrapper(InvalidUnslashableBlock))
      )

  def signatureVerifiers(sigAlgorithm: String): Option[(Data, Signature, PublicKey) => Boolean] =
    sigAlgorithm match {
      case SignatureAlgorithm(sa) => Some((data, sig, pub) => sa.verify(data, sig, pub))
      case _                      => None
    }

  def signature(d: Data, sig: protocol.Signature): Boolean =
    signatureVerifiers(sig.algorithm).fold(false) { verify =>
      verify(d, Signature(sig.sig.toByteArray), PublicKey(sig.publicKey.toByteArray))
    }

  def ignore(block: Block, reason: String): String =
    ignore(block.blockHash, reason)

  def ignore(block: BlockSummary, reason: String): String =
    ignore(block.blockHash, reason)

  def ignore(blockHash: ByteString, reason: String): String =
    s"Ignoring block ${PrettyPrinter.buildString(blockHash)} because $reason"

  def approvedBlock[F[_]: Applicative: Log](
      a: ApprovedBlock,
      requiredValidators: Set[PublicKeyBS]
  ): F[Boolean] = {
    val maybeSigData = for {
      c     <- a.candidate
      bytes = c.toByteArray
    } yield Blake2b256.hash(bytes)

    val requiredSigs = a.candidate.map(_.requiredSigs).getOrElse(0)

    maybeSigData match {
      case Some(sigData) =>
        val validatedSigs =
          (for {
            s      <- a.sigs
            verify <- signatureVerifiers(s.algorithm)
            pk     = s.publicKey
            if verify(sigData, Signature(s.sig.toByteArray), PublicKey(pk.toByteArray))
          } yield pk).toSet

        if (validatedSigs.size >= requiredSigs && requiredValidators.forall(validatedSigs.contains))
          true.pure[F]
        else
          Log[F]
            .warn("Received invalid ApprovedBlock message not containing enough valid signatures.")
            .map(_ => false)

      case None =>
        Log[F]
          .warn("Received invalid ApprovedBlock message not containing any candidate.")
          .map(_ => false)
    }
  }

  /** Validate just the BlockSummary, assuming we don't have the block yet, or all its dependencies.
    * We can check that all the fields are present, the signature is fine, etc.
    * We'll need the full body to validate the block properly but this preliminary check can prevent
    * obviously corrupt data from being downloaded. */
  def blockSummary[F[_]: MonadThrowable: Log: RaiseValidationError](
      summary: BlockSummary,
      chainId: String
  ): F[Unit] = {
    val treatAsGenesis = summary.isGenesisLike
    for {
      _ <- checkDroppable(
            Validate.formatOfFields[F](summary, treatAsGenesis),
            Validate.version[F](
              summary,
              CasperLabsProtocolVersions.thresholdsVersionMap.versionAt
            ),
            if (!treatAsGenesis) Validate.blockSignature[F](summary) else true.pure[F]
          )
      _ <- Validate.summaryHash[F](summary)
      _ <- Validate.chainIdentifier[F](summary, chainId)
    } yield ()
  }

  /** Check the block without executing deploys. */
  def blockFull[F[_]: MonadThrowable: Log: Time: BlockStore: RaiseValidationError](
      block: Block,
      dag: BlockDagRepresentation[F],
      chainId: String,
      maybeGenesis: Option[Block]
  ): F[Unit] = {
    val summary = BlockSummary(block.blockHash, block.header, block.signature)
    for {
      _ <- checkDroppable(
            if (block.body.isEmpty)
              Log[F].warn(ignore(block, s"block body is missing.")) *> false.pure[F]
            else true.pure[F],
            // Validate that the sender is a bonded validator.
            maybeGenesis.fold(summary.isGenesisLike.pure[F]) { _ =>
              Validate.blockSender[F](summary)
            }
          )
      _ <- Validate.blockSummary[F](summary, chainId)
      // Checks that need dependencies.
      _ <- Validate.missingBlocks[F](summary, dag)
      _ <- Validate.timestamp[F](summary, dag)
      _ <- Validate.blockNumber[F](summary, dag)
      _ <- Validate.sequenceNumber[F](summary, dag)
      _ <- maybeGenesis.filterNot(_.blockHash == summary.blockHash).fold(().pure[F]) { genesis =>
            Validate.justificationFollows[F](summary, genesis, dag) *>
              Validate.justificationRegressions[F](summary, genesis, dag)
          }
      // Checks that need the body.
      _ <- Validate.blockHash[F](block)
      _ <- Validate.deployCount[F](block)
    } yield ()
  }

  def blockSignature[F[_]: Applicative: Log](b: BlockSummary): F[Boolean] =
    signatureVerifiers(b.getSignature.sigAlgorithm) map { verify =>
      Try(
        verify(
          b.blockHash.toByteArray,
          Signature(b.getSignature.sig.toByteArray),
          PublicKey(b.getHeader.validatorPublicKey.toByteArray)
        )
      ) match {
        case Success(true) => true.pure[F]
        case _             => Log[F].warn(ignore(b, "signature is invalid.")).map(_ => false)
      }
    } getOrElse {
      for {
        _ <- Log[F].warn(
              ignore(b, s"signature algorithm '${b.getSignature.sigAlgorithm}' is unsupported.")
            )
      } yield false
    }

  def deploySignature[F[_]: Monad: Log](d: consensus.Deploy): F[Boolean] =
    if (d.approvals.isEmpty) {
      Log[F].warn(
        s"Deploy ${PrettyPrinter.buildString(d.deployHash)} has no signatures."
      ) *> false.pure[F]
    } else {
      for {
        signatoriesVerified <- d.approvals.toList
                                .traverse { a =>
                                  signatureVerifiers(a.getSignature.sigAlgorithm)
                                    .map { verify =>
                                      Try {
                                        verify(
                                          d.deployHash.toByteArray,
                                          Signature(a.getSignature.sig.toByteArray),
                                          PublicKey(a.approverPublicKey.toByteArray)
                                        )
                                      } match {
                                        case Success(true) =>
                                          true.pure[F]
                                        case _ =>
                                          Log[F].warn(
                                            s"Signature of deploy ${PrettyPrinter.buildString(d.deployHash)} is invalid."
                                          ) *> false.pure[F]
                                      }
                                    } getOrElse {
                                    Log[F].warn(
                                      s"Signature algorithm ${a.getSignature.sigAlgorithm} of deploy ${PrettyPrinter
                                        .buildString(d.deployHash)} is unsupported."
                                    ) *> false.pure[F]
                                  }
                                }
                                .map(_.forall(identity))
        keysMatched = d.approvals.toList.exists { a =>
          a.approverPublicKey == d.getHeader.accountPublicKey
        }
        _ <- Log[F]
              .warn(
                s"Signatories of deploy ${PrettyPrinter.buildString(d.deployHash)} don't contain at least one signature with key equal to public key: ${PrettyPrinter
                  .buildString(d.getHeader.accountPublicKey)}"
              )
              .whenA(!keysMatched)
      } yield signatoriesVerified && keysMatched
    }

  def blockSender[F[_]: Monad: Log: BlockStore](
      block: BlockSummary
  ): F[Boolean] =
    for {
      weight <- ProtoUtil.weightFromSender[F](block.getHeader)
      result <- if (weight > 0) true.pure[F]
               else
                 for {
                   _ <- Log[F].warn(
                         ignore(
                           block,
                           s"block creator ${PrettyPrinter.buildString(block.getHeader.validatorPublicKey)} has 0 weight."
                         )
                       )
                 } yield false
    } yield result

  def formatOfFields[F[_]: Monad: Log](
      b: BlockSummary,
      treatAsGenesis: Boolean = false
  ): F[Boolean] =
    if (b.blockHash.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block hash is empty."))
      } yield false
    } else if (b.header.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block header is missing."))
      } yield false
    } else if (b.getSignature.sig.isEmpty && !treatAsGenesis) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature is empty."))
      } yield false
    } else if (!b.getSignature.sig.isEmpty && treatAsGenesis) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature is not empty on Genesis."))
      } yield false
    } else if (b.getSignature.sigAlgorithm.isEmpty && !treatAsGenesis) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature algorithm is not empty on Genesis."))
      } yield false
    } else if (!b.getSignature.sigAlgorithm.isEmpty && treatAsGenesis) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature algorithm is empty."))
      } yield false
    } else if (b.getHeader.chainId.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block chain identifier is empty."))
      } yield false
    } else if (b.getHeader.getState.postStateHash.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block post state hash is empty."))
      } yield false
    } else if (b.getHeader.bodyHash.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block new code hash is empty."))
      } yield false
    } else {
      true.pure[F]
    }

  // Validates whether block was built using correct protocol version.
  def version[F[_]: Applicative: Log](
      b: BlockSummary,
      m: BlockHeight => state.ProtocolVersion
  ): F[Boolean] = {
    val blockVersion = b.getHeader.protocolVersion
    val blockHeight  = b.getHeader.rank
    val version      = m(blockHeight).value
    if (blockVersion == version) {
      true.pure[F]
    } else {
      Log[F].warn(
        ignore(
          b,
          s"Received block version $blockVersion, expected version $version."
        )
      ) *> false.pure[F]
    }
  }

  /**
    * Works with either efficient justifications or full explicit justifications
    */
  def missingBlocks[F[_]: Monad: Log: BlockStore: RaiseValidationError](
      block: BlockSummary,
      dag: BlockDagRepresentation[F]
  ): F[Unit] =
    for {
      parentsPresent <- block.getHeader.parentHashes.toList.forallM(p => BlockStore[F].contains(p))
      justificationsPresent <- block.getHeader.justifications.toList
                                .forallM(j => BlockStore[F].contains(j.latestBlockHash))
      _ <- RaiseValidationError[F]
            .raise[Unit](MissingBlocks)
            .whenA(!parentsPresent || !justificationsPresent)
    } yield ()

  // This is not a slashable offence
  def timestamp[F[_]: MonadThrowable: Log: Time: BlockStore: RaiseValidationError](
      b: BlockSummary,
      dag: BlockDagRepresentation[F]
  ): F[Unit] =
    for {
      currentTime  <- Time[F].currentMillis
      timestamp    = b.getHeader.timestamp
      beforeFuture = currentTime + DRIFT >= timestamp
      latestParentTimestamp <- b.getHeader.parentHashes.toList.foldM(0L) {
                                case (latestTimestamp, parentHash) =>
                                  ProtoUtil
                                    .unsafeGetBlockSummary[F](parentHash)
                                    .map(parent => {
                                      val timestamp =
                                        parent.header.fold(latestTimestamp)(_.timestamp)
                                      math.max(latestTimestamp, timestamp)
                                    })
                              }
      afterLatestParent = timestamp >= latestParentTimestamp
      _ <- if (beforeFuture && afterLatestParent) {
            Applicative[F].unit
          } else {
            for {
              _ <- Log[F].warn(
                    ignore(
                      b,
                      s"block timestamp $timestamp is not between latest parent block time and current time."
                    )
                  )
              _ <- RaiseValidationError[F].raise[Unit](InvalidUnslashableBlock)
            } yield ()
          }
    } yield ()

  // Agnostic of non-parent justifications
  def blockNumber[F[_]: MonadThrowable: Log: RaiseValidationError](
      b: BlockSummary,
      dag: BlockDagRepresentation[F]
  ): F[Unit] =
    for {
      parents <- b.getHeader.parentHashes.toList.traverse { parentHash =>
                  dag.lookup(parentHash).flatMap {
                    MonadThrowable[F].fromOption(
                      _,
                      new Exception(
                        s"Block dag store was missing ${PrettyPrinter.buildString(parentHash)}."
                      )
                    )
                  }
                }
      maxBlockNumber = parents.foldLeft(-1L) {
        case (acc, p) => math.max(acc, p.rank)
      }
      number = b.getHeader.rank
      result = maxBlockNumber + 1 == number
      _ <- if (result) {
            Applicative[F].unit
          } else {
            val logMessage =
              if (parents.isEmpty)
                s"block number $number is not zero, but block has no parents."
              else
                s"block number $number is not one more than maximum parent number $maxBlockNumber."
            for {
              _ <- Log[F].warn(ignore(b, logMessage))
              _ <- RaiseValidationError[F].raise[Unit](InvalidBlockNumber)
            } yield ()
          }
    } yield ()

  /**
    * Works with either efficient justifications or full explicit justifications.
    * Specifically, with efficient justifications, if a block B doesn't update its
    * creator justification, this check will fail as expected. The exception is when
    * B's creator justification is the genesis block.
    */
  def sequenceNumber[F[_]: MonadThrowable: Log: BlockStore: RaiseValidationError](
      b: BlockSummary,
      dag: BlockDagRepresentation[F]
  ): F[Unit] =
    for {
      creatorJustificationSeqNumber <- ProtoUtil.creatorJustification(b.getHeader).foldM(-1) {
                                        case (_, Justification(_, latestBlockHash)) =>
                                          dag.lookup(latestBlockHash).flatMap {
                                            case Some(meta) =>
                                              meta.validatorBlockSeqNum.pure[F]

                                            case None =>
                                              MonadThrowable[F].raiseError[Int](
                                                new Exception(
                                                  s"Latest block hash ${PrettyPrinter.buildString(latestBlockHash)} is missing from block dag store."
                                                )
                                              )
                                          }
                                      }
      number = b.getHeader.validatorBlockSeqNum
      ok     = creatorJustificationSeqNumber + 1 == number
      _ <- if (ok) {
            Applicative[F].unit
          } else {
            for {
              _ <- Log[F].warn(
                    ignore(
                      b,
                      s"seq number $number is not one more than creator justification number $creatorJustificationSeqNumber."
                    )
                  )
              _ <- RaiseValidationError[F].raise[Unit](InvalidSequenceNumber)
            } yield ()
          }
    } yield ()

  // Agnostic of justifications
  def chainIdentifier[F[_]: Monad: Log: RaiseValidationError](
      b: BlockSummary,
      chainId: String
  ): F[Unit] =
    if (b.getHeader.chainId == chainId) {
      Applicative[F].unit
    } else {
      for {
        _ <- Log[F].warn(
              ignore(b, s"got chain identifier ${b.getHeader.chainId} while $chainId was expected.")
            )
        _ <- RaiseValidationError[F].raise[Unit](InvalidChainId)
      } yield ()
    }

  def deployHash[F[_]: Monad: Log](d: consensus.Deploy): F[Boolean] = {
    val bodyHash   = ProtoUtil.protoHash(d.getBody)
    val deployHash = ProtoUtil.protoHash(d.getHeader)
    val ok         = bodyHash == d.getHeader.bodyHash && deployHash == d.deployHash
    Log[F].warn(s"Invalid deploy hash ${PrettyPrinter.buildString(d.deployHash)}").whenA(!ok) *>
      ok.pure[F]
  }

  def blockHash[F[_]: Monad: RaiseValidationError: Log](
      b: Block
  ): F[Unit] = {
    val blockHashComputed = ProtoUtil.protoHash(b.getHeader)
    val bodyHashComputed  = ProtoUtil.protoHash(b.getBody)

    if (b.blockHash == blockHashComputed &&
        b.getHeader.bodyHash == bodyHashComputed) {
      Applicative[F].unit
    } else {
      def show(hash: ByteString) = PrettyPrinter.buildString(hash)
      for {
        _ <- Log[F].warn(ignore(b, s"block hash does not match to computed value."))
        _ <- Log[F]
              .warn(
                s"Expected block hash ${show(blockHashComputed)}; got ${show(b.blockHash)}"
              )
              .whenA(b.blockHash != blockHashComputed)
        _ <- Log[F]
              .warn(
                s"Expected body hash ${show(bodyHashComputed)}; got ${show(b.getHeader.bodyHash)}"
              )
              .whenA(b.getHeader.bodyHash != bodyHashComputed)
        _ <- RaiseValidationError[F].raise[Unit](InvalidBlockHash)
      } yield ()
    }
  }

  def summaryHash[F[_]: Monad: Log: RaiseValidationError](
      b: BlockSummary
  ): F[Unit] = {
    val blockHashComputed = ProtoUtil.protoHash(b.getHeader)
    val ok                = b.blockHash == blockHashComputed
    (Log[F].warn(s"Invalid block hash ${PrettyPrinter.buildString(b.blockHash)}") *>
      RaiseValidationError[F].raise[Unit](InvalidBlockHash)).whenA(!ok)
  }

  def deployCount[F[_]: Monad: Log: RaiseValidationError](
      b: Block
  ): F[Unit] =
    if (b.getHeader.deployCount == b.getBody.deploys.length) {
      Applicative[F].unit
    } else {
      for {
        _ <- Log[F].warn(ignore(b, s"block deploy count does not match to the amount of deploys."))
        _ <- RaiseValidationError[F].raise[Unit](InvalidDeployCount)
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
  def parents[F[_]: MonadThrowable: Log: BlockStore: RaiseValidationError](
      b: Block,
      lastFinalizedBlockHash: BlockHash,
      genesisBlockHash: BlockHash,
      dag: BlockDagRepresentation[F]
  ): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]] = {
    val maybeParentHashes = ProtoUtil.parentHashes(b)
    val parentHashes = maybeParentHashes match {
      case hashes if hashes.isEmpty => Seq(lastFinalizedBlockHash)
      case hashes                   => hashes
    }

    def printHashes(hashes: Iterable[ByteString]) =
      hashes.map(PrettyPrinter.buildString(_)).mkString("[", ", ", "]")

    for {
      latestMessagesHashes <- ProtoUtil.toLatestMessageHashes(b.getHeader.justifications).pure[F]
      tipHashes            <- Estimator.tips[F](dag, genesisBlockHash, latestMessagesHashes)
      _                    <- Log[F].debug(s"Estimated tips are ${printHashes(tipHashes)}")
      tips                 <- tipHashes.toVector.traverse(ProtoUtil.unsafeGetBlock[F])
      merged               <- ExecEngineUtil.merge[F](tips, dag)
      computedParentHashes = merged.parents.map(_.blockHash)
      parentHashes         = ProtoUtil.parentHashes(b)
      _ <- if (parentHashes.isEmpty)
            RaiseValidationError[F].raise[Unit](InvalidParents)
          else if (parentHashes == computedParentHashes)
            Applicative[F].unit
          else {
            val parentsString =
              parentHashes.map(hash => PrettyPrinter.buildString(hash)).mkString(",")
            val estimateString =
              computedParentHashes.map(hash => PrettyPrinter.buildString(hash)).mkString(",")
            val justificationString = latestMessagesHashes.values
              .map(hash => PrettyPrinter.buildString(hash))
              .mkString(",")
            val message =
              s"block parents ${parentsString} did not match estimate ${estimateString} based on justification ${justificationString}."
            for {
              _ <- Log[F].warn(
                    ignore(
                      b,
                      message
                    )
                  )
              _ <- RaiseValidationError[F].raise[Unit](InvalidParents)
            } yield ()
          }
    } yield merged
  }

  /*
   * This check must come before Validate.parents
   */
  def justificationFollows[F[_]: MonadThrowable: Log: BlockStore: RaiseValidationError](
      b: BlockSummary,
      genesis: Block,
      dag: BlockDagRepresentation[F]
  ): F[Unit] = {
    val raiseNoParent =
      Log[F].warn(ignore(b, s"doesn't have a parent.")) *>
        RaiseValidationError[F].raise[Unit](InvalidFollows)
    for {
      _                   <- raiseNoParent.whenA(b.getHeader.parentHashes.isEmpty)
      justifiedValidators = b.getHeader.justifications.map(_.validatorPublicKey).toSet
      mainParentHash      = b.getHeader.parentHashes.head
      mainParent          <- ProtoUtil.unsafeGetBlockSummary[F](mainParentHash)
      bondedValidators    = ProtoUtil.bonds(mainParent).map(_.validatorPublicKey).toSet
      status <- if (bondedValidators == justifiedValidators) {
                 Applicative[F].unit
               } else {
                 val justifiedValidatorsPP = justifiedValidators.map(PrettyPrinter.buildString(_))
                 val bondedValidatorsPP    = bondedValidators.map(PrettyPrinter.buildString(_))
                 for {
                   _ <- Log[F].warn(
                         ignore(
                           b,
                           s"the justified validators, ${justifiedValidatorsPP}, do not match the bonded validators, ${bondedValidatorsPP}."
                         )
                       )
                   _ <- RaiseValidationError[F].raise[Unit](InvalidFollows)
                 } yield ()
               }
    } yield status
  }

  /*
   * When we switch between equivocation forks for a slashed validator, we will potentially get a
   * justification regression that is valid. We cannot ignore this as the creator only drops the
   * justification block created by the equivocator on the following block.
   * Hence, we ignore justification regressions involving the block's sender and
   * let checkEquivocations handle it instead.
   */
  def justificationRegressions[F[_]: MonadThrowable: Log: BlockStore: RaiseValidationError](
      b: BlockSummary,
      genesis: Block,
      dag: BlockDagRepresentation[F]
  ): F[Unit] = {
    val latestMessagesOfBlock = ProtoUtil.toLatestMessageHashes(b.getHeader.justifications)
    for {
      maybeLatestMessage <- dag.latestMessage(b.getHeader.validatorPublicKey)
      maybeLatestMessagesFromSenderView = maybeLatestMessage.map(
        bm => ProtoUtil.toLatestMessageHashes(bm.justifications)
      )
      result <- maybeLatestMessagesFromSenderView match {
                 case Some(latestMessagesFromSenderView) =>
                   justificationRegressionsAux[F](
                     b,
                     latestMessagesOfBlock,
                     latestMessagesFromSenderView,
                     genesis
                   )
                 case None =>
                   // We cannot have a justification regression if we don't have a previous latest message from sender
                   Applicative[F].unit
               }
    } yield result
  }

  private def justificationRegressionsAux[F[_]: MonadThrowable: Log: BlockStore: FunctorRaise[
    ?[_],
    InvalidBlock
  ]](
      b: BlockSummary,
      latestMessagesOfBlock: Map[Validator, BlockHash],
      latestMessagesFromSenderView: Map[Validator, BlockHash],
      genesis: Block
  ): F[Unit] =
    for {
      containsJustificationRegression <- latestMessagesOfBlock.toList.existsM {
                                          case (validator, currentBlockJustificationHash) =>
                                            if (validator == b.getHeader.validatorPublicKey) {
                                              // We let checkEquivocations handle this case
                                              false.pure[F]
                                            } else {
                                              val previousBlockJustificationHash =
                                                latestMessagesFromSenderView.getOrElse(
                                                  validator,
                                                  genesis.blockHash
                                                )
                                              isJustificationRegression[F](
                                                currentBlockJustificationHash,
                                                previousBlockJustificationHash
                                              )
                                            }
                                        }
      _ <- if (containsJustificationRegression) {
            for {
              _ <- Log[F].warn(ignore(b, "block contains justification regressions."))
              _ <- RaiseValidationError[F].raise[Unit](JustificationRegression)
            } yield ()
          } else {
            Applicative[F].unit
          }
    } yield ()

  private def isJustificationRegression[F[_]: MonadThrowable: Log: BlockStore](
      currentBlockJustificationHash: BlockHash,
      previousBlockJustificationHash: BlockHash
  ): F[Boolean] =
    for {
      currentBlockJustification <- ProtoUtil
                                    .unsafeGetBlockSummary[F](currentBlockJustificationHash)
      previousBlockJustification <- ProtoUtil
                                     .unsafeGetBlockSummary[F](previousBlockJustificationHash)
    } yield
      if (currentBlockJustification.getHeader.validatorBlockSeqNum < previousBlockJustification.getHeader.validatorBlockSeqNum) {
        true
      } else {
        false
      }

  def transactions[F[_]: Monad: Log: BlockStore: ExecutionEngineService: FunctorRaise[
    ?[_],
    InvalidBlock
  ]](
      block: Block,
      dag: BlockDagRepresentation[F],
      preStateHash: StateHash,
      effects: Seq[ipc.TransformEntry]
  ): F[Unit] = {
    val blockPreState  = ProtoUtil.preStateHash(block)
    val blockPostState = ProtoUtil.postStateHash(block)
    if (preStateHash == blockPreState) {
      for {
        possiblePostState <- ExecutionEngineService[F].commit(
                              preStateHash,
                              effects
                            )
        //TODO: distinguish "internal errors" and "user errors"
        _ <- possiblePostState match {
              case Left(ex) =>
                Log[F].error(
                  s"Could not commit effects of block ${PrettyPrinter.buildString(block)}: $ex",
                  ex
                ) *>
                  RaiseValidationError[F].raise[Unit](InvalidTransaction)
              case Right(postStateHash) =>
                if (postStateHash == blockPostState) {
                  Applicative[F].unit
                } else {
                  RaiseValidationError[F].raise[Unit](InvalidPostStateHash)
                }
            }
      } yield ()
    } else {
      RaiseValidationError[F].raise[Unit](InvalidPreStateHash)
    }
  }

  /**
    * If block contains an invalid justification block B and the creator of B is still bonded,
    * return a RejectableBlock. Otherwise return an IncludeableBlock.
    */
  def neglectedInvalidBlock[F[_]: Monad: Log: RaiseValidationError](
      block: Block,
      invalidBlockTracker: Set[BlockHash]
  ): F[Unit] = {
    val invalidJustifications = block.getHeader.justifications.filter(
      justification => invalidBlockTracker.contains(justification.latestBlockHash)
    )
    val neglectedInvalidJustification = invalidJustifications.exists { justification =>
      val slashedValidatorBond =
        bonds(block).find(_.validatorPublicKey == justification.validatorPublicKey)
      slashedValidatorBond match {
        case Some(bond) => bond.stake > 0
        case None       => false
      }
    }
    if (neglectedInvalidJustification) {
      for {
        _ <- Log[F].warn("Neglected invalid justification.")
        _ <- RaiseValidationError[F].raise[Unit](NeglectedInvalidBlock)
      } yield ()
    } else {
      Applicative[F].unit
    }
  }

  def bondsCache[F[_]: Monad: Log: RaiseValidationError](
      b: Block,
      computedBonds: Seq[Bond]
  ): F[Unit] = {
    val bonds = ProtoUtil.bonds(b)
    ProtoUtil.postStateHash(b) match {
      case globalStateRootHash if !globalStateRootHash.isEmpty =>
        if (bonds.toSet == computedBonds.toSet) {
          Applicative[F].unit
        } else {
          for {
            _ <- Log[F].warn(
                  "Bonds in proof of stake contract do not match block's bond cache."
                )
            _ <- RaiseValidationError[F].raise[Unit](InvalidBondsCache)
          } yield ()
        }
      case _ =>
        for {
          _ <- Log[F].warn(s"Block ${PrettyPrinter.buildString(b)} is missing a post state hash.")
          _ <- RaiseValidationError[F].raise[Unit](InvalidBondsCache)
        } yield ()
    }
  }
}
