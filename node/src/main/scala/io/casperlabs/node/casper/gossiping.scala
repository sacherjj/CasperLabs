package io.casperlabsnode.casper

import cats._
import cats.effect._
import cats.implicits._
import cats.data.OptionT
import cats.temp.par.Par
import com.google.protobuf.ByteString
import io.casperlabs.shared.{Cell, Log, Resources, Time}
import io.casperlabs.blockstorage.{BlockDagStorage, BlockStore}
import io.casperlabs.casper._
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.consensus
import io.casperlabs.casper.LegacyConversions
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.{CachedConnections, NodeAsk}
import io.casperlabs.comm.ServiceError.{Unavailable}
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.grpc.SslContexts
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.grpc.ManagedChannel
import io.netty.handler.ssl.{ClientAuth, SslContext}
import io.grpc.netty.{NegotiationType, NettyChannelBuilder}
import monix.execution.Scheduler
import scala.io.Source

/** Create the Casper stack using the GossipService. */
package object gossiping {
  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(Metrics.Source(Metrics.BaseSource, "node"), "gossiping")

  def apply[F[_]: Par: ConcurrentEffect: Log: Metrics: Time: Timer: SafetyOracle: BlockStore: BlockDagStorage: NodeDiscovery: NodeAsk: MultiParentCasperRef: ExecutionEngineService](
      port: Int,
      conf: Configuration,
      grpcScheduler: Scheduler
  )(implicit scheduler: Scheduler): Resource[F, Unit] = {

    val cert = Resources.withResource(Source.fromFile(conf.tls.certificate.toFile))(_.mkString)
    val key  = Resources.withResource(Source.fromFile(conf.tls.key.toFile))(_.mkString)

    val clientSslContext = SslContexts.forClient(cert, key)

    // For client stub to GossipService conversions.
    implicit val oi = ObservableIterant.default

    // TODO: Create GenesisApprover
    // TODO: Create Synchrnozier
    // TODO: Create StashingSynchronizer
    // TODO: Create InitialSynchronization
    // TODO: Create GossipServiceServer
    // TODO: Start gRPC for GossipServiceServer

    for {
      cachedConnections <- connectionsCache(conf.server.maxMessageSize, clientSslContext)

      connectToGossip: GossipService.Connector[F] = (node: Node) => {
        cachedConnections.connection(node, enforce = true) map { chan =>
          new GossipingGrpcMonix.GossipServiceStub(chan)
        } map {
          GrpcGossipService.toGossipService(_)
        }
      }

      relaying = RelayingImpl(
        NodeDiscovery[F],
        connectToGossip = connectToGossip,
        // TODO: Add to config.
        relayFactor = 2,
        relaySaturation = 90
      )

      downloadManager <- DownloadManagerImpl[F](
                          // TODO: Add to configu.
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

  /** Check if we have a block yet. */
  private def isInDag[F[_]: Sync: BlockDagStorage](blockHash: ByteString): F[Boolean] =
    for {
      dag  <- BlockDagStorage[F].getRepresentation
      cont <- dag.contains(blockHash)
    } yield cont

  /** Validate the genesis candidate or any new block via Casper. */
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

  /** Cached connection resources, closed at the end. */
  private def connectionsCache[F[_]: Concurrent: Log: Metrics](
      maxMessageSize: Int,
      clientSslContext: SslContext
  )(implicit scheduler: Scheduler): Resource[F, CachedConnections[F, Unit]] = Resource {
    for {
      fromOpener <- CachedConnections[F, Unit]
      cache = fromOpener {
        gossipChannel(
          _,
          maxMessageSize,
          clientSslContext
        )
      }
      shutdown = cache.read.flatMap { s =>
        s.connections.toList.traverse {
          case (peer, chan) =>
            Log[F].debug(s"Closing connection to ${peer.show}") *>
              Sync[F].delay(chan.shutdown()).attempt.void
        }.void
      }
    } yield (cache, shutdown)
  }

  /** Open a gRPC channel for gossiping. */
  private def gossipChannel[F[_]: Sync: Log](
      peer: Node,
      maxMessageSize: Int,
      clientSslContext: SslContext
  )(implicit scheduler: Scheduler): F[ManagedChannel] =
    for {
      _ <- Log[F].debug(s"Creating new channel to peer ${peer.show}")
      chan <- Sync[F].delay {
               NettyChannelBuilder
                 .forAddress(peer.host, peer.protocolPort)
                 .executor(scheduler)
                 .maxInboundMessageSize(maxMessageSize)
                 .negotiationType(NegotiationType.TLS)
                 .sslContext(clientSslContext)
                 .overrideAuthority(Base16.encode(peer.id.toByteArray))
                 .build()
             }
    } yield chan

  private def show(hash: ByteString) =
    PrettyPrinter.buildString(hash)
}
