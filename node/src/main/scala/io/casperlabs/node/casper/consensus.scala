package io.casperlabs.node.casper.consensus

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
  EventEmitter,
  MultiParentCasper,
  MultiParentCasperImpl,
  MultiParentCasperRef,
  PrettyPrinter,
  ValidatorIdentity
}
import io.casperlabs.casper.{EquivocatedBlock, Processed, SelfEquivocatedBlock, Valid}
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.finality.MultiParentFinalizer
import io.casperlabs.casper.finality.votingmatrix.FinalityDetectorVotingMatrix
import io.casperlabs.casper.validation.Validation
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.casper.DeploySelection
import io.casperlabs.casper.highway.{
  EraSupervisor,
  ForkChoiceManager,
  HighwayConf,
  MessageExecutor,
  MessageProducer
}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.ServiceError.{NotFound, Unavailable}
import io.casperlabs.comm.gossiping.Relaying
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.ipc
import io.casperlabs.ipc.ChainSpec
import io.casperlabs.models.Message
import io.casperlabs.metrics.Metrics
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.node.api.EventStream
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.shared.{Cell, FatalError, Log, Time}
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.dag.FinalityStorage
import io.casperlabs.storage.era.EraStorage
import io.casperlabs.smartcontracts.ExecutionEngineService
import java.util.concurrent.TimeUnit
import java.time.Instant
import scala.util.control.NoStackTrace
import scala.concurrent.duration._
import simulacrum.typeclass

// Stuff we need to pass to gossiping.
@typeclass
trait Consensus[F[_]] {

  /** Validate and persist a block. Raise an error if there's something wrong. Don't gossip. */
  def validateAndAddBlock(
      block: Block
  ): F[Unit]

  /** Let consensus know that the Genesis block has been approved and stored,
    * so it can transition to block processing now.
    */
  def onGenesisApproved(genesisBlockHash: ByteString): F[Unit]

  /** Let consensus know that a new block has been scheduled for downloading.
    */
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

object Highway {
  def apply[F[_]: Concurrent: Time: Timer: Clock: Log: Metrics: DagStorage: BlockStorage: DeployBuffer: DeployStorage: EraStorage: FinalityStorage: CasperLabsProtocol: ExecutionEngineService: DeploySelection: EventEmitter: Validation: Relaying](
      conf: Configuration,
      chainSpec: ChainSpec,
      maybeValidatorId: Option[ValidatorIdentity],
      genesis: Block,
      isSyncedRef: Ref[F, Boolean]
  ): Resource[F, Consensus[F]] = {
    val hc                      = chainSpec.getGenesis.getHighwayConfig
    val faultToleranceThreshold = 0.1

    for {
      implicit0(finalizer: MultiParentFinalizer[F]) <- {
        Resource.liftF[F, MultiParentFinalizer[F]] {
          for {
            lfb <- FinalityStorage[F].getLastFinalizedBlock
            dag <- DagStorage[F].getRepresentation
            finalityDetector <- FinalityDetectorVotingMatrix
                                 .of[F](
                                   dag,
                                   lfb,
                                   faultToleranceThreshold
                                 )
            finalizer <- MultiParentFinalizer.empty[F](
                          dag,
                          lfb,
                          finalityDetector
                        )
          } yield finalizer
        }
      }

      // TODO (CON-622): Implement the ForkChoiceManager.
      implicit0(forkchoice: ForkChoiceManager[F]) <- Resource.pure[F, ForkChoiceManager[F]] {
                                                      new ForkChoiceManager[F] {
                                                        override def fromKeyBlock(
                                                            keyBlockHash: BlockHash
                                                        ) = ???
                                                        override def fromJustifications(
                                                            keyBlockHash: BlockHash,
                                                            justifications: Set[BlockHash]
                                                        ) = ???
                                                        override def updateLatestMessage(
                                                            keyBlockHash: BlockHash,
                                                            message: Message
                                                        ) = ???
                                                      }
                                                    }

      // Allow specifying the start outside the chain spec, for convenience.
      genesisEraStart = Option(conf.highway.genesisEraStartOverride)
        .filter(_ > 0)
        .getOrElse(hc.genesisEraStartTimestamp)

      supervisor <- EraSupervisor(
                     conf = HighwayConf(
                       tickUnit = TimeUnit.MILLISECONDS,
                       genesisEraStart = Instant.ofEpochMilli(genesisEraStart),
                       eraDuration =
                         HighwayConf.EraDuration.FixedLength(hc.eraDurationMillis.millis),
                       bookingDuration = hc.bookingDurationMillis.millis,
                       entropyDuration = hc.entropyDurationMillis.millis,
                       postEraVotingDuration = if (hc.votingPeriodSummitLevel > 0) {
                         HighwayConf.VotingDuration.SummitLevel(hc.votingPeriodSummitLevel)
                       } else {
                         HighwayConf.VotingDuration
                           .FixedLength(hc.votingPeriodDurationMillis.millis)
                       },
                       omegaMessageTimeStart = conf.highway.omegaMessageTimeStart.value,
                       omegaMessageTimeEnd = conf.highway.omegaMessageTimeEnd.value
                     ),
                     genesis = BlockSummary(genesis.blockHash, genesis.header, genesis.signature),
                     maybeMessageProducer = maybeValidatorId.map { validatorId =>
                       MessageProducer[F](
                         validatorId,
                         chainName = chainSpec.getGenesis.name,
                         upgrades = chainSpec.upgrades
                       )
                     },
                     messageExecutor = new MessageExecutor(
                       chainName = chainSpec.getGenesis.name,
                       genesis = genesis,
                       upgrades = chainSpec.upgrades,
                       maybeValidatorId =
                         maybeValidatorId.map(v => PublicKey(ByteString.copyFrom(v.publicKey)))
                     ),
                     initRoundExponent = conf.highway.initRoundExponent.value,
                     isSynced = isSyncedRef.get
                   )

      cons = new Consensus[F] {
        override def validateAndAddBlock(block: Block): F[Unit] =
          supervisor.validateAndAddBlock(block)

        override def onGenesisApproved(genesisBlockHash: ByteString): F[Unit] =
          // This is for the integration tests, they are looking for this.
          Log[F].info(s"Making the transition to block processing.")

        override def onScheduled(summary: BlockSummary): F[Unit] =
          ().pure[F]
      }
    } yield cons
  }
}
