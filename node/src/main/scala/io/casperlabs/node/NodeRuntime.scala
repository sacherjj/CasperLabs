package io.casperlabs.node

import cats._
import cats.data._
import cats.effect._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.syntax.show._
import com.olegpy.meow.effects._
import io.casperlabs.blockstorage.util.fileIO.IOError
import io.casperlabs.blockstorage.util.fileIO.IOError.RaiseIOError
import io.casperlabs.blockstorage.{
  BlockDagFileStorage,
  BlockStore,
  CachingBlockStore,
  FileLMDBIndexBlockStore
}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits.{syncId, taskLiftEitherT}
import io.casperlabs.comm._
import io.casperlabs.comm.grpc.SslContexts
import io.casperlabs.comm.discovery.NodeDiscovery._
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.rp.Connect.RPConfState
import io.casperlabs.comm.rp._
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api.graphql.FinalizedBlocksStream
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.{ExecutionEngineService, GrpcExecutionEngineService}
import io.netty.handler.ssl.ClientAuth
import monix.eval.{Task, TaskLike}
import monix.execution.Scheduler
import scala.concurrent.duration._

class NodeRuntime private[node] (
    conf: Configuration,
    id: NodeIdentifier,
    scheduler: Scheduler
)(implicit log: Log[Task]) {

  private[this] val loopScheduler =
    Scheduler.fixedPool("loop", 4, reporter = UncaughtExceptionLogger)
  private[this] val blockingScheduler =
    Scheduler.cached("blocking-io", 4, 64, reporter = UncaughtExceptionLogger)
  private implicit val concurrentEffectForEffect: ConcurrentEffect[Effect] =
    catsConcurrentEffectForEffect(
      scheduler
    )

  implicit val raiseIOError: RaiseIOError[Effect] = IOError.raiseIOErrorThroughSync[Effect]

  // intra-node gossiping port.
  private val port           = conf.server.port
  private val kademliaPort   = conf.server.kademliaPort
  private val blockstorePath = conf.server.dataDir.resolve("blockstore")
  private val dagStoragePath = conf.server.dataDir.resolve("dagstorage")

  /**
    * Main node entry. It will:
    * 1. set up configurations
    * 2. create instances of typeclasses
    * 3. run the node program.
    */
  // TODO: Resolve scheduler chaos in Runtime, RuntimeManager and CasperPacketHandler

  val main: Effect[Unit] = {
    val rpConfState = (for {
      local     <- localPeerNode[Task]
      bootstrap <- initPeer[Task]
      conf      <- rpConf[Task](local, bootstrap)
    } yield conf).toEffect

    val logEff: Log[Effect] = Log.eitherTLog(Monad[Task], log)

    val logId: Log[Id]         = Log.logId
    val metricsId: Metrics[Id] = diagnostics.effects.metrics[Id](syncId)

    // SSL context to use for the public facing API.
    val maybeApiSslContext = Option(conf.tls.readCertAndKey).filter(_ => conf.grpc.useTls).map {
      case (cert, key) =>
        SslContexts.forServer(cert, key, ClientAuth.NONE)
    }

    rpConfState >>= (_.runState { implicit state =>
      val metrics = diagnostics.effects.metrics[Task]
      implicit val metricsEff: Metrics[Effect] =
        Metrics.eitherT[CommError, Task](Monad[Task], metrics)
      val resources = for {
        implicit0(executionEngineService: ExecutionEngineService[Effect]) <- GrpcExecutionEngineService[
                                                                              Effect
                                                                            ](
                                                                              conf.grpc.socket,
                                                                              conf.server.maxMessageSize,
                                                                              initBonds = Map.empty
                                                                            )

        maybeBootstrap <- Resource.liftF(initPeer[Effect])

        implicit0(finalizedBlocksStream: FinalizedBlocksStream[Effect]) <- Resource.liftF(
                                                                            FinalizedBlocksStream
                                                                              .of[Effect]
                                                                          )

        implicit0(nodeDiscovery: NodeDiscovery[Task]) <- effects.nodeDiscovery(
                                                          id,
                                                          kademliaPort,
                                                          conf.server.defaultTimeout
                                                        )(
                                                          maybeBootstrap
                                                        )(
                                                          blockingScheduler,
                                                          effects.peerNodeAsk,
                                                          log,
                                                          effects.time,
                                                          metrics
                                                        )

        implicit0(blockStore: BlockStore[Effect]) <- FileLMDBIndexBlockStore[Effect](
                                                      conf.server.dataDir,
                                                      blockstorePath,
                                                      100L * 1024L * 1024L * 4096L
                                                    )(
                                                      Concurrent[Effect],
                                                      logEff,
                                                      raiseIOError,
                                                      metricsEff
                                                    ) evalMap { underlying =>
                                                      CachingBlockStore[Effect](
                                                        underlying,
                                                        maxSizeBytes =
                                                          conf.blockstorage.cacheMaxSizeBytes
                                                      )(
                                                        Sync[Effect],
                                                        metricsEff
                                                      )
                                                    }

        blockDagStorage <- BlockDagFileStorage[Effect](
                            dagStoragePath,
                            conf.blockstorage.latestMessagesLogMaxSizeFactor,
                            blockStore
                          )(
                            Concurrent[Effect],
                            logEff,
                            raiseIOError,
                            metricsEff
                          )

        _ <- Resource.liftF {
              Task
                .delay {
                  log.info("Cleaning block storage ...")
                  blockStore.clear() *> blockDagStorage.clear()
                }
                .whenA(conf.server.cleanBlockStorage)
                .toEffect
            }

        nodeMetrics = diagnostics.effects.nodeCoreMetrics[Task]
        jvmMetrics  = diagnostics.effects.jvmMetrics[Task]

        // TODO: Only a loop started with the TransportLayer keeps filling this up,
        // so if we use the GossipService it's going to stay empty. The diagnostics
        // should use NodeDiscovery instead.
        implicit0(connectionsCell: Connect.ConnectionsCell[Task]) <- Resource.liftF(
                                                                      effects.rpConnections.toEffect
                                                                    )

        implicit0(multiParentCasperRef: MultiParentCasperRef[Effect]) <- Resource.liftF(
                                                                          MultiParentCasperRef
                                                                            .of[Effect]
                                                                        )

        implicit0(safetyOracle: FinalityDetector[Effect]) = new FinalityDetectorInstancesImpl[
          Effect
        ]()(
          Monad[Effect],
          logEff
        )

        blockApiLock <- Resource.liftF(Semaphore[Effect](1))

        // For now just either starting the auto-proposer or not, but ostensibly we
        // could pass it the flag to run or not and also wire it into the ControlService
        // so that the operator can turn it on/off on the fly.
        _ <- AutoProposer[Effect](
              checkInterval = conf.casper.autoProposeCheckInterval,
              maxInterval = conf.casper.autoProposeMaxInterval,
              maxCount = conf.casper.autoProposeMaxCount,
              blockApiLock = blockApiLock
            )(
              Concurrent[Effect],
              Time.eitherTTime(Monad[Task], effects.time),
              logEff,
              metricsEff,
              multiParentCasperRef
            ).whenA(conf.casper.autoProposeEnabled)

        _ <- api.Servers
              .internalServersR(
                conf.grpc.portInternal,
                conf.server.maxMessageSize,
                blockingScheduler,
                blockApiLock,
                maybeApiSslContext
              )(
                logEff,
                logId,
                metricsEff,
                metricsId,
                nodeDiscovery,
                jvmMetrics,
                nodeMetrics,
                connectionsCell,
                multiParentCasperRef,
                scheduler
              )

        _ <- api.Servers.externalServersR[Effect](
              conf.grpc.portExternal,
              conf.server.maxMessageSize,
              blockingScheduler,
              blockApiLock,
              conf.casper.ignoreDeploySignature,
              maybeApiSslContext
            )(
              Concurrent[Effect],
              TaskLike[Effect],
              logEff,
              multiParentCasperRef,
              metricsEff,
              safetyOracle,
              blockStore,
              executionEngineService,
              scheduler,
              logId,
              metricsId
            )

        _ <- api.Servers.httpServerR[Effect](
              conf.server.httpPort,
              conf,
              id,
              blockingScheduler
            )

        _ <- if (conf.server.useGossiping) {
              casper.gossiping.apply[Effect](
                port,
                conf,
                blockingScheduler
              )(
                catsParForEffect,
                catsConcurrentEffectForEffect(scheduler),
                logEff,
                metricsEff,
                Time.eitherTTime(Monad[Task], effects.time),
                Timer[Effect],
                safetyOracle,
                blockStore,
                blockDagStorage,
                NodeDiscovery.eitherTNodeDiscovery(Monad[Task], nodeDiscovery),
                eitherTApplicativeAsk(effects.peerNodeAsk(state)),
                multiParentCasperRef,
                executionEngineService,
                finalizedBlocksStream,
                scheduler,
                logId,
                metricsId
              )
            } else {
              casper.transport.apply(
                port,
                conf,
                blockingScheduler
              )(
                log,
                logEff,
                metrics,
                metricsEff,
                safetyOracle,
                blockStore,
                blockDagStorage,
                connectionsCell,
                nodeDiscovery,
                state,
                multiParentCasperRef,
                executionEngineService,
                finalizedBlocksStream,
                scheduler
              )
            }

      } yield (nodeDiscovery, multiParentCasperRef)

      resources.allocated flatMap {
        case ((nodeDiscovery, multiParentCasperRef), release) =>
          handleUnrecoverableErrors {
            nodeProgram(state, nodeDiscovery, multiParentCasperRef, release)
          }
      }
    })
  }

  /** Start periodic tasks as fibers. They'll automatically stop during shutdown. */
  private def nodeProgram(
      implicit
      rpConfState: RPConfState[Task],
      nodeDiscovery: NodeDiscovery[Task],
      multiParentCasperRef: MultiParentCasperRef[Effect],
      release: Effect[Unit]
  ): Effect[Unit] = {

    val peerNodeAsk = effects.peerNodeAsk(rpConfState)
    val time        = effects.time

    val info: Effect[Unit] =
      if (conf.casper.standalone) Log[Effect].info(s"Starting stand-alone node.")
      else
        Log[Effect].info(
          s"Starting node that will bootstrap from ${conf.server.bootstrap.map(_.show).getOrElse("n/a")}"
        )

    val fetchLoop: Effect[Unit] =
      for {
        casper <- multiParentCasperRef.get
        _      <- casper.fold(().pure[Effect])(_.fetchDependencies)
        _      <- time.sleep(30.seconds).toEffect
      } yield ()

    for {
      _       <- addShutdownHook(release).toEffect
      _       <- info
      _       <- Task.defer(fetchLoop.forever.value).executeOn(loopScheduler).start.toEffect
      host    <- peerNodeAsk.ask.toEffect.map(_.host)
      address = s"casperlabs://$id@$host?protocol=$port&discovery=$kademliaPort"
      _       <- Log[Effect].info(s"Listening for traffic on $address.")
      // This loop will keep the program from exiting until shutdown is initiated.
      _ <- NodeDiscovery[Task].discover.attemptAndLog.executeOn(loopScheduler).toEffect
    } yield ()
  }

  private def shutdown(release: Effect[Unit]): Unit = {
    // Everything has been moved to Resources.
    val task = for {
      _ <- log.info("Shutting down...")
      _ <- release.value
      _ <- log.info("Goodbye.")
    } yield ()
    // Run the release synchronously so that we can see the final message.
    task.unsafeRunSync(scheduler)
  }

  private def addShutdownHook(release: Effect[Unit]): Task[Unit] =
    Task.delay(
      sys.addShutdownHook(shutdown(release))
    )

  /**
    * Handles unrecoverable errors in program. Those are errors that should not happen in properly
    * configured environment and they mean immediate termination of the program
    */
  private def handleUnrecoverableErrors(prog: Effect[Unit]): Effect[Unit] =
    EitherT[Task, CommError, Unit](
      prog.value
        .onErrorHandleWith { th =>
          log.error("Caught unhandable error. Exiting. Stacktrace below.") *> Task.delay {
            th.printStackTrace()
          }
        } *> Task.delay(System.exit(1)).as(Right(()))
    )

  private def rpConf[F[_]: Sync](local: Node, maybeBootstrap: Option[Node]) =
    Ref.of[F, RPConf](
      RPConf(
        local,
        maybeBootstrap,
        conf.server.defaultTimeout,
        ClearConnectionsConf(
          conf.server.maxNumOfConnections,
          // TODO read from conf
          numOfConnectionsPinged = 10
        )
      )
    )

  private def localPeerNode[F[_]: Sync: Log] =
    WhoAmI
      .fetchLocalPeerNode[F](
        conf.server.host,
        conf.server.port,
        conf.server.kademliaPort,
        conf.server.noUpnp,
        id
      )

  private def initPeer[F[_]: MonadThrowable]: F[Option[Node]] =
    conf.server.bootstrap match {
      case None if !conf.casper.standalone =>
        MonadThrowable[F].raiseError(
          new java.lang.IllegalStateException(
            "Not in standalone mode but there's no bootstrap configured!"
          )
        )
      case other =>
        other.pure[F]
    }

}

object NodeRuntime {
  def apply(
      conf: Configuration
  )(implicit scheduler: Scheduler, log: Log[Task]): Effect[NodeRuntime] =
    for {
      id      <- NodeEnvironment.create(conf)
      runtime <- Task.delay(new NodeRuntime(conf, id, scheduler)).toEffect
    } yield runtime
}
