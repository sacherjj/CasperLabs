package io.casperlabs.comm

import cats.implicits._
import cats.effect._
import io.grpc.{Server, ServerServiceDefinition}
import io.grpc.netty.NettyServerBuilder
import monix.execution.Scheduler

object GrpcServer {
  type ServiceBinder[F[_]] = Scheduler => F[ServerServiceDefinition]

  /** Start a gRPC server resource with multiple services listening on a common port. */
  def apply[F[_]: Sync](port: Int, services: List[ServiceBinder[F]])(
      implicit scheduler: Scheduler
  ): Resource[F, Server] =
    Resource.make(
      services.traverse(_(scheduler)).map { boundService =>
        val builder = NettyServerBuilder
          .forPort(port)
          .executor(scheduler)
        boundService.foreach(builder.addService(_))
        builder.build.start
      }
    )(
      server =>
        Sync[F].delay {
          server.shutdown().awaitTermination()
        }
    )
}
