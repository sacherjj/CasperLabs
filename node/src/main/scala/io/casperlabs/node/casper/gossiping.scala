package io.casperlabsnode.casper

import cats._
import cats.effect._
import cats.implicits._
import cats.data.OptionT
import cats.temp.par.Par
import com.google.protobuf.ByteString
import io.casperlabs.shared.{Cell, Log, Time}
import io.casperlabs.blockstorage.{BlockDagStorage, BlockStore}
import io.casperlabs.casper._
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.consensus
import io.casperlabs.casper.LegacyConversions
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.NodeAsk
import io.casperlabs.comm.ServiceError.{Unavailable}
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.comm.gossiping._
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.execution.Scheduler

/** Create the Casper stack using the GossipService. */
package object gossiping {
  def apply[F[_]: Par: Concurrent: Log: Metrics: Time: Timer: SafetyOracle: BlockStore: BlockDagStorage: NodeDiscovery: NodeAsk: MultiParentCasperRef: ExecutionEngineService](
      port: Int,
      conf: Configuration,
      grpcScheduler: Scheduler
  )(implicit scheduler: Scheduler): Resource[F, Unit] = {

    val connectToGossip: GossipService.Connector[F] = ???

    // TODO: Method to create MultiParentCasperImpl.StatelessExecutor with temporary CasperState
    // to run and store the genesis block candidate.

    // TODO: Create caching for connections
    // TODO: Create GenesisApprover
    // TODO: Create Synchrnozier
    // TODO: Create StashingSynchronizer
    // TODO: Create InitialSynchronization
    // TODO: Create GossipServiceServer
    // TODO: Start gRPC for GossipServiceServer

    // TODO: Configure relaying.
    val relaying = RelayingImpl(
      NodeDiscovery[F],
      connectToGossip = connectToGossip,
      relayFactor = 2,
      relaySaturation = 90
    )

    for {
      downloadManager <- DownloadManagerImpl[F](
                          // TODO: Configure parallelism.
                          maxParallelDownloads = 10,
                          connectToGossip = connectToGossip,
                          backend = new DownloadManagerImpl.Backend[F] {
                            override def hasBlock(blockHash: ByteString): F[Boolean] =
                              isInDag(blockHash)

                            override def validateBlock(block: consensus.Block): F[Unit] =
                              validateAndAddBlock(conf.casper.shardId, block)

                            override def storeBlock(block: consensus.Block): F[Unit] =
                              // Validation has already stored it.
                              ().pure[F]

                            override def storeBlockSummary(
                                summary: consensus.BlockSummary
                            ): F[Unit] =
                              // TODO: Add separate storage for summaries.
                              ().pure[F]
                          },
                          relaying = relaying,
                          // TODO: Configure retry.
                          retriesConf = DownloadManagerImpl.RetriesConf.noRetries
                        )
    } yield ()
  }

  private def isInDag[F[_]: Sync: BlockDagStorage](blockHash: ByteString): F[Boolean] =
    for {
      dag  <- BlockDagStorage[F].getRepresentation
      cont <- dag.contains(blockHash)
    } yield cont

  private def validateAndAddBlock[F[_]: Concurrent: Time: Log: BlockStore: BlockDagStorage: ExecutionEngineService: MultiParentCasperRef](
      shardId: String,
      block: consensus.Block
  ): F[Unit] =
    MultiParentCasperRef[F].get
      .flatMap {
        case Some(casper) =>
          casper.addBlock(LegacyConversions.fromBlock(block))

        case None if block.getHeader.parentHashes.isEmpty =>
          for {
            _        <- Log[F].info(s"Validating genesis-like block ${show(block.blockHash)}...")
            state    <- Cell.mvarCell[F, CasperState](CasperState())
            executor = new MultiParentCasperImpl.StatelessExecutor(shardId)
            dag      <- BlockDagStorage[F].getRepresentation
            result <- executor.validateAndAddBlock(None, dag, LegacyConversions.fromBlock(block))(
                       state
                     )
            (status, _) = result
          } yield status

        case None =>
          MonadThrowable[F].raiseError[BlockStatus](Unavailable("Casper is not yet available."))
      }
      .flatMap {
        case Valid =>
          Log[F].debug(s"Validated and stored block ${show(block.blockHash)}")

        case AdmissibleEquivocation =>
          Log[F].debug(
            s"Detected AdmissibleEquivocation on block ${show(block.blockHash)}"
          )

        case other =>
          Log[F].debug(s"Received invalid block ${show(block.blockHash)}: $other") *>
            MonadThrowable[F].raiseError[Unit](
              new RuntimeException(s"Non-valid status: $other")
            )
      }

  private def show(hash: ByteString) =
    PrettyPrinter.buildString(hash)
}
