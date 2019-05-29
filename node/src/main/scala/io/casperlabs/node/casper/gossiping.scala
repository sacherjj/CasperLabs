package io.casperlabs.node.casper

import cats._
import cats.effect._
import cats.implicits._
import cats.data.OptionT
import cats.temp.par.Par
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.shared.{Cell, Log, Resources, Time}
import io.casperlabs.blockstorage.{BlockDagStorage, BlockStore}
import io.casperlabs.casper._
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.LegacyConversions
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.comm.BlockApproverProtocol
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.{CachedConnections, NodeAsk}
import io.casperlabs.comm.ServiceError.{InvalidArgument, NotFound, Unavailable}
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.grpc.{
  AuthInterceptor,
  ErrorInterceptor,
  GrpcServer,
  MetricsInterceptor,
  SslContexts
}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.node.diagnostics
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.grpc.ManagedChannel
import io.netty.handler.ssl.{ClientAuth, SslContext}
import io.grpc.netty.{NegotiationType, NettyChannelBuilder}
import monix.execution.Scheduler
import monix.eval.TaskLike
import scala.io.Source
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

/** Create the Casper stack using the GossipService. */
package object gossiping {
  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(Metrics.Source(Metrics.BaseSource, "node"), "gossiping")

  def apply[F[_]: Par: ConcurrentEffect: Log: Metrics: Time: Timer: SafetyOracle: BlockStore: BlockDagStorage: NodeDiscovery: NodeAsk: MultiParentCasperRef: ExecutionEngineService](
      port: Int,
      conf: Configuration,
      grpcScheduler: Scheduler
  )(implicit scheduler: Scheduler, logId: Log[Id], metricsId: Metrics[Id]): Resource[F, Unit] = {

    val cert = Resources.withResource(Source.fromFile(conf.tls.certificate.toFile))(_.mkString)
    val key  = Resources.withResource(Source.fromFile(conf.tls.key.toFile))(_.mkString)

    val clientSslContext = SslContexts.forClient(cert, key)
    val serverSslContext = SslContexts.forServer(cert, key, ClientAuth.REQUIRE)

    // For client stub to GossipService conversions.
    implicit val oi = ObservableIterant.default

    for {
      cachedConnections <- makeConnectionsCache(
                            conf.server.maxMessageSize,
                            clientSslContext,
                            grpcScheduler
                          )

      connectToGossip: GossipService.Connector[F] = (node: Node) => {
        cachedConnections.connection(node, enforce = true) map { chan =>
          new GossipingGrpcMonix.GossipServiceStub(chan)
        } map {
          GrpcGossipService.toGossipService(_, onError = {
            case Unavailable(_) => disconnect(cachedConnections, node)
          })
        }
      }

      relaying <- makeRelaying(conf, connectToGossip)

      downloadManager <- makeDownloadManager(conf, connectToGossip, relaying)

      genesisApprover <- makeGenesisApprover(conf, connectToGossip, downloadManager)

      // Make sure MultiParentCasperRef is set before the synchronizer is resumed.
      awaitApproval = genesisApprover.awaitApproval >>= { genesisBlockHash =>
        for {
          maybeGenesis <- BlockStore[F].get(genesisBlockHash)
          genesisStore <- MonadThrowable[F].fromOption(
                           maybeGenesis,
                           NotFound(s"Cannot retrieve Genesis ${show(genesisBlockHash)}")
                         )
          validatorId <- ValidatorIdentity.fromConfig[F](conf.casper)
          genesis     = genesisStore.getBlockMessage
          prestate    = ProtoUtil.preStateHash(genesis)
          transforms  = genesisStore.transformEntry
          casper <- MultiParentCasper.fromGossipServices(
                     validatorId,
                     genesis,
                     prestate,
                     transforms,
                     conf.casper.shardId,
                     relaying
                   )
          _ <- MultiParentCasperRef[F].set(casper)
          _ <- Log[F].info("Making the transition to block processing.")
        } yield ()
      }

      // The StashingSynchronizer will start a fiber to await on the above.
      synchronizer <- makeSynchronizer(connectToGossip, awaitApproval)

      gossipServiceServer <- makeGossipServiceServer(
                              port,
                              conf,
                              synchronizer,
                              downloadManager,
                              genesisApprover,
                              serverSslContext,
                              grpcScheduler
                            )

      // Start syncing with the bootstrap in the background.
      _ <- makeInitialSynchronization(conf, gossipServiceServer, connectToGossip)

      // Start a loop to periodically print peer count, new and disconnected peers, based on NodeDiscovery.
      _ <- makePeerCountPrinter

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
          casper.addBlock(block)

        case None if block.getHeader.parentHashes.isEmpty =>
          for {
            _           <- Log[F].info(s"Validating genesis-like block ${show(block.blockHash)}...")
            state       <- Cell.mvarCell[F, CasperState](CasperState())
            executor    = new MultiParentCasperImpl.StatelessExecutor(shardId)
            dag         <- BlockDagStorage[F].getRepresentation
            result      <- executor.validateAndAddBlock(None, dag, block)(state)
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
              // Raise an exception to stop the DownloadManager from progressing with this block.
              new RuntimeException(s"Non-valid status: $other") with NoStackTrace
            )
      }

  /** Only meant to be called once the genesis has been approved and the Casper instance created. */
  private def unsafeGetCasper[F[_]: MonadThrowable: MultiParentCasperRef]: F[MultiParentCasper[F]] =
    MultiParentCasperRef[F].get.flatMap {
      MonadThrowable[F].fromOption(_, Unavailable("Casper is not yet available."))
    }

  /** Cached connection resources, closed at the end. */
  private def makeConnectionsCache[F[_]: Concurrent: Log: Metrics](
      maxMessageSize: Int,
      clientSslContext: SslContext,
      grpcScheduler: Scheduler
  ): Resource[F, CachedConnections[F, Unit]] = Resource {
    for {
      makeCache <- CachedConnections[F, Unit]
      cache = makeCache {
        makeGossipChannel(
          _,
          maxMessageSize,
          clientSslContext,
          grpcScheduler
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
      clientSslContext: SslContext,
      grpcScheduler: Scheduler
  ): F[ManagedChannel] =
    for {
      _ <- Log[F].debug(s"Creating new channel to peer ${peer.show}")
      chan <- Sync[F].delay {
               NettyChannelBuilder
                 .forAddress(peer.host, peer.protocolPort)
                 .executor(grpcScheduler)
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

  private def makeRelaying[F[_]: Sync: Par: Log: Metrics: NodeDiscovery: NodeAsk](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F]
  ): Resource[F, Relaying[F]] =
    Resource.liftF(RelayingImpl.establishMetrics[F]) *>
      Resource.pure {
        RelayingImpl(
          NodeDiscovery[F],
          connectToGossip = connectToGossip,
          relayFactor = conf.server.relayFactor,
          relaySaturation = conf.server.relaySaturation
        )
      }

  private def makeDownloadManager[F[_]: Concurrent: Log: Time: Timer: Metrics: BlockStore: BlockDagStorage: ExecutionEngineService: MultiParentCasperRef](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F],
      relaying: Relaying[F]
  ): Resource[F, DownloadManager[F]] =
    for {
      _           <- Resource.liftF(DownloadManagerImpl.establishMetrics[F])
      validatorId <- Resource.liftF(ValidatorIdentity.fromConfig[F](conf.casper))
      maybeValidatorPublicKey = validatorId
        .map(x => ByteString.copyFrom(x.publicKey))
        .filterNot(_.isEmpty)
      downloadManager <- DownloadManagerImpl[F](
                          // TODO: Add to config.
                          maxParallelDownloads = 10,
                          connectToGossip = connectToGossip,
                          backend = new DownloadManagerImpl.Backend[F] {
                            override def hasBlock(blockHash: ByteString): F[Boolean] =
                              isInDag(blockHash)

                            override def validateBlock(block: Block): F[Unit] =
                              maybeValidatorPublicKey
                                .filter(_ == block.getHeader.validatorPublicKey)
                                .fold(().pure[F]) { _ =>
                                  Log[F]
                                    .warn(
                                      s"Block ${PrettyPrinter.buildString(block)} seems to be created by a doppelganger using the same validator key!"
                                    )
                                } *>
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
    } yield downloadManager

  private def makeGenesisApprover[F[_]: Concurrent: Log: Time: Timer: NodeDiscovery: BlockStore: BlockDagStorage: MultiParentCasperRef: ExecutionEngineService](
      conf: Configuration,
      connectToGossip: GossipService.Connector[F],
      downloadManager: DownloadManager[F]
  ): Resource[F, GenesisApprover[F]] =
    for {
      knownValidators <- Resource.liftF {
                          // Based on `CasperPacketHandler.of` in default mode.
                          CasperConf.parseValidatorsFile[F](conf.casper.knownValidatorsFile)
                        }

      validatorId <- Resource.liftF {
                      for {
                        id <- ValidatorIdentity.fromConfig[F](conf.casper)
                        _ <- id match {
                              case Some(ValidatorIdentity(publicKey, _, _)) =>
                                Log[F].info(
                                  s"Starting with validator identity ${Base16.encode(publicKey)}"
                                )
                              case None =>
                                Log[F].info("Starting without a validator identity.")
                            }
                      } yield id
                    }

      maybeApproveBlock = (block: Block) =>
        validatorId.map { id =>
          val sig = id.signature(block.blockHash.toByteArray)
          Approval()
            .withApproverPublicKey(sig.publicKey)
            .withSignature(
              Signature()
                .withSigAlgorithm(sig.algorithm)
                .withSig(sig.sig)
            )
        }

      // Function to read and set the bonds.txt in modes which generate the Genesis locally.
      readBondsFile = {
        for {
          _ <- Log[F].info("Taking bonds from file.")
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
                                 wallets <- Genesis.getWallets[F](conf.casper.walletsFile)
                                 bonds   <- readBondsFile
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
                                       Right(maybeApproveBlock(block))
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
                               // In order to not have to circulate the bonds.txt they set it here.
                               Log[F].info("Starting in default mode") *> { (genesis: Block) =>
                                 for {
                                   _ <- Log[F].info("Taking bonds from the Genesis candidate.")
                                   bonds = genesis.getHeader.getState.bonds.map { bond =>
                                     PublicKey(bond.validatorPublicKey.toByteArray) -> bond.stake
                                   }.toMap
                                   _ <- ExecutionEngineService[F].setBonds(bonds)
                                 } yield none[Approval].asRight[Throwable]
                               }.pure[F]
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
            .map(_.map(_.getBlockMessage))
      }

      approver <- if (conf.casper.standalone) {
                   for {
                     genesis <- Resource.liftF {
                                 for {
                                   bonds <- readBondsFile
                                   _     <- Log[F].info("Constructing Genesis candidate...")
                                   genesis <- Genesis[F](
                                               conf.casper.walletsFile,
                                               conf.casper.minimumBond,
                                               conf.casper.maximumBond,
                                               conf.casper.hasFaucet,
                                               conf.casper.shardId,
                                               conf.casper.deployTimestamp
                                             ).map(_.getBlockMessage)
                                   // Store it so others can pull it from the bootstrap node.
                                   _ <- Log[F].info(
                                         s"Trying to store generated Genesis candidate ${genesis.blockHash}..."
                                       )
                                   _ <- validateAndAddBlock(conf.casper.shardId, genesis)
                                 } yield genesis
                               }

                     approver <- GenesisApproverImpl.fromGenesis(
                                  backend,
                                  NodeDiscovery[F],
                                  connectToGossip,
                                  // TODO: Move to config.
                                  relayFactor = 10,
                                  genesis = genesis,
                                  maybeApproval = maybeApproveBlock(genesis)
                                )
                   } yield approver
                 } else {
                   for {
                     bootstrap <- unsafeGetBootstrap[F](conf)
                     approver <- GenesisApproverImpl.fromBootstrap(
                                  backend,
                                  NodeDiscovery[F],
                                  connectToGossip,
                                  bootstrap = bootstrap,
                                  // TODO: Move to config.
                                  relayFactor = 10,
                                  pollInterval = 30.seconds,
                                  downloadManager = downloadManager
                                )
                   } yield approver
                 }
    } yield approver

  private def makeSynchronizer[F[_]: Concurrent: Par: Log: Metrics: MultiParentCasperRef: BlockDagStorage](
      connectToGossip: GossipService.Connector[F],
      awaitApproved: F[Unit]
  ): Resource[F, Synchronizer[F]] = Resource.liftF {
    for {
      _ <- SynchronizerImpl.establishMetrics[F]
      underlying <- Sync[F].delay {
                     new SynchronizerImpl[F](
                       connectToGossip,
                       new SynchronizerImpl.Backend[F] {
                         override def tips: F[List[ByteString]] =
                           for {
                             casper    <- unsafeGetCasper[F]
                             dag       <- casper.blockDag
                             tipHashes <- casper.estimator(dag)
                           } yield tipHashes.toList

                         override def justifications: F[List[ByteString]] =
                           for {
                             casper <- unsafeGetCasper[F]
                             dag    <- casper.blockDag
                             latest <- dag.latestMessageHashes
                           } yield latest.values.toList

                         override def validate(blockSummary: BlockSummary): F[Unit] =
                           // TODO: Presently the Validation only works on full blocks.
                           // Logging so we get an idea of how many summaries we are pulling.
                           Log[F].debug(
                             s"Trying to validate block summary ${show(blockSummary.blockHash)}"
                           )

                         override def notInDag(blockHash: ByteString): F[Boolean] =
                           isInDag(blockHash).map(!_)
                       },
                       // TODO: Move to config.
                       maxPossibleDepth = 1000,
                       minBlockCountToCheckBranchingFactor = 100,
                       // Really what we should be looking at is the width at any rank being less than the number of validators.
                       maxBranchingFactor = 1.5,
                       maxDepthAncestorsRequest = 10
                     )
                   }
      stashing <- StashingSynchronizer.wrap(underlying, awaitApproved)
    } yield stashing
  }

  /** Create and start the gossip service. */
  private def makeGossipServiceServer[F[_]: Concurrent: Par: TaskLike: ObservableIterant: Log: Metrics: BlockStore: BlockDagStorage: MultiParentCasperRef](
      port: Int,
      conf: Configuration,
      synchronizer: Synchronizer[F],
      downloadManager: DownloadManager[F],
      genesisApprover: GenesisApprover[F],
      serverSslContext: SslContext,
      grpcScheduler: Scheduler
  )(implicit logId: Log[Id], metricsId: Metrics[Id]): Resource[F, GossipServiceServer[F]] =
    for {
      backend <- Resource.pure[F, GossipServiceServer.Backend[F]] {
                  new GossipServiceServer.Backend[F] {
                    override def hasBlock(blockHash: ByteString): F[Boolean] =
                      isInDag(blockHash)

                    override def getBlockSummary(blockHash: ByteString): F[Option[BlockSummary]] =
                      BlockStore[F]
                        .getBlockSummary(blockHash)

                    override def getBlock(blockHash: ByteString): F[Option[Block]] =
                      BlockStore[F]
                        .get(blockHash)
                        .map(_.map(_.getBlockMessage))
                  }
                }

      consensus <- Resource.pure[F, GossipServiceServer.Consensus[F]] {
                    new GossipServiceServer.Consensus[F] {
                      override def onPending(dag: Vector[consensus.BlockSummary]) =
                        // The EquivocationDetector treats equivocations with children differently,
                        // so let Casper know about the DAG dependencies up front.
                        for {
                          _      <- Log[F].debug(s"Feeding ${dag.size} pending blocks to Casper.")
                          casper <- unsafeGetCasper[F].map(_.asInstanceOf[MultiParentCasperImpl[F]])
                          _ <- dag.traverse { summary =>
                                val partialBlock = consensus
                                  .Block()
                                  .withBlockHash(summary.blockHash)
                                  .withHeader(summary.getHeader)

                                casper.addMissingDependencies(partialBlock)
                              }
                        } yield ()

                      override def onDownloaded(blockHash: ByteString) =
                        // Calling `addBlock` during validation has already stored the block.
                        Log[F].debug(s"Download ready for ${show(blockHash)}")

                      override def listTips =
                        for {
                          casper    <- unsafeGetCasper[F]
                          dag       <- casper.blockDag
                          tipHashes <- casper.estimator(dag)
                          tips      <- tipHashes.toList.traverse(backend.getBlockSummary(_))
                        } yield tips.flatten
                    }
                  }

      server <- Resource.liftF {
                 GossipServiceServer[F](
                   backend,
                   synchronizer,
                   downloadManager,
                   consensus,
                   genesisApprover,
                   maxChunkSize = conf.server.chunkSize,
                   // TODO: Move to config.
                   maxParallelBlockDownloads = 5
                 )
               }

      // Start the gRPC server.
      _ <- {
        implicit val s = grpcScheduler
        GrpcServer(
          port,
          services = List(
            (scheduler: Scheduler) =>
              Sync[F].delay {
                val svc = GrpcGossipService.fromGossipService(
                  server,
                  // TODO: Move to config.
                  blockChunkConsumerTimeout = 10.seconds
                )
                GossipingGrpcMonix.bindService(svc, scheduler)
              }
          ),
          interceptors = List(
            new AuthInterceptor(),
            new MetricsInterceptor(),
            ErrorInterceptor.default
          ),
          sslContext = serverSslContext.some,
          maxMessageSize = conf.server.maxMessageSize.some
        )
      }

    } yield server

  /** Make the best effort so sync with the bootstrap node and some others.
    * Nothing really depends on this at the moment so just do it in the background. */
  private def makeInitialSynchronization[F[_]: Concurrent: Par: Log: Timer: NodeDiscovery](
      conf: Configuration,
      gossipServiceServer: GossipServiceServer[F],
      connectToGossip: GossipService.Connector[F]
  ): Resource[F, Unit] =
    conf.server.bootstrap map { bootstrap =>
      for {
        initialSync <- Resource.pure[F, InitialSynchronization[F]] {
                        new InitialSynchronizationImpl(
                          NodeDiscovery[F],
                          gossipServiceServer,
                          InitialSynchronizationImpl.Bootstrap(bootstrap),
                          // TODO: Move to config
                          selectNodes = (b, ns) => b +: ns.take(5),
                          minSuccessful = 1,
                          memoizeNodes = false,
                          skipFailedNodesInNextRounds = false,
                          roundPeriod = 30.seconds,
                          connector = connectToGossip
                        )
                      }
        _ <- makeFiberResource(initialSync.sync())
      } yield ()
    } getOrElse Resource.pure(())

  /** The TransportLayer setup prints the number of peers now and then which integration tests
    * may depend upon. We aren't using the `Connect` functionality so have to do it another way. */
  private def makePeerCountPrinter[F[_]: Concurrent: Time: Log: NodeDiscovery]
    : Resource[F, Unit] = {
    def loop(prevPeers: Set[Node]): F[Unit] = {
      // Based on Connecttions.removeConn
      val newPeers = for {
        peers <- NodeDiscovery[F].alivePeersAscendingDistance.map(_.toSet)
        _     <- Log[F].info(s"Peers: ${peers.size}").whenA(peers.size != prevPeers.size)
        _ <- (prevPeers diff peers).toList.traverse { peer =>
              Log[F].info(s"Disconnected from ${peer.show}")
            }
        _ <- (peers diff prevPeers).toList.traverse { peer =>
              Log[F].info(s"Connected to ${peer.show}")
            }
        _ <- Time[F].sleep(15.seconds)
      } yield peers

      Time[F].sleep(5.seconds) *> newPeers flatMap { peers =>
        loop(peers)
      }
    }

    makeFiberResource(loop(Set.empty)).map(_ => ())
  }

  /** Start something in a fiber. Make sure it stops if the resource is released. */
  private def makeFiberResource[F[_]: Concurrent, A](f: F[A]): Resource[F, Fiber[F, A]] =
    Resource {
      Concurrent[F].start(f).map { fiber =>
        (fiber, fiber.cancel.attempt.void)
      }
    }

  private def show(hash: ByteString) =
    PrettyPrinter.buildString(hash)

  // Should only be called in non-stand alone mode.
  private def unsafeGetBootstrap[F[_]: MonadThrowable](conf: Configuration): Resource[F, Node] =
    Resource.liftF(
      MonadThrowable[F]
        .fromOption(
          conf.server.bootstrap,
          new java.lang.IllegalStateException("Bootstrap node hasn't been configured.")
        )
    )
}
