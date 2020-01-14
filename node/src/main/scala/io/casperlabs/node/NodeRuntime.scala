package io.casperlabs.node

import java.nio.file.Path

import cats._
import cats.effect._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.mtl.{FunctorRaise, MonadState}
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import com.olegpy.meow.effects._
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper._
import io.casperlabs.casper.MultiParentCasperImpl.Broadcaster
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.validation.{Validation, ValidationImpl}
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib.effect.implicits.syncId
import io.casperlabs.comm._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.grpc.SslContexts
import io.casperlabs.comm.rp._
import io.casperlabs.ipc.ChainSpec
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import io.casperlabs.node.api.EventStream
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.{ExecutionEngineService, GrpcExecutionEngineService}
import io.casperlabs.storage.SQLiteStorage
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag._
import io.casperlabs.storage.deploy.{DeployStorage, DeployStorageWriter}
import io.casperlabs.storage.util.fileIO._
import io.casperlabs.storage.util.fileIO.IOError._
import io.netty.handler.ssl.ClientAuth
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.AtomicLong
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location

import scala.concurrent.duration._

class NodeRuntime private[node] (
    conf: Configuration,
    chainSpec: ChainSpec,
    id: NodeIdentifier,
    mainScheduler: Scheduler
)(
    implicit log: Log[Task],
    logId: Log[Id],
    uncaughtExceptionHandler: UncaughtExceptionHandler
) {

  private[this] val loopScheduler =
    Scheduler.fixedPool("loop", 2, reporter = uncaughtExceptionHandler)

  // Bounded thread pool for incoming traffic. Limited thread pool size so loads of request cannot exhaust all resources.
  private[this] val ingressScheduler =
    Scheduler.cached("ingress-io", 2, 64, reporter = uncaughtExceptionHandler)
  // Unbounded thread pool for outgoing, blocking IO. It is recommended to have unlimited thread pools for waiting on IO.
  private[this] val egressScheduler =
    Scheduler.cached("egress-io", 2, Int.MaxValue, reporter = uncaughtExceptionHandler)

  private[this] val dbConnScheduler =
    Scheduler.cached("db-conn", 1, 64, reporter = uncaughtExceptionHandler)
  private[this] val dbIOScheduler =
    Scheduler.cached("db-io", 1, Int.MaxValue, reporter = uncaughtExceptionHandler)

  implicit val raiseIOError: RaiseIOError[Task] = IOError.raiseIOErrorThroughSync[Task]

  implicit val concurrentEffectForEffect = {
    implicit val s = mainScheduler
    implicitly[ConcurrentEffect[Task]]
  }

  // intra-node gossiping port.
  private val port         = conf.server.port
  private val kademliaPort = conf.server.kademliaPort

  /**
    * Main node entry. It will:
    * 1. set up configurations
    * 2. create instances of typeclasses
    * 3. run the node program.
    */
  // TODO: Resolve scheduler chaos in Runtime, RuntimeManager and CasperPacketHandler

  val main: Task[Unit] = {
    implicit val metricsId: Metrics[Id] = diagnostics.effects.metrics[Id](syncId)
    implicit val filesApiEff            = FilesAPI.create[Task](Sync[Task], log)

    // SSL context to use for the public facing API.
    val maybeApiSslContext = if (conf.grpc.useTls) {
      val (cert, key) = conf.tls.readPublicApiCertAndKey
      Option(SslContexts.forServer(cert, key, ClientAuth.NONE))
    } else None

    implicit val metrics = diagnostics.effects.metrics[Task]

    val resources = for {
      implicit0(executionEngineService: ExecutionEngineService[Task]) <- GrpcExecutionEngineService[
                                                                          Task
                                                                        ](
                                                                          conf.grpc.socket,
                                                                          conf.server.maxMessageSize,
                                                                          conf.server.engineParallelism.value
                                                                        )
      //TODO: We may want to adjust threading model for better performance
      (writeTransactor, readTransactor) <- effects.doobieTransactors(
                                            connectEC = dbConnScheduler,
                                            transactEC = dbIOScheduler,
                                            conf.server.dataDir
                                          )
      _ <- Resource.liftF(runRdmbsMigrations(conf.server.dataDir))

      implicit0(
        storage: BlockStorage[Task] with DagStorage[Task] with DeployStorage[Task] with FinalityStorage[
          Task
        ]
      ) <- Resource.liftF(
            SQLiteStorage.create[Task](
              deployStorageChunkSize = conf.blockstorage.deployStreamChunkSize,
              readXa = readTransactor,
              writeXa = writeTransactor,
              wrapBlockStorage = (underlyingBlockStorage: BlockStorage[Task]) =>
                CachingBlockStorage[Task](
                  underlyingBlockStorage,
                  maxSizeBytes = conf.blockstorage.cacheMaxSizeBytes
                ),
              wrapDagStorage = (underlyingDagStorage: DagStorage[Task]
                with DagRepresentation[Task]
                with FinalityStorage[Task]) =>
                CachingDagStorage[Task](
                  underlyingDagStorage,
                  maxSizeBytes = conf.blockstorage.cacheMaxSizeBytes,
                  neighborhoodAfter = conf.blockstorage.cacheNeighborhoodAfter,
                  neighborhoodBefore = conf.blockstorage.cacheNeighborhoodBefore
                ).map(
                  cache =>
                    // Compiler fails to infer the proper type without this
                    cache: DagStorage[Task] with DagRepresentation[Task] with FinalityStorage[Task]
                )
            )
          )

      _ <- Resource.liftF {
            Task
              .delay {
                log.info("Cleaning storage ...")
                storage.clear()
              }
              .whenA(conf.server.cleanBlockStorage)
          }

      genesis <- makeGenesis[Task](chainSpec)

      validatorId <- Resource.liftF(ValidatorIdentity.fromConfig[Task](conf.casper))

      implicit0(deployBuffer: DeployBuffer[Task]) <- Resource.pure[Task, DeployBuffer[Task]](
                                                      DeployBuffer.create[Task](
                                                        chainSpec.getGenesis.name,
                                                        conf.casper.minTtl
                                                      )
                                                    )

      implicit0(state: MonadState[Task, RPConf]) <- Resource
                                                     .liftF((for {
                                                       local      <- localPeerNode[Task]()
                                                       bootstraps <- initPeers[Task]
                                                       conf <- rpConf[Task](
                                                                local
                                                                  .withChainId(genesis.blockHash),
                                                                bootstraps.map(
                                                                  _.withChainId(genesis.blockHash)
                                                                )
                                                              )
                                                     } yield conf.stateInstance))
      implicit0(nodeAsk: NodeAsk[Task])            = effects.peerNodeAsk(state)
      implicit0(boostrapsAsk: BootstrapsAsk[Task]) = effects.bootstrapsAsk(state)

      lfb <- Resource.liftF[Task, BlockHash](
              storage.getLastFinalizedBlock
            )

      implicit0(eventsStream: EventStream[Task]) <- Resource.pure[Task, EventStream[Task]](
                                                     EventStream
                                                       .create[Task](
                                                         ingressScheduler,
                                                         conf.server.eventStreamBufferSize.value
                                                       )
                                                   )

      implicit0(finalizedBlocksStream: FinalizedBlocksStream[Task]) <- Resource.suspend(
                                                                        FinalizedBlocksStream
                                                                          .of[Task](lfb)
                                                                      )

      implicit0(nodeDiscovery: NodeDiscovery[Task]) <- effects.nodeDiscovery(
                                                        id,
                                                        kademliaPort,
                                                        conf.server.defaultTimeout,
                                                        conf.server.alivePeersCacheExpirationPeriod,
                                                        conf.server.relayFactor,
                                                        conf.server.relaySaturation,
                                                        ingressScheduler,
                                                        egressScheduler
                                                      )

      implicit0(raise: FunctorRaise[Task, InvalidBlock]) = validation
        .raiseValidateErrorThroughApplicativeError[Task]
      implicit0(validationEff: Validation[Task]) = ValidationImpl.metered[Task]

      // TODO: Only a loop started with the TransportLayer keeps filling this up,
      // so if we use the GossipService it's going to stay empty. The diagnostics
      // should use NodeDiscovery instead.
      implicit0(connectionsCell: Connect.ConnectionsCell[Task]) <- Resource.liftF(
                                                                    effects.rpConnections
                                                                  )

      implicit0(multiParentCasperRef: MultiParentCasperRef[Task]) <- Resource.liftF(
                                                                      MultiParentCasperRef
                                                                        .of[Task]
                                                                    )

      // Creating with 0 permits initially, enabled after the initial synchronization.
      blockApiLock <- Resource.liftF(Semaphore[Task](0))

      // Set up gossiping machinery and start synchronizing with the network.
      broadcastAndSync <- casper.gossiping
                           .apply[
                             Task
                           ](
                             port,
                             conf,
                             chainSpec,
                             genesis,
                             ingressScheduler,
                             egressScheduler
                           )

      implicit0(broadcaster: Broadcaster[Task]) = broadcastAndSync._1
      awaitSynchronization                      = broadcastAndSync._2

      // Enable block production after sync.
      _ <- Resource.liftF((awaitSynchronization >> blockApiLock.releaseN(1)).start)

      // For now just either starting the auto-proposer or not, but ostensibly we
      // could pass it the flag to run or not and also wire it into the ControlService
      // so that the operator can turn it on/off on the fly.
      _ <- AutoProposer[Task](
            checkInterval = conf.casper.autoProposeCheckInterval,
            ballotInterval = conf.casper.autoProposeBallotInterval,
            accInterval = conf.casper.autoProposeAccInterval,
            accCount = conf.casper.autoProposeAccCount,
            blockApiLock = blockApiLock
          ).whenA(conf.casper.autoProposeEnabled)

      _ <- api.Servers
            .internalServersR(
              conf.grpc.portInternal,
              conf.server.maxMessageSize,
              ingressScheduler,
              blockApiLock,
              maybeApiSslContext
            )

      _ <- api.Servers.externalServersR[Task](
            conf.grpc.portExternal,
            conf.server.maxMessageSize,
            ingressScheduler,
            maybeApiSslContext,
            validatorId.isEmpty
          )

      _ <- api.Servers.httpServerR[Task](
            conf.server.httpPort,
            conf,
            id,
            ingressScheduler
          )
    } yield (nodeAsk, nodeDiscovery, storage.writer)

    resources.allocated flatMap {
      case ((nodeAsk, nodeDiscovery, deployStorage), release) =>
        handleUnrecoverableErrors {
          nodeProgram(nodeAsk, nodeDiscovery, deployStorage, release)
        }
    }

  }

  private def runRdmbsMigrations(serverDataDir: Path): Task[Unit] =
    Task.delay {
      val db = serverDataDir.resolve("sqlite.db").toString
      val conf =
        Flyway
          .configure()
          .dataSource(s"jdbc:sqlite:$db", "", "")
          .locations(new Location("classpath:/db/migration"))
      val flyway = conf.load()
      flyway.migrate()
      ()
    }

  /** Start periodic tasks as fibers. They'll automatically stop during shutdown. */
  private def nodeProgram(
      implicit
      localAsk: NodeAsk[Task],
      nodeDiscovery: NodeDiscovery[Task],
      deployStorageWriter: DeployStorageWriter[Task],
      release: Task[Unit]
  ): Task[Unit] = {

    val time = effects.time

    val info: Task[Unit] =
      if (conf.casper.standalone)
        Log[Task].info(s"Starting stand-alone node.")
      else
        Log[Task].info(
          s"Starting node that will bootstrap from ${conf.server.bootstrap.map(_.show).mkString(", ") -> "bootstraps"}"
        )

    val cleanupDiscardedDeploysLoop: Task[Unit] = for {
      _ <- deployStorageWriter.cleanupDiscarded(1.hour)
      _ <- time.sleep(1.minute)
    } yield ()

    val checkPendingDeploysExpirationLoop: Task[Unit] = for {
      _ <- deployStorageWriter.markAsDiscarded(24.hours)
      _ <- time.sleep(1.minute)
    } yield ()

    for {
      _ <- addShutdownHook(release)
      _ <- info
      _ <- Task
            .defer(cleanupDiscardedDeploysLoop.forever)
            .executeOn(loopScheduler)
            .start

      _ <- Task
            .defer(checkPendingDeploysExpirationLoop.forever)
            .executeOn(loopScheduler)
            .start

      localNode <- localAsk.ask
      _         <- Log[Task].info(s"Listening for traffic on ${localNode.show -> "peer"}.")
      // This loop will keep the program from exiting until shutdown is initiated.
      _ <- NodeDiscovery[Task].discover.attemptAndLog.executeOn(loopScheduler)
    } yield ()
  }

  private def shutdown(release: Task[Unit]): Unit = {
    implicit val s = egressScheduler
    // Everything has been moved to Resources.
    val task = for {
      _ <- log.info("Shutting down...")
      _ <- release
      _ <- log.info("Goodbye.")
    } yield ()
    // Run the release synchronously so that we can see the final message.
    task.runSyncUnsafe(1.minute)
  }

  private def addShutdownHook(release: Task[Unit]): Task[Unit] =
    Task.delay(
      sys.addShutdownHook(shutdown(release))
    )

  /**
    * Handles unrecoverable errors in program. Those are errors that should not happen in properly
    * configured environment and they mean immediate termination of the program
    */
  private def handleUnrecoverableErrors(prog: Task[Unit]): Task[Unit] =
    prog
      .onErrorHandleWith { th =>
        log.error("Caught unhandable error. Exiting. Stacktrace below.") *> Task.delay {
          th.printStackTrace()
        }
      } *> Task.delay(System.exit(1)).as(Right(()))

  private def rpConf[F[_]: Sync](local: Node, bootstraps: List[Node]) =
    Ref.of[F, RPConf](
      RPConf(
        local,
        bootstraps,
        conf.server.defaultTimeout,
        ClearConnectionsConf(
          conf.server.maxNumOfConnections,
          // TODO read from conf
          numOfConnectionsPinged = 10
        )
      )
    )

  private def localPeerNode[F[_]: Sync: Log]() =
    WhoAmI
      .fetchLocalPeerNode[F](
        conf.server.host,
        conf.server.port,
        conf.server.kademliaPort,
        conf.server.noUpnp,
        id
      )

  private def initPeers[F[_]: MonadThrowable]: F[List[NodeWithoutChainId]] =
    conf.server.bootstrap match {
      case Nil if !conf.casper.standalone =>
        MonadThrowable[F].raiseError(
          new java.lang.IllegalStateException(
            "Not in standalone mode but there's no bootstrap configured!"
          )
        )
      case nodes =>
        nodes.pure[F]
    }

  private def makeGenesis[F[_]: MonadThrowable](chainSpec: ChainSpec)(
      implicit E: ExecutionEngineService[F],
      L: Log[F],
      B: BlockStorage[F],
      FS: FinalityStorage[F]
  ): Resource[F, Block] =
    Resource.liftF[F, Block] {
      for {
        _       <- Log[F].info(s"Constructing Genesis block...")
        genesis <- Genesis.fromChainSpec[F](chainSpec.getGenesis)
        _ <- Log[F].info(
              s"Genesis hash is ${PrettyPrinter.buildString(genesis.getBlockMessage.blockHash) -> "genesis" -> null}"
            )
      } yield genesis.getBlockMessage
    }
}

object NodeRuntime {
  def apply(
      conf: Configuration,
      chainSpec: ChainSpec
  )(
      implicit
      scheduler: Scheduler,
      log: Log[Task],
      logId: Log[Id],
      uncaughtExceptionHandler: UncaughtExceptionHandler
  ): Task[NodeRuntime] =
    for {
      id      <- NodeEnvironment.create(conf)
      runtime <- Task.delay(new NodeRuntime(conf, chainSpec, id, scheduler))
    } yield runtime
}
