package io.casperlabs.node.api

import java.util.concurrent.TimeUnit

import cats.effect.Sync
import cats.implicits._
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.protocol.CasperMessageGrpcMonix
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.comm.rp.Connect.ConnectionsCell
import io.casperlabs.node.diagnostics
import io.casperlabs.node.diagnostics.{JvmMetrics, NodeMetrics}
import io.casperlabs.node.model.diagnostics.DiagnosticsGrpcMonix
import io.casperlabs.shared._
import io.grpc.Server
import io.grpc.netty.NettyServerBuilder
import monix.eval.Task
import monix.execution.Scheduler

class GrpcServer(server: Server) {
  def start: Task[Unit] = Task.delay(server.start())

  private def attemptShutdown: Task[Boolean] =
    (for {
      _          <- Task.delay(server.shutdown())
      _          <- Task.delay(server.awaitTermination(1000, TimeUnit.MILLISECONDS))
      terminated <- Task.delay(server.isTerminated)
    } yield terminated).attempt map (_.fold(kp(false), id))

  private def shutdownImmediately: Task[Unit] =
    Task.delay(server.shutdownNow()).attempt.as(())

  def stop: Task[Unit] = attemptShutdown >>= { stopped =>
    if (stopped) Task.unit else shutdownImmediately
  }
  def port: Int = server.getPort
}

object GrpcServer {

  def apply(server: Server): GrpcServer = new GrpcServer(server)

  def acquireInternalServer(
      port: Int,
      maxMessageSize: Int,
      grpcExecutor: Scheduler
  )(
      implicit nodeDiscovery: NodeDiscovery[Task],
      jvmMetrics: JvmMetrics[Task],
      nodeMetrics: NodeMetrics[Task],
      connectionsCell: ConnectionsCell[Task]
  ): Task[GrpcServer] =
    Task.delay {
      GrpcServer(
        NettyServerBuilder
          .forPort(port)
          .executor(grpcExecutor)
          .maxMessageSize(maxMessageSize)
          .addService(DiagnosticsGrpcMonix.bindService(diagnostics.grpc, grpcExecutor))
          .build
      )
    }

  def acquireExternalServer[F[_]: Sync: Capture: MultiParentCasperRef: Log: SafetyOracle: BlockStore: Taskable](
      port: Int,
      maxMessageSize: Int,
      grpcExecutor: Scheduler
  )(implicit worker: Scheduler): F[GrpcServer] =
    Capture[F].capture {
      GrpcServer(
        NettyServerBuilder
          .forPort(port)
          .executor(grpcExecutor)
          .maxMessageSize(maxMessageSize)
          .addService(
            CasperMessageGrpcMonix.bindService(DeployGrpcService.instance, grpcExecutor)
          )
          .build
      )
    }
}
