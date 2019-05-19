package io.casperlabs.node.api

import java.util.concurrent.TimeUnit

import cats.Id
import cats.effect.{Concurrent, ConcurrentEffect, Resource}
import cats.effect.concurrent.Semaphore
import cats.implicits._
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.protocol.CasperMessageGrpcMonix
import io.casperlabs.node.api.casper.CasperGrpcMonix
import io.casperlabs.node.api.control.ControlGrpcMonix
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.discovery.{NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.rp.Connect.ConnectionsCell
import io.casperlabs.comm.grpc.{ErrorInterceptor, GrpcServer, MetricsInterceptor}
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.node.diagnostics.{JvmMetrics, NewPrometheusReporter, NodeMetrics}
import io.casperlabs.node.diagnostics.effects.diagnosticsService
import io.casperlabs.node.api.diagnostics.DiagnosticsGrpcMonix
import io.casperlabs.node._
import io.casperlabs.shared._
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import kamon.Kamon
import monix.eval.{Task, TaskLike}
import monix.execution.Scheduler
import io.casperlabs.smartcontracts.ExecutionEngineService
import org.http4s.server.blaze.BlazeBuilder

object Servers {

  /** Start a gRPC server with services meant for the operators.
    * This port shouldn't be exposed to the internet, or some endpoints
    * should be protected by authentication and an SSL channel. */
  def internalServersR(
      port: Int,
      maxMessageSize: Int,
      grpcExecutor: Scheduler,
      blockApiLock: Semaphore[Effect]
  )(
      implicit
      log: Log[Effect],
      logId: Log[Id],
      metrics: Metrics[Effect],
      metricsId: Metrics[Id],
      nodeDiscovery: NodeDiscovery[Task],
      jvmMetrics: JvmMetrics[Task],
      nodeMetrics: NodeMetrics[Task],
      connectionsCell: ConnectionsCell[Task],
      multiParentCasperRef: MultiParentCasperRef[Effect],
      scheduler: Scheduler
  ): Resource[Effect, Unit] =
    GrpcServer[Effect](
      port = port,
      maxMessageSize = Some(maxMessageSize),
      services = List(
        (_: Scheduler) =>
          Task.delay {
            DiagnosticsGrpcMonix.bindService(diagnosticsService, grpcExecutor)
          }.toEffect,
        (_: Scheduler) =>
          GrpcControlService(blockApiLock) map {
            ControlGrpcMonix.bindService(_, grpcExecutor)
          }
      ),
      interceptors = List(
        new MetricsInterceptor(),
        ErrorInterceptor.default
      )
    ) *> Resource.liftF(
      Log[Effect].info(s"Internal gRPC services started on port ${port}.")
    )

  /** Start a gRPC server with services meant for users and dApp developers. */
  def externalServersR[F[_]: Concurrent: TaskLike: Log: MultiParentCasperRef: Metrics: SafetyOracle: BlockStore: ExecutionEngineService](
      port: Int,
      maxMessageSize: Int,
      grpcExecutor: Scheduler,
      blockApiLock: Semaphore[F],
      ignoreDeploySignature: Boolean
  )(implicit scheduler: Scheduler, logId: Log[Id], metricsId: Metrics[Id]): Resource[F, Unit] =
    GrpcServer(
      port = port,
      maxMessageSize = Some(maxMessageSize),
      services = List(
        // TODO: Phase DeployService out in favor of CasperService.
        (_: Scheduler) =>
          GrpcDeployService.instance(blockApiLock, ignoreDeploySignature) map {
            CasperMessageGrpcMonix.bindService(_, grpcExecutor)
          },
        (_: Scheduler) =>
          GrpcCasperService(ignoreDeploySignature) map {
            CasperGrpcMonix.bindService(_, grpcExecutor)
          }
      ),
      interceptors = List(
        new MetricsInterceptor(),
        ErrorInterceptor.default
      )
    ) *>
      Resource.liftF(Log[F].info(s"External gRPC services started on port ${port}."))

  def httpServerR(
      port: Int,
      conf: Configuration,
      id: NodeIdentifier
  )(
      implicit
      log: Log[Task],
      nodeDiscovery: NodeDiscovery[Task],
      connectionsCell: ConnectionsCell[Task],
      scheduler: Scheduler
  ): Resource[Effect, Unit] = {
    val prometheusReporter = new NewPrometheusReporter()
    val prometheusService  = NewPrometheusReporter.service[Task](prometheusReporter)
    val metricsRuntime     = new MetricsRuntime[Task](conf, id)

    (for {
      _ <- Resource.make {
            metricsRuntime.setupMetrics(prometheusReporter)
          } { _ =>
            Task.delay(Kamon.stopAllReporters())
          }
      _ <- BlazeBuilder[Task]
            .bindHttp(port, "0.0.0.0")
            .mountService(prometheusService, "/metrics")
            .mountService(VersionInfo.service, "/version")
            .mountService(StatusInfo.service, "/status")
            .resource
      _ <- Resource.liftF(
            Log[Task].info(s"HTTP server started on port ${port}.")
          )
    } yield ()).toEffect
  }
}
