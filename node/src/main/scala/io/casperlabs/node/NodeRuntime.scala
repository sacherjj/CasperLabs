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
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.{BlockStore, InMemBlockDagStorage, InMemBlockStore}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.util.comm.CasperPacketHandler
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib.effect.implicits.{bracketEitherTThrowable, taskLiftEitherT}
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.{GrpcServer => _, _}
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk, RPConfState}
import io.casperlabs.comm.rp._
import io.casperlabs.comm.transport._
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.api._
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.node.diagnostics._
import io.casperlabs.p2p.effects._
import io.casperlabs.shared.PathOps._
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.{ExecutionEngineService, GrpcExecutionEngineService}
import kamon._
import monix.eval.Task
import monix.execution.Scheduler
import org.http4s.server.blaze._
import com.olegpy.meow.effects._

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

  private implicit val logSource: LogSource = LogSource(this.getClass)

  implicit def eitherTrpConfAsk(implicit ev: RPConfAsk[Task]): RPConfAsk[Effect] =
    new EitherTApplicativeAsk[Task, RPConf, CommError]

  import ApplicativeError_._

  private val port           = conf.server.port
  private val kademliaPort   = conf.server.kademliaPort
  private val defaultTimeout = FiniteDuration(conf.server.defaultTimeout.toLong, MILLISECONDS) // TODO remove

  case class Servers(
      grpcServerExternal: GrpcServer,
      grpcServerInternal: GrpcServer,
      httpServer: Fiber[Task, Unit]
  )

  /**
    * Main node entry. It will:
    * 1. set up configurations
    * 2. create instances of typeclasses
    * 3. run the node program.
    */
  // TODO: Resolve scheduler chaos in Runtime, RuntimeManager and CasperPacketHandler

  val main: Effect[Unit] = {
    val rpConfState = localPeerNode[Task].flatMap(rpConf[Task]).toEffect
    rpConfState >>= (_.runState { implicit state =>
      val resources = for {
        ee <- GrpcExecutionEngineService[Effect](
               conf.grpc.socket,
               conf.server.maxMessageSize,
               initBonds = Map.empty
             )
        nd <- effects.nodeDiscovery(id, kademliaPort, defaultTimeout)(initPeer)(
               grpcScheduler,
               effects.peerNodeAsk,
               log,
               effects.time,
               diagnostics.effects.metrics)
      } yield (ee, nd)

      resources.use { case (ee, nd) => runMain(ee, nd, state) }
    })
  }

  def runMain(
      implicit
      executionEngineService: ExecutionEngineService[Effect],
      nodeDiscovery: NodeDiscovery[Task],
      rpConfState: MonadState[Task, RPConf]
  ) =
    for {
      rpConnections        <- effects.rpConnections.toEffect
      defaultTimeout       = conf.server.defaultTimeout.millis
      rpConfAsk            = effects.rpConfAsk
      peerNodeAsk          = effects.peerNodeAsk
      metrics              = diagnostics.effects.metrics[Task]
      tcpConnections       <- CachedConnections[Task, TcpConnTag](Task.catsAsync, metrics).toEffect
      time                 = effects.time
      multiParentCasperRef <- MultiParentCasperRef.of[Effect]
      lab                  <- LastApprovedBlock.of[Task].toEffect
      labEff               = LastApprovedBlock.eitherTLastApprovedBlock[CommError, Task](Monad[Task], lab)
      commTmpFolder        = conf.server.dataDir.resolve("tmp").resolve("comm")
      _                    <- commTmpFolder.deleteDirectory[Task]().toEffect
      transport = effects.tcpTransportLayer(
        port,
        conf.tls.certificate,
        conf.tls.key,
        conf.server.maxMessageSize,
        conf.server.chunkSize,
        commTmpFolder
      )(grpcScheduler, log, metrics, tcpConnections)
      // TODO: This change is temporary until itegulov's BlockStore implementation is in
      blockMap <- Ref.of[Effect, Map[BlockHash, BlockMessage]](Map.empty[BlockHash, BlockMessage])
      blockStore = InMemBlockStore.create[Effect](
        syncEffect,
        blockMap,
        Metrics.eitherT(Monad[Task], metrics)
      )
      blockDagStorage <- InMemBlockDagStorage.create[Effect](
                          Concurrent[Effect],
                          Log.eitherTLog(Monad[Task], log),
                          blockStore
                        )
      _      <- blockStore.clear() // TODO: Replace with a proper casper init when it's available
      oracle = SafetyOracle.cliqueOracle[Effect](Monad[Effect], Log.eitherTLog(Monad[Task], log))
      casperPacketHandler <- CasperPacketHandler
                              .of[Effect](
                                conf.casper,
                                defaultTimeout,
                                executionEngineService,
                                _.value
                              )(
                                labEff,
                                Metrics.eitherT(Monad[Task], metrics),
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
      nodeCoreMetrics = diagnostics.effects.nodeCoreMetrics[Task]
      jvmMetrics      = diagnostics.effects.jvmMetrics[Task]

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
        multiParentCasperRef,
        nodeCoreMetrics,
        jvmMetrics
      )
      _ <- handleUnrecoverableErrors(program)
    } yield ()

  private def acquireServers(blockApiLock: Semaphore[Effect], ee: ExecutionEngineService[Effect])(
      implicit
      nodeDiscovery: NodeDiscovery[Task],
      blockStore: BlockStore[Effect],
      oracle: SafetyOracle[Effect],
      multiParentCasperRef: MultiParentCasperRef[Effect],
      metrics: Metrics[Task],
      nodeCoreMetrics: NodeMetrics[Task],
      jvmMetrics: JvmMetrics[Task],
      connectionsCell: ConnectionsCell[Task],
      concurrent: Concurrent[Effect]
  ): Effect[Servers] = {
    implicit val s: Scheduler = scheduler
    implicit val e            = ee
    for {
      grpcServerExternal <- GrpcServer
                             .acquireExternalServer[Effect](
                               conf.grpc.portExternal,
                               conf.server.maxMessageSize,
                               grpcScheduler,
                               blockApiLock
                             )

      grpcServerInternal <- GrpcServer
                             .acquireInternalServer(
                               conf.grpc.portInternal,
                               conf.server.maxMessageSize,
                               grpcScheduler
                             )
                             .toEffect

      prometheusReporter = new NewPrometheusReporter()
      prometheusService  = NewPrometheusReporter.service[Task](prometheusReporter)

      httpServerFiber <- BlazeBuilder[Task]
                          .bindHttp(conf.server.httpPort, "0.0.0.0")
                          .mountService(prometheusService, "/metrics")
                          .mountService(VersionInfo.service, "/version")
                          .mountService(StatusInfo.service, "/status")
                          .resource
                          .use(_ => Task.never[Unit])
                          .start
                          .toEffect

      metrics = new MetricsRuntime[Effect](conf, id)
      _       <- metrics.setupMetrics(prometheusReporter)

    } yield Servers(grpcServerExternal, grpcServerInternal, httpServerFiber)
  }

  private def clearResources[F[_]: Monad](
      servers: Servers
  )(
      implicit
      transport: TransportLayer[Task],
      blockStore: BlockStore[Effect],
      peerNodeAsk: PeerNodeAsk[Task]
  ): Unit =
    (for {
      _   <- log.info("Shutting down gRPC servers...")
      _   <- servers.grpcServerExternal.stop
      _   <- servers.grpcServerInternal.stop
      _   <- log.info("Shutting down transport layer, broadcasting DISCONNECT")
      loc <- peerNodeAsk.ask
      msg = ProtocolHelper.disconnect(loc)
      _   <- transport.shutdown(msg)
      _   <- log.info("Shutting down HTTP server....")
      _   <- Task.delay(Kamon.stopAllReporters())
      _   <- servers.httpServer.cancel
      _   <- log.info("Bringing BlockStore down ...")
      _   <- blockStore.close().value
      _   <- log.info("Goodbye.")
    } yield ()).unsafeRunSync(scheduler)

  private def addShutdownHook[F[_]: Monad](
      servers: Servers
  )(
      implicit transport: TransportLayer[Task],
      blockStore: BlockStore[Effect],
      peerNodeAsk: PeerNodeAsk[Task]
  ): Task[Unit] =
    Task.delay(
      sys.addShutdownHook(clearResources(servers))
    )

  private def exit0: Task[Unit] = Task.delay(System.exit(0))

  private def nodeProgram(
      executionEngineService: ExecutionEngineService[Effect]
  )(
      implicit
      time: Time[Task],
      rpConfState: RPConfState[Task],
      rpConfAsk: RPConfAsk[Task],
      peerNodeAsk: PeerNodeAsk[Task],
      metrics: Metrics[Task],
      transport: TransportLayer[Task],
      nodeDiscovery: NodeDiscovery[Task],
      rpConnectons: ConnectionsCell[Task],
      blockStore: BlockStore[Effect],
      oracle: SafetyOracle[Effect],
      packetHandler: PacketHandler[Effect],
      casperConstructor: MultiParentCasperRef[Effect],
      nodeCoreMetrics: NodeMetrics[Task],
      jvmMetrics: JvmMetrics[Task]
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
      blockApiLock <- Semaphore[Effect](1)
      _            <- info
      local        <- peerNodeAsk.ask.toEffect
      host         = local.endpoint.host
      servers      <- acquireServers(blockApiLock, executionEngineService)
      _            <- addShutdownHook(servers).toEffect
      _            <- servers.grpcServerExternal.start.toEffect
      _ <- Log[Effect].info(
            s"gRPC external server started at $host:${servers.grpcServerExternal.port}"
          )
      _ <- servers.grpcServerInternal.start.toEffect
      _ <- Log[Effect].info(
            s"gRPC internal server started at $host:${servers.grpcServerInternal.port}"
          )

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
        } *> exit0.as(Right(()))
    )

  private def syncEffect = cats.effect.Sync.catsEitherTSync[Task, CommError]

  private val rpClearConnConf = ClearConnetionsConf(
    conf.server.maxNumOfConnections,
    numOfConnectionsPinged = 10
  ) // TODO read from conf

  private def rpConf[F[_]: Sync](local: PeerNode) =
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
