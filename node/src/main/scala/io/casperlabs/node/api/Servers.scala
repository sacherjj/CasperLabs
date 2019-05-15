package io.casperlabs.node.api

import java.util.concurrent.TimeUnit

import cats.effect.{Concurrent, ConcurrentEffect, Resource}
import cats.effect.concurrent.Semaphore
import cats.implicits._
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.protocol.CasperMessageGrpcMonix
import io.casperlabs.node.api.casper.CasperGrpcMonix
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.discovery.{NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.rp.Connect.ConnectionsCell
import io.casperlabs.comm.grpc.GrpcServer
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

  def diagnosticsServerR(
      port: Int,
      maxMessageSize: Int,
      grpcExecutor: Scheduler
  )(
      implicit
      log: Log[Effect],
      nodeDiscovery: NodeDiscovery[Task],
      jvmMetrics: JvmMetrics[Task],
      nodeMetrics: NodeMetrics[Task],
      connectionsCell: ConnectionsCell[Task],
      scheduler: Scheduler
  ): Resource[Effect, Unit] =
    GrpcServer(
      port = port,
      maxMessageSize = Some(maxMessageSize),
      services = List(
        (_: Scheduler) =>
          Task.delay {
            DiagnosticsGrpcMonix.bindService(diagnosticsService, grpcExecutor)
          }
      )
    ).void.toEffect <* Resource.liftF(
      Log[Effect].info(s"gRPC diagnostics service started on port ${port}.")
    )

  def deploymentServerR[F[_]: Concurrent: TaskLike: Log: MultiParentCasperRef: Metrics: SafetyOracle: BlockStore: ExecutionEngineService](
      port: Int,
      maxMessageSize: Int,
      grpcExecutor: Scheduler
  )(implicit scheduler: Scheduler): Resource[F, Unit] =
    for {
      blockApiLock <- Resource.liftF(Semaphore[F](1))
      _ <- GrpcServer(
            port = port,
            maxMessageSize = Some(maxMessageSize),
            services = List(
              // TODO: Phase DeployService out in favor of CasperService.
              (_: Scheduler) =>
                for {
                  inst <- DeployGrpcService.instance(blockApiLock)
                } yield {
                  CasperMessageGrpcMonix.bindService(inst, grpcExecutor)
                },
              (_: Scheduler) =>
                for {
                  inst <- GrpcCasperService(blockApiLock)
                } yield {
                  CasperGrpcMonix.bindService(inst, grpcExecutor)
                }
            )
          )
      _ <- Resource.liftF(Log[F].info(s"gRPC deployment service started on port ${port}."))
    } yield ()

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
