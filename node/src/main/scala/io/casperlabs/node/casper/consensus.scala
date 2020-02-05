package io.casperlabs.node.casper

import com.google.protobuf.ByteString
import cats._
import cats.implicits._
import cats.effect._
import cats.effect.implicits._
import cats.effect.concurrent._
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.{
  BlockStatus,
  CasperState,
  MultiParentCasper,
  MultiParentCasperImpl,
  MultiParentCasperRef,
  PrettyPrinter,
  ValidatorIdentity
}
import io.casperlabs.casper.{EquivocatedBlock, Processed, SelfEquivocatedBlock, Valid}
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.validation.Validation
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.ServiceError.{NotFound, Unavailable}
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.ipc
import io.casperlabs.ipc.ChainSpec
import io.casperlabs.metrics.Metrics
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.node.api.EventStream
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.shared.{Cell, FatalError, Log, Time}
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.dag.FinalityStorage
import io.casperlabs.smartcontracts.ExecutionEngineService
import scala.util.control.NoStackTrace
import simulacrum.typeclass
import io.casperlabs.casper.DeploySelection

// Stuff we need to pass to gossiping.
@typeclass
trait Consensus[F[_]] {

  /** Validate and persist a block. Raise an error if there's something wrong. Don't gossip. */
  def validateAndAddBlock(
      block: Block
  ): F[Unit]

  def onGenesisApproved(genesisBlockHash: ByteString): F[Unit]

  def onScheduled(summary: BlockSummary): F[Unit]
}

class NCB[F[_]: Concurrent: Time: Log: BlockStorage: DagStorage: ExecutionEngineService: MultiParentCasperRef: Metrics: DeployStorage: DeployBuffer: DeploySelection: FinalityStorage: Validation: CasperLabsProtocol: EventStream](
    conf: Configuration,
    chainSpec: ChainSpec,
    maybeValidatorId: Option[ValidatorIdentity]
) extends Consensus[F] {

  override def validateAndAddBlock(
      block: Block
  ): F[Unit] =
    MultiParentCasperRef[F].get
      .flatMap {
        case Some(casper) =>
          casper.addBlock(block)

        case None if block.getHeader.parentHashes.isEmpty =>
          for {
            _     <- Log[F].info(s"Validating genesis-like ${show(block.blockHash) -> "block"}")
            state <- Cell.mvarCell[F, CasperState](CasperState())
            executor <- MultiParentCasperImpl.StatelessExecutor
                         .create[F](
                           maybeValidatorId.map(_.publicKey),
                           chainName = chainSpec.getGenesis.name,
                           chainSpec.upgrades
                         )
            status <- executor.validateAndAddBlock(None, block)(state)
          } yield status

        case None =>
          MonadThrowable[F].raiseError[BlockStatus](Unavailable("Casper is not yet available."))
      }
      .flatMap {
        case Valid =>
          Log[F].debug(s"Validated and stored ${show(block.blockHash) -> "block"}")

        case EquivocatedBlock =>
          Log[F].debug(
            s"Detected ${show(block.blockHash) -> "block"} equivocated"
          )

        case Processed =>
          Log[F].warn(
            s"${show(block.blockHash) -> "block"} seems to have been processed before."
          )

        case SelfEquivocatedBlock =>
          FatalError.selfEquivocationError(block.blockHash)

        case other =>
          Log[F].debug(s"Received invalid ${show(block.blockHash) -> "block"}: $other") *>
            MonadThrowable[F].raiseError[Unit](
              // Raise an exception to stop the DownloadManager from progressing with this block.
              new RuntimeException(s"Non-valid status: $other") with NoStackTrace
            )
      }

  override def onGenesisApproved(genesisBlockHash: ByteString): F[Unit] =
    for {
      maybeGenesis <- BlockStorage[F].get(genesisBlockHash)
      genesisStore <- MonadThrowable[F].fromOption(
                       maybeGenesis,
                       NotFound(
                         s"Cannot retrieve ${show(genesisBlockHash) -> "genesis"}"
                       )
                     )
      genesis    = genesisStore.getBlockMessage
      prestate   = ProtoUtil.preStateHash(genesis)
      transforms = genesisStore.blockEffects.flatMap(_.effects)
      casper <- MultiParentCasper.fromGossipServices(
                 maybeValidatorId,
                 genesis,
                 prestate,
                 transforms,
                 genesis.getHeader.chainName,
                 conf.casper.minTtl,
                 chainSpec.upgrades
               )
      _ <- MultiParentCasperRef[F].set(casper)
      _ <- Log[F].info(s"Making the transition to block processing.")
    } yield ()

  override def onScheduled(summary: BlockSummary): F[Unit] =
    // The EquivocationDetector treats equivocations with children differently,
    // so let Casper know about the DAG dependencies up front.
    MultiParentCasperRef[F].get.flatMap {
      case Some(casper: MultiParentCasperImpl[F]) =>
        val partialBlock = Block()
          .withBlockHash(summary.blockHash)
          .withHeader(summary.getHeader)

        Log[F].debug(
          s"Feeding a pending block to Casper: ${show(summary.blockHash) -> "block"}"
        ) *>
          casper.addMissingDependencies(partialBlock)

      case _ => ().pure[F]
    }

  private def show(hash: ByteString) =
    PrettyPrinter.buildString(hash)
}
