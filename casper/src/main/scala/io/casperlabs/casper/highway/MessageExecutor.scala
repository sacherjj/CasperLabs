package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import cats.effect.concurrent.Semaphore
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.validation.Validation
import io.casperlabs.casper.validation.Validation.BlockEffects
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.casper._

/** A stateless class to encapsulate the steps to validate, execute and store a block. */
class MessageExecutor[F[_]: MonadThrowable: Log: Time: Metrics: BlockStorage: DagStorage: DeployStorage: EventEmitter] {

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
            // TODO (CON-621): Validate and execute the block, store it in the block store and DAG store.
            // Pass the `isBookingBlock` flag as well. For now just store the incoming blocks so they
            // are available in the DAG when we produce messages on top of them.
            _ <- addEffects(Valid, block, BlockEffects.empty)
            _ = isBookingBlock
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

}
