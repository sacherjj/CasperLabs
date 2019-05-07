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
import org.http4s.server.blaze._

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

  implicit def eitherTrpConfAsk(implicit ev: RPConfAsk[Task]): RPConfAsk[Effect] =
    new EitherTApplicativeAsk[Task, RPConf, CommError]

  import ApplicativeError_._

  private val port           = conf.server.port
  private val kademliaPort   = conf.server.kademliaPort
  private val blockstorePath = conf.server.dataDir.resolve("blockstore")
  private val dagStoragePath = conf.server.dataDir.resolve("dagstorage")
  private val defaultTimeout = FiniteDuration(conf.server.defaultTimeout.toLong, MILLISECONDS) // TODO remove

  // TODO: Move into resources
  val metrics = diagnostics.effects.metrics[Task]
  val metricsEff: Metrics[EitherT[Task, CommError, ?]] =
    Metrics.eitherT[CommError, Task](Monad[Task], metrics)

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

        nodeDiscovery <- effects.nodeDiscovery(id, kademliaPort, defaultTimeout)(initPeer)(
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

        blockDag <- BlockDagFileStorage[Effect](conf.server.dataDir, dagStoragePath, blockStore)(
                     Concurrent[Effect],
                     logEff,
                     raiseIOError,
                     metricsEff
                   )

        nodeMetrics = diagnostics.effects.nodeCoreMetrics[Task]
        jvmMetrics  = diagnostics.effects.jvmMetrics[Task]

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

      } yield (executionEngineService, nodeDiscovery, blockStore, blockDag)

      resources.use {
        case (executionEngineService, nodeDiscovery, blockStore, dag) =>
          runMain(executionEngineService, nodeDiscovery, blockStore, dag, state)
      }
    })
  }

  def runMain(
      implicit
      executionEngineService: ExecutionEngineService[Effect],
      nodeDiscovery: NodeDiscovery[Task],
      blockStore: BlockStore[Effect],
      blockDagStorage: BlockDagStorage[Effect],
      rpConfState: MonadState[Task, RPConf]
  ) =
    for {
      rpConnections        <- effects.rpConnections.toEffect
      defaultTimeout       = conf.server.defaultTimeout.millis
      rpConfAsk            = effects.rpConfAsk
      peerNodeAsk          = effects.peerNodeAsk
      tcpConnections       <- CachedConnections[Task, TcpConnTag](Task.catsAsync, metrics).toEffect
      time                 = effects.time
      multiParentCasperRef <- MultiParentCasperRef.of[Effect]
      lab                  <- LastApprovedBlock.of[Task].toEffect
      labEff               = LastApprovedBlock.eitherTLastApprovedBlock[CommError, Task](Monad[Task], lab)
      _ <- Task
            .delay {
              log.info("Cleaning block storage ...")
              blockStore.clear() *> blockDagStorage.clear()
            }
            .whenA(conf.server.cleanBlockStorage)
            .toEffect
      commTmpFolder = conf.server.dataDir.resolve("tmp").resolve("comm")
      _             <- commTmpFolder.deleteDirectory[Task]().toEffect
      transport = effects.tcpTransportLayer(
        port,
        conf.tls.certificate,
        conf.tls.key,
        conf.server.maxMessageSize,
        conf.server.chunkSize,
        commTmpFolder
      )(grpcScheduler, log, metrics, tcpConnections)
      oracle = SafetyOracle.cliqueOracle[Effect](Monad[Effect], Log.eitherTLog(Monad[Task], log))
      casperPacketHandler <- CasperPacketHandler
                              .of[Effect](
                                conf.casper,
                                defaultTimeout,
                                executionEngineService,
                                _.value
                              )(
                                labEff,
                                metricsEff,
                                blockStore,
                                Cell.eitherTCell(Monad[Task], rpConnections),
                                NodeDiscovery.eitherTNodeDiscovery(Monad[Task], nodeDiscovery),
                                TransportLayer.eitherTTransportLayer(Monad[Task], log, transport),
                                ErrorHandler[Effect],
                                eitherTrpConfAsk(rpConfAsk),
                                oracle,
                                Sync[Effect],
                                Concurrent[Effect],
                                Time.eitherTTime(Monad[Task], time),
                                Log.eitherTLog(Monad[Task], log),
                                multiParentCasperRef,
                                blockDagStorage,
                                executionEngineService,
                                scheduler
                              )
      packetHandler = PacketHandler.pf[Effect](casperPacketHandler.handle)(
        Applicative[Effect],
        Log.eitherTLog(Monad[Task], log),
        ErrorHandler[Effect]
      )

      program = nodeProgram(executionEngineService)(
        time,
        rpConfState,
        rpConfAsk,
        peerNodeAsk,
        metrics,
        transport,
        nodeDiscovery,
        rpConnections,
        blockStore,
        oracle,
        packetHandler,
        multiParentCasperRef
      )
      _ <- handleUnrecoverableErrors(program)
    } yield ()

  private def clearResources[F[_]: Monad]()(
      implicit
      transport: TransportLayer[Task],
      peerNodeAsk: NodeAsk[Task]
  ): Unit =
    (for {
      _   <- log.info("Shutting down transport layer, broadcasting DISCONNECT")
      loc <- peerNodeAsk.ask
      msg = ProtocolHelper.disconnect(loc)
      _   <- transport.shutdown(msg)
      _   <- log.info("Goodbye.")
    } yield ()).unsafeRunSync(scheduler)

  private def addShutdownHook[F[_]: Monad]()(
      implicit transport: TransportLayer[Task],
      peerNodeAsk: NodeAsk[Task]
  ): Task[Unit] =
    Task.delay(
      sys.addShutdownHook(clearResources())
    )

  private def nodeProgram(
      executionEngineService: ExecutionEngineService[Effect]
  )(
      implicit
      time: Time[Task],
      rpConfState: RPConfState[Task],
      rpConfAsk: RPConfAsk[Task],
      peerNodeAsk: NodeAsk[Task],
      metrics: Metrics[Task],
      transport: TransportLayer[Task],
      nodeDiscovery: NodeDiscovery[Task],
      rpConnectons: ConnectionsCell[Task],
      blockStore: BlockStore[Effect],
      oracle: SafetyOracle[Effect],
      packetHandler: PacketHandler[Effect],
      casperConstructor: MultiParentCasperRef[Effect]
  ): Effect[Unit] = {

    val info: Effect[Unit] =
      if (conf.casper.standalone) Log[Effect].info(s"Starting stand-alone node.")
      else Log[Effect].info(s"Starting node that will bootstrap from ${conf.server.bootstrap}")

    val dynamicIpCheck: Task[Unit] =
      if (conf.server.dynamicHostAddress)
        for {
          local <- peerNodeAsk.ask
          newLocal <- WhoAmI
                       .checkLocalPeerNode[Task](conf.server.port, conf.server.kademliaPort, local)
          _ <- newLocal.fold(Task.unit) { pn =>
                Connect.resetConnections[Task].flatMap(kp(rpConfState.modify(_.copy(local = pn))))
              }
        } yield ()
      else Task.unit

    val loop: Effect[Unit] =
      for {
        _ <- time.sleep(1.minute).toEffect
        _ <- dynamicIpCheck.toEffect
        _ <- Connect.clearConnections[Effect]
        _ <- Connect.findAndConnect[Effect](Connect.connect[Effect])
      } yield ()

    val casperLoop: Effect[Unit] =
      for {
        casper <- casperConstructor.get
        _      <- casper.fold(().pure[Effect])(_.fetchDependencies)
        _      <- time.sleep(30.seconds).toEffect
      } yield ()

    for {
      _     <- info
      local <- peerNodeAsk.ask.toEffect
      host  = local.host
      _     <- addShutdownHook().toEffect
      _ <- TransportLayer[Effect].receive(
            pm => HandleMessages.handle[Effect](pm, defaultTimeout),
            blob => packetHandler.handlePacket(blob.sender, blob.packet).as(())
          )
      _       <- NodeDiscovery[Task].discover.attemptAndLog.executeOn(loopScheduler).start.toEffect
      _       <- Task.defer(casperLoop.forever.value).executeOn(loopScheduler).start.toEffect
      address = s"casperlabs://$id@$host?protocol=$port&discovery=$kademliaPort"
      _       <- Log[Effect].info(s"Listening for traffic on $address.")
      _       <- EitherT(Task.defer(loop.forever.value).executeOn(loopScheduler))
    } yield ()
  }

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

  private def syncEffect = cats.effect.Sync.catsEitherTSync[Task, CommError]

  private val rpClearConnConf = ClearConnectionsConf(
    conf.server.maxNumOfConnections,
    numOfConnectionsPinged = 10
  ) // TODO read from conf

  private def rpConf[F[_]: Sync](local: Node) =
    Ref.of(RPConf(local, initPeer, defaultTimeout, rpClearConnConf))

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
