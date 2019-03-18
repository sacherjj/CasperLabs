package io.casperlabs.casper

import cats.effect.Sync
import cats.implicits._
import cats.mtl.FunctorRaise
import cats.{Applicative, Functor, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.protocol.{ApprovedBlock, BlockMessage, Bond, Justification}
import io.casperlabs.casper.util.ProtoUtil.bonds
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.ipc
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService

import scala.util.{Success, Try}

object Validate {
  type PublicKey = Array[Byte]
  type Data      = Array[Byte]
  type Signature = Array[Byte]

  type RaiseValidationError[F[_]] = FunctorRaise[F, InvalidBlock]
  object RaiseValidationError {
    def apply[F[_]](implicit ev: RaiseValidationError[F]): RaiseValidationError[F] = ev
  }

  def raiseValidateErrorThroughSync[F[_]: Sync]: FunctorRaise[F, InvalidBlock] =
    new FunctorRaise[F, InvalidBlock] {
      override val functor: Functor[F] =
        Functor[F]

      override def raise[A](e: InvalidBlock): F[A] =
        Sync[F].raiseError(ValidateErrorWrapper(e))
    }

  final case class ValidateErrorWrapper(status: InvalidBlock) extends Exception

  val DRIFT                                 = 15000 // 15 seconds
  private implicit val logSource: LogSource = LogSource(this.getClass)
  val signatureVerifiers: Map[String, (Data, Signature, PublicKey) => Boolean] =
    Map(
      "ed25519" -> Ed25519.verify
    )

  def signature(d: Data, sig: protocol.Signature): Boolean =
    signatureVerifiers.get(sig.algorithm).fold(false) { verify =>
      verify(d, sig.sig.toByteArray, sig.publicKey.toByteArray)
    }

  def ignore(b: BlockMessage, reason: String): String =
    s"Ignoring block ${PrettyPrinter.buildString(b.blockHash)} because $reason"

  def approvedBlock[F[_]: Applicative: Log](
      a: ApprovedBlock,
      requiredValidators: Set[ByteString]
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
            verify <- signatureVerifiers.get(s.algorithm)
            pk     = s.publicKey
            if verify(sigData, s.sig.toByteArray, pk.toByteArray)
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

  def blockSignature[F[_]: Applicative: Log](b: BlockMessage): F[Boolean] =
    signatureVerifiers
      .get(b.sigAlgorithm)
      .map(verify => {
        Try(verify(b.blockHash.toByteArray, b.sig.toByteArray, b.sender.toByteArray)) match {
          case Success(true) => true.pure[F]
          case _             => Log[F].warn(ignore(b, "signature is invalid.")).map(_ => false)
        }
      }) getOrElse {
      for {
        _ <- Log[F].warn(ignore(b, s"signature algorithm ${b.sigAlgorithm} is unsupported."))
      } yield false
    }

  def blockSender[F[_]: Monad: Log: BlockStore](
      b: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Boolean] =
    if (b == genesis) {
      true.pure[F] //genesis block has a valid sender
    } else {
      for {
        weight <- ProtoUtil.weightFromSender[F](b)
        result <- if (weight > 0) true.pure[F]
                 else
                   for {
                     _ <- Log[F].warn(
                           ignore(
                             b,
                             s"block creator ${PrettyPrinter.buildString(b.sender)} has 0 weight."
                           )
                         )
                   } yield false
      } yield result
    }

  def formatOfFields[F[_]: Monad: Log](b: BlockMessage): F[Boolean] =
    if (b.blockHash.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block hash is empty."))
      } yield false
    } else if (b.header.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block header is missing."))
      } yield false
    } else if (b.body.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block body is missing."))
      } yield false
    } else if (b.sig.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature is empty."))
      } yield false
    } else if (b.sigAlgorithm.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature algorithm is empty."))
      } yield false
    } else if (b.shardId.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block shard identifier is empty."))
      } yield false
    } else if (b.header.get.postStateHash.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block post state hash is empty."))
      } yield false
    } else if (b.header.get.deploysHash.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block new code hash is empty."))
      } yield false
    } else if (b.body.get.state.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block post state is missing."))
      } yield false
    } else {
      true.pure[F]
    }

  def version[F[_]: Applicative: Log](b: BlockMessage, version: Long): F[Boolean] = {
    val blockVersion = b.header.get.version
    if (blockVersion == version) {
      true.pure[F]
    } else {
      Log[F].warn(
        ignore(
          b,
          s"received block version $blockVersion is the expected version $version."
        )
      ) *> false.pure[F]
    }
  }

  /*
   * TODO: Double check ordering of validity checks
   * TODO: Add check for missing fields
   * TODO: Check that justifications follow from bonds of creator justification
   */
  def blockSummary[F[_]: Monad: Log: Time: BlockStore: RaiseValidationError](
      block: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F],
      shardId: String,
      lastFinalizedBlockHash: BlockHash
  ): F[Unit] =
    for {
      _ <- Validate.blockHash[F](block)
      _ <- Validate.deployCount[F](block)
      _ <- Validate.missingBlocks[F](block, dag)
      _ <- Validate.timestamp[F](block, dag)
      _ <- Validate.repeatDeploy[F](block, dag)
      _ <- Validate.blockNumber[F](block)
      _ <- Validate.justificationFollows[F](block, genesis, dag)
      _ <- Validate.parents[F](block, genesis, lastFinalizedBlockHash, dag)
      - <- Validate.sequenceNumber[F](block, dag)
      - <- Validate.justificationRegressions[F](block, genesis, dag)
      _ <- Validate.shardIdentifier[F](block, shardId)
    } yield ()

  /**
    * Works with either efficient justifications or full explicit justifications
    */
  def missingBlocks[F[_]: Monad: Log: BlockStore: RaiseValidationError](
      block: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Unit] =
    for {
      parentsPresent <- ProtoUtil.parentHashes(block).toList.forallM(p => BlockStore[F].contains(p))
      justificationsPresent <- block.justifications.toList
                                .forallM(j => BlockStore[F].contains(j.latestBlockHash))
      result <- if (parentsPresent && justificationsPresent) {
                 Applicative[F].unit
               } else {
                 for {
                   _ <- Log[F].debug(
                         s"Fetching missing dependencies for ${PrettyPrinter.buildString(block.blockHash)}."
                       )
                   _ <- RaiseValidationError[F].raise[Unit](MissingBlocks)
                 } yield ()
               }
    } yield result

  /**
    * Validate no deploy by the same (user, millisecond timestamp)
    * has been produced in the chain
    *
    * Agnostic of non-parent justifications
    */
  def repeatDeploy[F[_]: Monad: Log: BlockStore: RaiseValidationError](
      block: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Unit] = {
    val deployKeySet = (for {
      bd <- block.body.toList
      d  <- bd.deploys.flatMap(_.deploy)
    } yield (d.user, d.timestamp)).toSet

    for {
      initParents <- ProtoUtil.unsafeGetParents[F](block)
      duplicatedBlock <- DagOperations
                          .bfTraverseF[F, BlockMessage](initParents)(ProtoUtil.unsafeGetParents[F])
                          .find(
                            _.body.exists(
                              _.deploys
                                .flatMap(_.deploy)
                                .exists(p => deployKeySet.contains((p.user, p.timestamp)))
                            )
                          )
      _ <- duplicatedBlock match {
            case Some(b) =>
              for {
                _ <- Log[F].warn(
                      ignore(
                        block,
                        s"found deploy by the same (user, millisecond timestamp) produced in the block(${b.blockHash})"
                      )
                    )
                _ <- RaiseValidationError[F].raise[Unit](InvalidRepeatDeploy)
              } yield Left(InvalidRepeatDeploy)
            case None => Applicative[F].unit
          }
    } yield ()
  }

  // This is not a slashable offence
  def timestamp[F[_]: Monad: Log: Time: BlockStore: RaiseValidationError](
      b: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Unit] =
    for {
      currentTime  <- Time[F].currentMillis
      timestamp    = b.header.get.timestamp
      beforeFuture = currentTime + DRIFT >= timestamp
      latestParentTimestamp <- ProtoUtil.parentHashes(b).toList.foldM(0L) {
                                case (latestTimestamp, parentHash) =>
                                  ProtoUtil
                                    .unsafeGetBlock[F](parentHash)
                                    .map(parent => {
                                      val timestamp =
                                        parent.header.fold(latestTimestamp)(_.timestamp)
                                      math.max(latestTimestamp, timestamp)
                                    })
                              }
      afterLatestParent = timestamp >= latestParentTimestamp
      result <- if (beforeFuture && afterLatestParent) {
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
    } yield result

  // Agnostic of non-parent justifications
  def blockNumber[F[_]: Monad: Log: BlockStore: RaiseValidationError](
      b: BlockMessage
  ): F[Unit] =
    for {
      parents <- ProtoUtil.unsafeGetParents[F](b)
      maxBlockNumber = parents.foldLeft(-1L) {
        case (acc, p) => math.max(acc, ProtoUtil.blockNumber(p))
      }
      number = ProtoUtil.blockNumber(b)
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
  def sequenceNumber[F[_]: Monad: Log: BlockStore: RaiseValidationError](
      b: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Unit] =
    for {
      creatorJustificationSeqNumber <- ProtoUtil.creatorJustification(b).foldM(-1) {
                                        case (_, Justification(_, latestBlockHash)) =>
                                          for {
                                            latestBlock <- ProtoUtil
                                                            .unsafeGetBlock[F](latestBlockHash)
                                          } yield latestBlock.seqNum
                                      }
      number = b.seqNum
      result = creatorJustificationSeqNumber + 1 == number
      _ <- if (result) {
            Applicative[F].pure(Right(Valid))
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
  def shardIdentifier[F[_]: Monad: Log: BlockStore: RaiseValidationError](
      b: BlockMessage,
      shardId: String
  ): F[Unit] =
    if (b.shardId == shardId) {
      Applicative[F].unit
    } else {
      for {
        _ <- Log[F].warn(
              ignore(b, s"got shard identifier ${b.shardId} while $shardId was expected.")
            )
        _ <- RaiseValidationError[F].raise[Unit](InvalidShardId)
      } yield ()
    }

  def blockHash[F[_]: Monad: RaiseValidationError: Log](
      b: BlockMessage
  ): F[Unit] = {
    val blockHashComputed = ProtoUtil.hashSignedBlock(
      b.header.get,
      b.sender,
      b.sigAlgorithm,
      b.seqNum,
      b.shardId,
      b.extraBytes
    )
    val deployHashComputed    = ProtoUtil.protoSeqHash(b.body.get.deploys)
    val postStateHashComputed = ProtoUtil.protoHash(b.body.get.state.get)
    if (b.blockHash == blockHashComputed &&
        b.header.get.deploysHash == deployHashComputed &&
        b.header.get.postStateHash == postStateHashComputed) {
      Applicative[F].unit
    } else
      for {
        _ <- Log[F].warn(ignore(b, s"block hash does not match to computed value."))
        _ <- RaiseValidationError[F].raise[Unit](InvalidBlockHash)
      } yield ()
  }

  def deployCount[F[_]: Monad: Log: RaiseValidationError](
      b: BlockMessage
  ): F[Unit] =
    if (b.header.get.deployCount == b.body.get.deploys.length) {
      Applicative[F].unit
    } else {
      for {
        _ <- Log[F].warn(ignore(b, s"block deploy count does not match to the amount of deploys."))
        _ <- RaiseValidationError[F].raise[Unit](InvalidDeployCount)
      } yield ()
    }

  /**
    * Works only with fully explicit justifications.
    */
  def parents[F[_]: Monad: Log: BlockStore: RaiseValidationError](
      b: BlockMessage,
      genesis: BlockMessage,
      lastFinalizedBlockHash: BlockHash,
      dag: BlockDagRepresentation[F]
  ): F[Unit] = {
    val maybeParentHashes = ProtoUtil.parentHashes(b)
    val parentHashes = maybeParentHashes match {
      case hashes if hashes.isEmpty => Seq(lastFinalizedBlockHash)
      case hashes                   => hashes
    }

    def printHashes(hashes: Iterable[ByteString]) =
      hashes.map(PrettyPrinter.buildString(_)).mkString("[", ", ", "]")

    for {
      latestMessagesHashes <- ProtoUtil.toLatestMessageHashes(b.justifications).pure[F]
      estimate             <- Estimator.tips[F](dag, lastFinalizedBlockHash, latestMessagesHashes)
      _                    <- Log[F].debug(s"Estimated tips are ${printHashes(estimate.map(_.blockHash))}")
      computedParents      <- ProtoUtil.chooseNonConflicting[F](estimate, genesis, dag)
      computedParentHashes = computedParents.map(_.blockHash)
      _ <- if (parentHashes == computedParentHashes)
            Applicative[F].unit
          else {
            for {
              _ <- Log[F].warn(
                    ignore(
                      b,
                      s"block parents did not match estimate based on justification. Expected parents are ${printHashes(computedParentHashes)}, got ${printHashes(parentHashes)}."
                    )
                  )
              _ <- RaiseValidationError[F].raise[Unit](InvalidParents)
            } yield ()
          }
    } yield ()
  }

  /*
   * This check must come before Validate.parents
   */
  def justificationFollows[F[_]: Monad: Log: BlockStore: RaiseValidationError](
      b: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Unit] = {
    val justifiedValidators = b.justifications.map(_.validator).toSet
    val mainParentHash      = ProtoUtil.parentHashes(b).head
    for {
      mainParent       <- ProtoUtil.unsafeGetBlock[F](mainParentHash)
      bondedValidators = ProtoUtil.bonds(mainParent).map(_.validator).toSet
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
  def justificationRegressions[F[_]: Monad: Log: BlockStore: RaiseValidationError](
      b: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDagRepresentation[F]
  ): F[Unit] = {
    val latestMessagesOfBlock = ProtoUtil.toLatestMessageHashes(b.justifications)
    for {
      maybeLatestMessage <- dag.latestMessage(b.sender)
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

  private def justificationRegressionsAux[F[_]: Monad: Log: BlockStore: FunctorRaise[
    ?[_],
    InvalidBlock
  ]](
      b: BlockMessage,
      latestMessagesOfBlock: Map[Validator, BlockHash],
      latestMessagesFromSenderView: Map[Validator, BlockHash],
      genesis: BlockMessage
  ): F[Unit] =
    for {
      containsJustificationRegression <- latestMessagesOfBlock.toList.existsM {
                                          case (validator, currentBlockJustificationHash) =>
                                            if (validator == b.sender) {
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

  private def isJustificationRegression[F[_]: Monad: Log: BlockStore](
      currentBlockJustificationHash: BlockHash,
      previousBlockJustificationHash: BlockHash
  ): F[Boolean] =
    for {
      currentBlockJustification <- ProtoUtil
                                    .unsafeGetBlock[F](currentBlockJustificationHash)
      previousBlockJustification <- ProtoUtil
                                     .unsafeGetBlock[F](previousBlockJustificationHash)
    } yield
      if (currentBlockJustification.seqNum < previousBlockJustification.seqNum) {
        true
      } else {
        false
      }

  def transactions[F[_]: Monad: Log: BlockStore: ExecutionEngineService: FunctorRaise[
    ?[_],
    InvalidBlock
  ]](
      block: BlockMessage,
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
              case Left(_) => RaiseValidationError[F].raise[Unit](InvalidTransaction)
              case Right(postStateHash) =>
                if (postStateHash == blockPostState) {
                  Applicative[F].unit
                } else {
                  RaiseValidationError[F].raise[Unit](InvalidTransaction)
                }
            }
      } yield ()
    } else {
      //TODO: InvalidPrestate variant?
      RaiseValidationError[F].raise[Unit](InvalidTransaction)
    }
  }

  /**
    * If block contains an invalid justification block B and the creator of B is still bonded,
    * return a RejectableBlock. Otherwise return an IncludeableBlock.
    */
  def neglectedInvalidBlock[F[_]: Monad: Log: RaiseValidationError](
      block: BlockMessage,
      invalidBlockTracker: Set[BlockHash]
  ): F[Unit] = {
    val invalidJustifications = block.justifications.filter(
      justification => invalidBlockTracker.contains(justification.latestBlockHash)
    )
    val neglectedInvalidJustification = invalidJustifications.exists { justification =>
      val slashedValidatorBond = bonds(block).find(_.validator == justification.validator)
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
      b: BlockMessage,
      computedBonds: Seq[Bond]
  ): F[Unit] = {
    val bonds = ProtoUtil.bonds(b)
    ProtoUtil.tuplespace(b) match {
      case Some(tuplespaceHash) =>
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
      case None =>
        for {
          _ <- Log[F].warn(s"Block ${PrettyPrinter.buildString(b)} is missing a tuplespace hash.")
          _ <- RaiseValidationError[F].raise[Unit](InvalidBondsCache)
        } yield ()
    }
  }
}
