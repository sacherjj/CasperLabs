package coop.rchain.node.api

import java.util.concurrent.TimeUnit

import cats.effect.Sync
import cats.implicits._
import coop.rchain.blockstorage.BlockStore
import coop.rchain.casper.MultiParentCasperRef.MultiParentCasperRef
import coop.rchain.casper.SafetyOracle
import coop.rchain.casper.protocol.CasperMessageGrpcMonix
import coop.rchain.catscontrib._
import coop.rchain.catscontrib.ski._
import coop.rchain.shared._
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
