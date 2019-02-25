package io.casperlabs.smartcontracts

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import cats.effect.{Resource, Sync}
import cats.syntax.applicative._
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.apply._
import io.casperlabs.ipc.IpcGrpcMonix
import io.casperlabs.shared.Log
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.epoll.{Epoll, EpollDomainSocketChannel, EpollEventLoopGroup}
import io.netty.channel.kqueue.{KQueueDomainSocketChannel, KQueueEventLoopGroup}
import io.netty.channel.unix.DomainSocketAddress
import monix.eval.TaskLift

class ExecutionEngineConf[F[_]: Sync: Log: TaskLift](addr: Path, maxMessageSize: Int) {
  val channelType =
    if (Epoll.isAvailable) classOf[EpollDomainSocketChannel] else classOf[KQueueDomainSocketChannel]
  val eventLoopGroup =
    if (Epoll.isAvailable) new EpollEventLoopGroup() else new KQueueEventLoopGroup()

  val channelF = Sync[F].delay(
    NettyChannelBuilder
      .forAddress(new DomainSocketAddress(addr.toFile))
      .channelType(channelType)
      .maxInboundMessageSize(maxMessageSize)
      .eventLoopGroup(eventLoopGroup)
      .usePlaintext()
      .build()
  )

  private def stop(channel: ManagedChannel): F[Unit] = {
    def await(channel: ManagedChannel): F[Boolean] =
      Sync[F].delay(channel.awaitTermination(10, TimeUnit.SECONDS))

    val retry = for {
      _ <- Log[F].warn(
            "Execution engine service did not shutdown after 10 seconds, retrying with additional 10 seconds timeout"
          )
      _ <- await(channel)
    } yield ()

    val terminated = Sync[F].delay(channel.shutdown()) >>= await

    Log[F].info("Shutting down execution engine service...") *> terminated >>= retry.unlessA
  }

  def apply: Resource[F, GrpcExecutionEngineService[F]] = {
    val res = for {
      channel <- channelF
      stub    <- Sync[F].delay(IpcGrpcMonix.stub(channel))
    } yield
      Resource.make(
        Sync[F].delay(new GrpcExecutionEngineService[F](addr, maxMessageSize, stub))
      )(_ => stop(channel))

    Resource.suspend(res)
  }
}
