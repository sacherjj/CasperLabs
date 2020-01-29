package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.Sync
import cats.mtl.FunctorRaise
import cats.effect.concurrent.Semaphore
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.equivocations.EquivocationDetector
import io.casperlabs.casper.validation.Validation
import io.casperlabs.casper.validation.Validation.BlockEffects
import io.casperlabs.casper.validation.Errors.{DropErrorWrapper, ValidateErrorWrapper}
import io.casperlabs.casper.util.CasperLabsProtocol
import io.casperlabs.casper._
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys.PublicKeyBS
import io.casperlabs.ipc
import io.casperlabs.models.Message
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._ // for .timer syntax
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.smartcontracts.ExecutionEngineService
import scala.util.control.NonFatal

/** A stateless class to encapsulate the steps to validate, execute and store a block. */
class MessageExecutor[F[_]: Sync: Log: Time: Metrics: BlockStorage: DagStorage: DeployStorage: EventEmitter: Validation: CasperLabsProtocol: ExecutionEngineService: Fs2Compiler](
    chainName: String,
    genesis: Block,
    upgrades: Seq[ipc.ChainSpec.UpgradePoint],
    maybeValidatorId: Option[PublicKeyBS]
) {

  implicit val functorRaiseInvalidBlock = validation.raiseValidateErrorThroughApplicativeError[F]

  /** Validate, execute and persist an incoming block.
    * The blocks made by the MessageProducer don't have to be passed here.
    */
  def validateAndAdd(semaphore: Semaphore[F], block: Block, isBookingBlock: Boolean): F[Unit] =
    // If the block timestamp is in the future, wait some time before adding it,
    // so we won't include it as a justification from the future.
    Validation.preTimestamp[F](block).attempt.flatMap {
      case Right(Some(delay)) =>
        Log[F].info(
          s"${block.blockHash.show -> "block"} is ahead for $delay from now, will retry adding later"
        ) >>
          Time[F].sleep(delay) >>
          validateAndAdd(semaphore, block, isBookingBlock)

      case Right(None) =>
        semaphore.withPermit {
          for {
            (status, effects) <- computeEffects(block, isBookingBlock)
            _                 <- addEffects(status, block, effects)
            _ <- status match {
                  case invalid: InvalidBlock =>
                    Log[F]
                      .warn(s"Could not validate ${block.blockHash.show -> "block"}: $invalid") *>
                      functorRaiseInvalidBlock.raise(invalid)
                  case _ =>
                    ().pure[F]
                }
          } yield ()
        }

      case _ =>
        semaphore.withPermit {
          Log[F]
            .warn(
              s"${block.blockHash.show -> "block"} timestamp exceeded threshold"
            ) >>
            addEffects(InvalidUnslashableBlock, block, BlockEffects.empty)
        }
    }

  /** Carry out the effects according to the status: store valid blocks, ignore invalid ones. */
  private def addEffects(
      status: BlockStatus,
      block: Block,
      blockEffects: BlockEffects
  ): F[Unit] =
    status match {
      case Valid =>
        addToState(block, blockEffects) *>
          Log[F].info(s"Added ${block.blockHash.show -> "block"}")

      case EquivocatedBlock | SelfEquivocatedBlock =>
        addToState(block, blockEffects) *>
          Log[F].info(s"Added equivocated ${block.blockHash.show -> "block"}")

      case InvalidUnslashableBlock | InvalidBlockNumber | InvalidParents | InvalidSequenceNumber |
          InvalidPrevBlockHash | NeglectedInvalidBlock | InvalidTransaction | InvalidBondsCache |
          InvalidRepeatDeploy | InvalidChainName | InvalidBlockHash | InvalidDeployCount |
          InvalidDeployHash | InvalidDeploySignature | InvalidPreStateHash | InvalidPostStateHash |
          InvalidTargetHash | InvalidDeployHeader | InvalidDeployChainName |
          DeployDependencyNotMet | DeployExpired | DeployFromFuture | SwimlaneMerged =>
        Log[F].warn(s"Ignoring invalid ${block.blockHash.show -> "block"} with $status")

      case MissingBlocks =>
        throw new RuntimeException(
          "The DownloadManager should not give us a block with missing dependencies."
        )

      case Processing | Processed =>
        throw new RuntimeException(s"A block should not be processing at this stage.")

      case UnexpectedBlockException(ex) =>
        Log[F].error(
          s"Encountered exception in while processing ${block.blockHash.show -> "block"}: $ex"
        )
    }

  /** Save the block to the block and DAG storage. */
  private def addToState(block: Block, blockEffects: BlockEffects): F[Unit] =
    for {
      _ <- BlockStorage[F].put(block, blockEffects.effects)
      info <- BlockAPI.getBlockInfo[F](
               Base16.encode(block.blockHash.toByteArray),
               BlockInfo.View.FULL
             )
      _ <- EventEmitter[F].blockAdded(info)
    } yield ()

  // NOTE: Don't call this on genesis, genesis is presumed to be already computed and saved.
  private def computeEffects(
      block: Block,
      isBookingBlock: Boolean
  ): F[(BlockStatus, BlockEffects)] = {
    import io.casperlabs.casper.validation.ValidationImpl.metricsSource
    Metrics[F].timer("validateAndAddBlock") {
      val hashPrefix = block.blockHash
      val effects: F[BlockEffects] = for {
        _   <- Log[F].info(s"Attempting to add $isBookingBlock ${hashPrefix -> "block"} to the DAG.")
        dag <- DagStorage[F].getRepresentation
        _   <- Validation[F].blockFull(block, dag, chainName, genesis.some)
        // Confirm the parents are correct (including checking they commute) and capture
        // the effect needed to compute the correct pre-state as well.
        _      <- Log[F].debug(s"Validating the parents of ${hashPrefix -> "block"}")
        merged <- Validation[F].parents(block, dag)
        _      <- Log[F].debug(s"Computing the pre-state hash of ${hashPrefix -> "block"}")
        preStateHash <- ExecEngineUtil
                         .computePrestate[F](merged, block.getHeader.rank, upgrades)
                         .timer("computePrestate")
        _ <- Log[F].debug(s"Computing the effects for ${hashPrefix -> "block"}")
        blockEffects <- ExecEngineUtil
                         .effectsForBlock[F](block, preStateHash)
                         .recoverWith {
                           case NonFatal(ex) =>
                             Log[F].error(
                               s"Could not calculate effects for ${hashPrefix -> "block"}: $ex"
                             ) *>
                               FunctorRaise[F, InvalidBlock].raise(InvalidTransaction)
                         }
                         .timer("effectsForBlock")
        gasSpent = block.getBody.deploys.foldLeft(0L) { case (acc, next) => acc + next.cost }
        _ <- Metrics[F]
              .incrementCounter("gas_spent", gasSpent)
        _ <- Log[F].debug(s"Validating the transactions in ${hashPrefix -> "block"}")
        _ <- Validation[F].transactions(
              block,
              preStateHash,
              blockEffects
            )
        // TODO: The invalid block tracker used to be a transient thing, it didn't survive a restart.
        // It's not clear why we need to do this, the DM will not download a block if it depends on
        // an invalid one that could not be validated. Is it equivocations? Wouldn't the hash change,
        // because of hashing affecting the post state hash?
        // _ <- Log[F].debug(s"Validating neglection for ${hashPrefix -> "block"}")
        // _ <- Validation[F]
        //       .neglectedInvalidBlock(
        //         block,
        //         invalidBlockTracker = Set.empty
        //       )
        _       <- Log[F].debug(s"Checking equivocation for ${hashPrefix -> "block"}")
        message <- MonadThrowable[F].fromTry(Message.fromBlock(block))
        _       <- Validation[F].checkEquivocation(dag, message).timer("checkEquivocationsWithUpdate")
        _       <- Log[F].debug(s"Block effects calculated for ${hashPrefix -> "block"}")
      } yield blockEffects

      def validBlock(effects: BlockEffects)  = ((Valid: BlockStatus)  -> effects).pure[F]
      def invalidBlock(status: InvalidBlock) = ((status: BlockStatus) -> BlockEffects.empty).pure[F]

      effects.attempt.flatMap {
        case Right(effects) =>
          validBlock(effects)

        case Left(DropErrorWrapper(invalid)) =>
          // These exceptions are coming from the validation checks that used to happen outside attemptAdd,
          // the ones that returned boolean values.
          invalidBlock(invalid)

        case Left(ValidateErrorWrapper(EquivocatedBlock))
            if maybeValidatorId.contains(block.getHeader.validatorPublicKey) =>
          invalidBlock(SelfEquivocatedBlock)

        case Left(ValidateErrorWrapper(invalid)) =>
          invalidBlock(invalid)

        case Left(ex) =>
          Log[F].error(s"Unexpected exception during validation of ${hashPrefix -> "block"}: $ex") *>
            ex.raiseError[F, (BlockStatus, BlockEffects)]
      }
    }
  }
}
