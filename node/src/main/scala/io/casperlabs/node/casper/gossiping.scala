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
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.protocol
import io.casperlabs.casper.LegacyConversions
import io.casperlabs.casper.util.comm.BlockApproverProtocol
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.{CachedConnections, NodeAsk}
import io.casperlabs.comm.ServiceError.{InvalidArgument, Unavailable}
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
import scala.concurrent.duration._

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
    // TODO: Start a loop to periodically print peer count, new and disconnected peers, based on NodeDiscovery.

    for {
      cachedConnections <- makeConnectionsCache(conf.server.maxMessageSize, clientSslContext)

      connectToGossip: GossipService.Connector[F] = (node: Node) => {
        cachedConnections.connection(node, enforce = true) map { chan =>
          new GossipingGrpcMonix.GossipServiceStub(chan)
        } map {
          GrpcGossipService.toGossipService(_, onError = {
            case Unavailable(_) => disconnect(cachedConnections, node)
          })
        }
      }

      relaying <- makeRelaying(connectToGossip)

      downloadManager <- makeDownloadManager(conf, connectToGossip, relaying)

      genesisApprover <- makeGenesisApprover(conf, connectToGossip, downloadManager)

      // TODO: Start fiber on `genesisApprover.awaitApproval` and trigger the StashingSynchronizer and set MultiParentCasperRef.

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
      block: Block
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
  private def makeConnectionsCache[F[_]: Concurrent: Log: Metrics](
      maxMessageSize: Int,
      clientSslContext: SslContext
  )(implicit scheduler: Scheduler): Resource[F, CachedConnections[F, Unit]] = Resource {
    for {
      makeCache <- CachedConnections[F, Unit]
      cache = makeCache {
        makeGossipChannel(
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
  private def makeGossipChannel[F[_]: Sync: Log](
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

  /** Close and remove a cached connection. */
  private def disconnect[F[_]: Sync: Log](cache: CachedConnections[F, Unit], peer: Node): F[Unit] =
    cache.modify { s =>
      for {
        _ <- s.connections.get(peer).fold(().pure[F]) { c =>
              Log[F].debug(s"Disconnecting from peer ${peer.show}") *>
                Sync[F].delay(c.shutdown()).attempt.void
            }
      } yield s.copy(connections = s.connections - peer)
    }

  private def makeRelaying[F[_]: Sync: Par: Log: NodeDiscovery: NodeAsk](
      connectToGossip: GossipService.Connector[F]
  ): Resource[F, Relaying[F]] = Resource.pure {
    RelayingImpl(
      NodeDiscovery[F],
      connectToGossip = connectToGossip,
      // TODO: Add to config.
      relayFactor = 2,
      relaySaturation = 90
    )
  }

  private def makeDownloadManager[F[_]: Concurrent: Log: Time: Timer: BlockStore: BlockDagStorage: ExecutionEngineService: MultiParentCasperRef](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F],
      relaying: Relaying[F]
  ): Resource[F, DownloadManager[F]] =
    DownloadManagerImpl[F](
      // TODO: Add to config.
      maxParallelDownloads = 10,
      connectToGossip = connectToGossip,
      backend = new DownloadManagerImpl.Backend[F] {
        override def hasBlock(blockHash: ByteString): F[Boolean] =
          isInDag(blockHash)

        override def validateBlock(block: Block): F[Unit] =
          validateAndAddBlock(conf.casper.shardId, block)

        override def storeBlock(block: Block): F[Unit] =
          // Validation has already stored it.
          ().pure[F]

        override def storeBlockSummary(
            summary: BlockSummary
        ): F[Unit] =
          // TODO: Add separate storage for summaries.
          ().pure[F]
      },
      relaying = relaying,
      // TODO: Configure retry.
      retriesConf = DownloadManagerImpl.RetriesConf.noRetries
    )

  private def makeGenesisApprover[F[_]: Concurrent: Log: Time: Timer: NodeDiscovery: BlockStore: ExecutionEngineService](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F],
      downloadManager: DownloadManager[F]
  ): Resource[F, GenesisApprover[F]] =
    for {
      knownValidators <- Resource.liftF {
                          // Based on `CasperPacketHandler.of` in default mode.
                          CasperConf.parseValidatorsFile[F](conf.casper.knownValidatorsFile)
                        }

      bonds <- Resource.liftF {
                for {
                  bonds <- Genesis.getBonds[F](
                            conf.casper.genesisPath,
                            conf.casper.bondsFile,
                            conf.casper.numValidators
                          )
                  _ <- ExecutionEngineService[F].setBonds(bonds)
                } yield bonds
              }

      candidateValidator <- Resource.liftF[F, Block => F[Either[Throwable, Option[Approval]]]] {
                             if (conf.casper.approveGenesis) {
                               // This is the case of a validator that will pull the genesis from the bootstrap, validate and approve it.
                               // Based on `CasperPacketHandler.of`.
                               for {
                                 _ <- Log[F].info("Starting in approve genesis mode")
                                 timestamp <- conf.casper.deployTimestamp
                                               .fold(Time[F].currentMillis)(_.pure[F])
                                 wallets     <- Genesis.getWallets[F](conf.casper.walletsFile)
                                 validatorId <- ValidatorIdentity.fromConfig[F](conf.casper)
                                 bondsMap = bonds.map {
                                   case (k, v) => ByteString.copyFrom(k) -> v
                                 }
                               } yield { (block: Block) =>
                                 {
                                   val candidate = protocol
                                     .ApprovedBlockCandidate()
                                     .withBlock(LegacyConversions.fromBlock(block))
                                     .withRequiredSigs(conf.casper.requiredSigs)

                                   BlockApproverProtocol.validateCandidate(
                                     candidate,
                                     conf.casper.requiredSigs,
                                     timestamp,
                                     wallets,
                                     bondsMap,
                                     conf.casper.minimumBond,
                                     conf.casper.maximumBond,
                                     conf.casper.hasFaucet
                                   ) map {
                                     case Left(msg) =>
                                       Left(InvalidArgument(msg))

                                     case Right(()) =>
                                       Right(
                                         validatorId
                                           .map { id =>
                                             val sig = id.signature(block.blockHash.toByteArray)
                                             Approval()
                                               .withValidatorPublicKey(sig.publicKey)
                                               .withSignature(
                                                 Signature()
                                                   .withSigAlgorithm(sig.algorithm)
                                                   .withSig(sig.sig)
                                               )
                                           }
                                       )
                                   }
                                 }
                               }
                             } else if (conf.casper.standalone) {
                               // This is the case of the bootstrap node. It will not pull candidates.
                               Log[F].info("Starting in create genesis mode") *>
                                 ((_: Block) => none[Approval].asRight[Throwable].pure[F]).pure[F]
                             } else {
                               // Non-validating nodes. They are okay with everything,
                               // only checking that the required signatures are present.
                               Log[F].info("Starting in default mode") *>
                                 ((_: Block) => none[Approval].asRight[Throwable].pure[F]).pure[F]
                             }
                           }

      backend = new GenesisApproverImpl.Backend[F] {
        override def validateCandidate(
            block: Block
        ): F[Either[Throwable, Option[Approval]]] =
          candidateValidator(block)

        override def canTransition(
            block: Block,
            signatories: Set[ByteString]
        ): Boolean =
          signatories.size >= conf.casper.requiredSigs &&
            knownValidators.forall(signatories(_))

        override def validateSignature(
            blockHash: ByteString,
            publicKey: ByteString,
            signature: Signature
        ): Boolean =
          Validate.signature(
            blockHash.toByteArray,
            protocol
              .Signature()
              .withPublicKey(publicKey)
              .withAlgorithm(signature.sigAlgorithm)
              .withSig(signature.sig)
          )

        override def getBlock(blockHash: ByteString): F[Option[Block]] =
          BlockStore[F]
            .get(blockHash)
            .map(_.map(x => LegacyConversions.toBlock(x.getBlockMessage)))
      }

      approver <- if (conf.casper.standalone) {
                   GenesisApproverImpl.fromGenesis(
                     backend,
                     NodeDiscovery[F],
                     connectToGossip,
                     // TODO: Move to config.
                     relayFactor = 10,
                     // TODO: Create Genesis block.
                     genesis = ???,
                     // TODO: Sign approval.
                     approval = ???
                   )
                 } else {
                   GenesisApproverImpl.fromBootstrap(
                     backend,
                     NodeDiscovery[F],
                     connectToGossip,
                     bootstrap = ???,
                     // TODO: Move to config.
                     relayFactor = 10,
                     pollInterval = 30.seconds,
                     downloadManager = downloadManager
                   )
                 }
    } yield approver

  private def show(hash: ByteString) =
    PrettyPrinter.buildString(hash)
}
