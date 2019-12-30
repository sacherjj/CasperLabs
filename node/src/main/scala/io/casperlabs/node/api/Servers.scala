package io.casperlabs.node.api

import cats.Id
import cats.effect.{Effect => _, _}
import cats.effect.concurrent.Semaphore
import cats.implicits._
import io.casperlabs.casper.MultiParentCasperImpl.Broadcaster
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.comm.discovery.{NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.grpc.{ErrorInterceptor, GrpcServer, MetricsInterceptor}
import io.casperlabs.comm.rp.Connect.ConnectionsCell
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.node._
import io.casperlabs.node.api.casper.CasperGrpcMonix
import io.casperlabs.node.api.control.ControlGrpcMonix
import io.casperlabs.node.api.diagnostics.DiagnosticsGrpcMonix
import io.casperlabs.node.api.graphql.{FinalizedBlocksStream, GraphQL}
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.node.diagnostics.{GrpcDiagnosticsService, NewPrometheusReporter}
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.deploy.DeployStorage
import io.netty.handler.ssl.SslContext
import kamon.Kamon
import monix.eval.{Task, TaskLike}
import monix.execution.Scheduler
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder

import scala.concurrent.ExecutionContext

object Servers {

  private def logStarted[F[_]: Log](name: String, port: Int, isSsl: Boolean) =
    Log[F].info(s"$name gRPC services started on $port with $isSsl")

  /** Start a gRPC server with services meant for the operators.
    * This port shouldn't be exposed to the internet, or some endpoints
    * should be protected by authentication and an SSL channel. */
  def internalServersR(
      port: Int,
      maxMessageSize: Int,
      ingressScheduler: Scheduler,
      blockApiLock: Semaphore[Task],
      maybeSslContext: Option[SslContext]
  )(
      implicit
      log: Log[Task],
      logId: Log[Id],
      metrics: Metrics[Task],
      metricsId: Metrics[Id],
      multiParentCasperRef: MultiParentCasperRef[Task],
      broadcaster: Broadcaster[Task],
      eventsStream: EventStream[Task]
  ): Resource[Task, Unit] = {
    implicit val s = ingressScheduler
    GrpcServer[Task](
      port = port,
      maxMessageSize = Some(maxMessageSize),
      services = List(
        (_: Scheduler) =>
          GrpcControlService(blockApiLock) map {
            ControlGrpcMonix.bindService(_, ingressScheduler)
          }
      ),
      interceptors = List(
        new MetricsInterceptor(),
        ErrorInterceptor.default
      ),
      sslContext = maybeSslContext
    ) *> Resource.liftF(
      logStarted[Task]("Internal", port, maybeSslContext.isDefined)
    )
  }

  /** Start a gRPC server with services meant for users and dApp developers. */
  def externalServersR[F[_]: Concurrent: TaskLike: Log: MultiParentCasperRef: Metrics: BlockStorage: ExecutionEngineService: DeployStorage: Validation: Fs2Compiler: DeployBuffer: DagStorage: EventStream: NodeDiscovery](
      port: Int,
      maxMessageSize: Int,
      ingressScheduler: Scheduler,
      maybeSslContext: Option[SslContext],
      isReadOnlyNode: Boolean
  )(implicit logId: Log[Id], metricsId: Metrics[Id]): Resource[F, Unit] = {
    implicit val s = ingressScheduler
    GrpcServer(
      port = port,
      maxMessageSize = Some(maxMessageSize),
      services = List(
        (_: Scheduler) =>
          GrpcDiagnosticsService() map {
            DiagnosticsGrpcMonix.bindService(_, ingressScheduler)
          },
        (_: Scheduler) =>
          GrpcCasperService(isReadOnlyNode) map {
            CasperGrpcMonix.bindService(_, ingressScheduler)
          }
      ),
      interceptors = List(
        new MetricsInterceptor(),
        ErrorInterceptor.default
      ),
      sslContext = maybeSslContext
    ) *>
      Resource.liftF(
        logStarted[F]("External", port, maybeSslContext.isDefined)
      )
  }

  def httpServerR[F[_]: Log: NodeDiscovery: ConnectionsCell: Timer: ConcurrentEffect: MultiParentCasperRef: BlockStorage: ContextShift: FinalizedBlocksStream: ExecutionEngineService: DeployStorage: DagStorage: Fs2Compiler: Metrics](
      port: Int,
      conf: Configuration,
      id: NodeIdentifier,
      ec: ExecutionContext
  ): Resource[F, Unit] = {

    val prometheusReporter = new NewPrometheusReporter()
    val prometheusService  = NewPrometheusReporter.service[F](prometheusReporter)
    val metricsRuntime     = new MetricsRuntime[F](conf, id)

    for {
      _ <- Resource.make {
            metricsRuntime.setupMetrics(prometheusReporter)
          } { _ =>
            Sync[F].delay(Kamon.stopAllReporters())
          }
      _ <- BlazeServerBuilder[F]
            .bindHttp(port, "0.0.0.0")
            .withNio2(true)
            .withHttpApp(
              Router(
                "/metrics" -> prometheusService,
                "/version" -> VersionInfo.service,
                "/status"  -> StatusInfo.service,
                "/graphql" -> GraphQL.service[F](ec)
              ).orNotFound
            )
            .resource
      _ <- Resource.liftF(
            Log[F].info(s"HTTP server started on port ${port}.")
          )
    } yield ()
  }
}
