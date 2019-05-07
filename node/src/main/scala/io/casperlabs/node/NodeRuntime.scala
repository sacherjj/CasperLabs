package io.casperlabs.node

import cats._
import cats.data._
import cats.effect._
import cats.effect.concurrent.{Ref, Semaphore}
import cats.mtl.MonadState
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.olegpy.meow.effects._
import io.casperlabs.blockstorage.util.fileIO.IOError
import io.casperlabs.blockstorage.util.fileIO.IOError.RaiseIOError
import io.casperlabs.blockstorage.{
  BlockDagFileStorage,
  BlockDagStorage,
  BlockStore,
  FileLMDBIndexBlockStore
}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.util.comm.CasperPacketHandler
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits.{bracketEitherTThrowable, taskLiftEitherT}
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk, RPConfState}
import io.casperlabs.comm.rp._
import io.casperlabs.comm.transport._
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.node.diagnostics._
import io.casperlabs.p2p.effects._
import io.casperlabs.shared.PathOps._
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.{ExecutionEngineService, GrpcExecutionEngineService}
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
  private[this] val grpcScheduler =
    Scheduler.cached("grpc-io", 4, 64, reporter = UncaughtExceptionLogger)

  private val initPeer = if (conf.casper.standalone) None else Some(conf.server.bootstrap)

  implicit val raiseIOError: RaiseIOError[Effect] = IOError.raiseIOErrorThroughSync[Effect]

  import ApplicativeError_._

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
    val rpConfState         = localPeerNode[Task].flatMap(rpConf[Task]).toEffect
    val logEff: Log[Effect] = Log.eitherTLog(Monad[Task], log)

    rpConfState >>= (_.runState { implicit state =>
      val resources = for {
        executionEngineService <- GrpcExecutionEngineService[Effect](
                                   conf.grpc.socket,
                                   conf.server.maxMessageSize,
                                   initBonds = Map.empty
                                 )

        metrics                     = diagnostics.effects.metrics[Task]
        metricsEff: Metrics[Effect] = Metrics.eitherT[CommError, Task](Monad[Task], metrics)

        nodeDiscovery <- effects.nodeDiscovery(id, kademliaPort, conf.server.defaultTimeout.millis)(
                          initPeer
                        )(
                          grpcScheduler,
                          effects.peerNodeAsk,
                          log,
                          effects.time,
                          metrics
                        )

        blockStore <- FileLMDBIndexBlockStore[Effect](
                       conf.server.dataDir,
                       blockstorePath,
                       100L * 1024L * 1024L * 4096L
                     )(
                       Concurrent[Effect],
                       logEff,
                       raiseIOError,
                       metricsEff
                     )

        blockDagStorage <- BlockDagFileStorage[Effect](
                            conf.server.dataDir,
                            dagStoragePath,
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
        connectionsCell <- Resource.liftF(effects.rpConnections.toEffect)

        multiParentCasperRef <- Resource.liftF(MultiParentCasperRef.of[Effect])

        safetyOracle = SafetyOracle
          .cliqueOracle[Effect](Monad[Effect], logEff)

        _ <- api.Servers
              .diagnosticsServerR(
                conf.grpc.portInternal,
                conf.server.maxMessageSize,
                grpcScheduler
              )(
                logEff,
                nodeDiscovery,
                jvmMetrics,
                nodeMetrics,
                connectionsCell,
                scheduler
              )

        _ <- api.Servers.deploymentServerR[Effect](
              conf.grpc.portExternal,
              conf.server.maxMessageSize,
              grpcScheduler
            )(
              Concurrent[Effect],
              TaskLike[Effect],
              logEff,
              multiParentCasperRef,
              metricsEff,
              safetyOracle,
              blockStore,
              executionEngineService,
              scheduler
            )

        _ <- api.Servers.httpServerR(
              conf.server.httpPort,
              conf,
              id
            )(
              log,
              nodeDiscovery,
              connectionsCell,
              scheduler
            )

        _ <- casper.transport.apply(
              port,
              conf,
              grpcScheduler
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
              scheduler
            )

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
      else Log[Effect].info(s"Starting node that will bootstrap from ${conf.server.bootstrap}")

    val loop: Effect[Unit] =
      for {
        _ <- time.sleep(1.minute).toEffect
        // NOTE: All the original periodic tasks were moved into the transport module resource setup.
        // The NodeDiscovery loop should be enough for the gossiping, but the diagnostics would not
        // show the info if it's based on ConnectionsCell.
      } yield ()

    val casperLoop: Effect[Unit] =
      for {
        casper <- multiParentCasperRef.get
        _      <- casper.fold(().pure[Effect])(_.fetchDependencies)
        _      <- time.sleep(30.seconds).toEffect
      } yield ()

    for {
      _       <- info
      local   <- peerNodeAsk.ask.toEffect
      host    = local.host
      _       <- addShutdownHook(release).toEffect
      _       <- NodeDiscovery[Task].discover.attemptAndLog.executeOn(loopScheduler).start.toEffect
      _       <- Task.defer(casperLoop.forever.value).executeOn(loopScheduler).start.toEffect
      address = s"casperlabs://$id@$host?protocol=$port&discovery=$kademliaPort"
      _       <- Log[Effect].info(s"Listening for traffic on $address.")
      // This loop will keep the program from exiting until shutdown is initiated.
      _ <- EitherT(Task.defer(loop.forever.value).executeOn(loopScheduler))
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

  private def rpConf[F[_]: Sync](local: Node) =
    Ref.of(
      RPConf(
        local,
        initPeer,
        conf.server.defaultTimeout.millis,
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
