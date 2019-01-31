package io.casperlabs.node

import cats._
import cats.data._
import cats.effect._
import cats.effect.concurrent.Ref
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.functor._
import io.casperlabs.blockstorage.BlockStore.BlockHash
import io.casperlabs.blockstorage.{BlockStore, InMemBlockStore}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.protocol.BlockMessage
import io.casperlabs.casper.util.comm.CasperPacketHandler
import io.casperlabs.casper.util.rholang.RuntimeManager
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm._
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
import kamon.system.SystemMetrics
import kamon.zipkin.ZipkinReporter
import monix.eval.Task
import monix.execution.Scheduler
import com.typesafe.config.ConfigFactory
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

  // TODO: Resolve scheduler chaos in Runtime, RuntimeManager and CasperPacketHandler
  val main: Effect[Unit] = for {
    local <- WhoAmI
              .fetchLocalPeerNode[Task](
                conf.server.host,
                conf.server.port,
                conf.server.kademliaPort,
                conf.server.noUpnp,
                id
              )
              .toEffect

    defaultTimeout = conf.server.defaultTimeout.millis

    initPeer             = if (conf.server.standalone) None else Some(conf.server.bootstrap)
    rpConfState          = effects.rpConfState(rpConf(local, initPeer))
    rpConfAsk            = effects.rpConfAsk(rpConfState)
    peerNodeAsk          = effects.peerNodeAsk(rpConfState)
    rpConnections        <- effects.rpConnections.toEffect
    metrics              = diagnostics.effects.metrics[Task]
    kademliaConnections  <- CachedConnections[Task, KademliaConnTag](Task.catsAsync, metrics).toEffect
    tcpConnections       <- CachedConnections[Task, TcpConnTag](Task.catsAsync, metrics).toEffect
    time                 = effects.time
    multiParentCasperRef <- MultiParentCasperRef.of[Effect]
    lab                  <- LastApprovedBlock.of[Task].toEffect
    labEff               = LastApprovedBlock.eitherTLastApprovedBlock[CommError, Task](Monad[Task], lab)
    commTmpFolder        = conf.server.dataDir.resolve("tmp").resolve("comm")
    _                    <- commTmpFolder.delete[Task]().toEffect
    transport = effects.tcpTransportLayer(
      port,
      conf.tls.certificate,
      conf.tls.key,
      conf.server.maxMessageSize,
      conf.server.chunkSize,
      commTmpFolder
    )(grpcScheduler, log, metrics, tcpConnections)
    kademliaRPC = effects.kademliaRPC(kademliaPort, defaultTimeout)(
      grpcScheduler,
      peerNodeAsk,
      metrics,
      log,
      kademliaConnections
    )
    nodeDiscovery <- effects
                      .nodeDiscovery(id, defaultTimeout)(initPeer)(
                        log,
                        time,
                        metrics,
                        kademliaRPC
                      )
                      .toEffect
    // TODO: This change is temporary until itegulov's BlockStore implementation is in
    blockMap <- Ref.of[Effect, Map[BlockHash, BlockMessage]](Map.empty[BlockHash, BlockMessage])
    blockStore = InMemBlockStore.create[Effect](
      syncEffect,
      blockMap,
      Metrics.eitherT(Monad[Task], metrics)
    )
    _      <- blockStore.clear() // TODO: Replace with a proper casper init when it's available
    oracle = SafetyOracle.turanOracle[Effect](Monad[Effect])
    executionEngineService = new GrpcExecutionEngineService(
      conf.grpcServer.socket,
      conf.server.maxMessageSize
    )
    runtimeManager = RuntimeManager.fromExecutionEngineService(executionEngineService)
    abs = new ToAbstractContext[Effect] {
      def fromTask[A](fa: Task[A]): Effect[A] = fa.toEffect
    }
    casperPacketHandler <- CasperPacketHandler
                            .of[Effect](conf.casper, defaultTimeout, runtimeManager, _.value)(
                              labEff,
                              Metrics.eitherT(Monad[Task], metrics),
                              blockStore,
                              Cell.eitherTCell(Monad[Task], rpConnections),
                              NodeDiscovery.eitherTNodeDiscovery(Monad[Task], nodeDiscovery),
                              TransportLayer.eitherTTransportLayer(Monad[Task], log, transport),
                              ErrorHandler[Effect],
                              eitherTrpConfAsk(rpConfAsk),
                              oracle,
                              Capture[Effect],
                              Sync[Effect],
                              Time.eitherTTime(Monad[Task], time),
                              Log.eitherTLog(Monad[Task], log),
                              multiParentCasperRef,
                              abs,
                              scheduler
                            )
    packetHandler = PacketHandler.pf[Effect](casperPacketHandler.handle)(
      Applicative[Effect],
      Log.eitherTLog(Monad[Task], log),
      ErrorHandler[Effect]
    )
    nodeCoreMetrics = diagnostics.effects.nodeCoreMetrics[Task]
    jvmMetrics      = diagnostics.effects.jvmMetrics[Task]

    program = nodeProgram[Task](executionEngineService)(
      Monad[Task],
      time,
      rpConfState,
      rpConfAsk,
      peerNodeAsk,
      metrics,
      transport,
      kademliaRPC,
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

  private def acquireServers()(
      implicit
      nodeDiscovery: NodeDiscovery[Task],
      blockStore: BlockStore[Effect],
      oracle: SafetyOracle[Effect],
      multiParentCasperRef: MultiParentCasperRef[Effect],
      nodeCoreMetrics: NodeMetrics[Task],
      jvmMetrics: JvmMetrics[Task],
      connectionsCell: ConnectionsCell[Task]
  ): Effect[Servers] = {
    implicit val s: Scheduler = scheduler
    for {
      grpcServerExternal <- GrpcServer
                             .acquireExternalServer[Effect](
                               conf.grpcServer.portExternal,
                               conf.server.maxMessageSize,
                               grpcScheduler
                             )

      grpcServerInternal <- GrpcServer
                             .acquireInternalServer(
                               conf.grpcServer.portInternal,
                               conf.server.maxMessageSize,
                               grpcScheduler
                             )
                             .toEffect

      prometheusReporter = new NewPrometheusReporter()
      prometheusService  = NewPrometheusReporter.service(prometheusReporter)

      httpServerFiber <- BlazeBuilder[Task]
                          .bindHttp(conf.server.httpPort, "0.0.0.0")
                          .mountService(prometheusService, "/metrics")
                          .mountService(VersionInfo.service, "/version")
                          .resource
                          .use(_ => Task.never[Unit])
                          .start
                          .toEffect

      _ <- setupMetrics(prometheusReporter)
    } yield Servers(grpcServerExternal, grpcServerInternal, httpServerFiber)
  }

  private def clearResources[F[_]: Monad](
      servers: Servers,
      executionEngineService: ExecutionEngineService[F]
  )(
      implicit
      transport: TransportLayer[Task],
      kademliaRPC: KademliaRPC[Task],
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
      _   <- kademliaRPC.shutdown()
      _   <- log.info("Shutting down HTTP server....")
      _   <- Task.delay(Kamon.stopAllReporters())
      _   <- servers.httpServer.cancel
      _   <- log.info("Shutting down executionEngine service...")
      _   <- Task.delay(executionEngineService.close())
      _   <- log.info("Bringing BlockStore down ...")
      _   <- blockStore.close().value
      _   <- log.info("Goodbye.")
    } yield ()).unsafeRunSync(scheduler)

  private def addShutdownHook[F[_]: Monad](
      servers: Servers,
      casperSmartContractsApi: ExecutionEngineService[F]
  )(
      implicit transport: TransportLayer[Task],
      kademliaRPC: KademliaRPC[Task],
      blockStore: BlockStore[Effect],
      peerNodeAsk: PeerNodeAsk[Task]
  ): Task[Unit] =
    Task.delay(
      sys.addShutdownHook(clearResources(servers, casperSmartContractsApi))
    )

  private def exit0: Task[Unit] = Task.delay(System.exit(0))

  private def nodeProgram[F[_]: Monad](
      executionEngineService: ExecutionEngineService[F]
  )(
      implicit
      time: Time[Task],
      rpConfState: RPConfState[Task],
      rpConfAsk: RPConfAsk[Task],
      peerNodeAsk: PeerNodeAsk[Task],
      metrics: Metrics[Task],
      transport: TransportLayer[Task],
      kademliaRPC: KademliaRPC[Task],
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
      if (conf.server.standalone) Log[Effect].info(s"Starting stand-alone node.")
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
      _       <- info
      local   <- peerNodeAsk.ask.toEffect
      host    = local.endpoint.host
      servers <- acquireServers()
      _       <- addShutdownHook(servers, executionEngineService).toEffect
      _       <- servers.grpcServerExternal.start.toEffect
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
      address = s"rnode://$id@$host?protocol=$port&discovery=$kademliaPort"
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

  private def rpConf(local: PeerNode, bootstrapNode: Option[PeerNode]) =
    RPConf(local, bootstrapNode, defaultTimeout, rpClearConnConf)

  private def setupMetrics(metricsReporter: MetricReporter): Effect[Unit] =
    Task.delay {
      Kamon.reconfigure(
        ConfigFactory
          .parseString(buildKamonConf)
          .withFallback(Kamon.config())
      )

      if (conf.kamon.influx.isDefined)
        Kamon.addReporter(new kamon.influxdb.InfluxDBReporter())
      if (conf.kamon.prometheus) Kamon.addReporter(metricsReporter)
      if (conf.kamon.zipkin) Kamon.addReporter(new ZipkinReporter())

      Kamon.addReporter(new JmxReporter())
      SystemMetrics.startCollecting()
    }.toEffect

  private def buildKamonConf: String = {
    val authentication = conf.kamon.influx
      .flatMap(
        _.authentication
          .map { auth =>
            s"""
           |    authentication {
           |      user = "${auth.user}"
           |      password = "${auth.password}"
           |    }
           |""".stripMargin
          }
      )
      .getOrElse("")

    val influxConf = conf.kamon.influx
      .map { influx =>
        s"""
         |  influxdb {
         |    hostname = "${influx.hostname}"
         |    port     =  ${influx.port}
         |    database = "${influx.database}"
         |    protocol = "${influx.protocol}"
         |    $authentication
         |  }
         |""".stripMargin
      }
      .getOrElse("")

    s"""
         |kamon {
         |  environment {
         |    service = "rnode"
         |    instance = "${id.toString}"
         |  }
         |  metric {
         |    tick-interval = 10 seconds
         |  }
         |  system-metrics {
         |    host {
         |      enabled = ${conf.kamon.sigar}
         |      sigar-native-folder = ${conf.server.dataDir.resolve("native")}
         |    }
         |  }
         |  $influxConf
         |}
         |""".stripMargin
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
