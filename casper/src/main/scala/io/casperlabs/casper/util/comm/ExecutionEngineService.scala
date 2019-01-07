package io.casperlabs.casper.util.comm

import java.io.Closeable
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import io.casperlabs.ipc._
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.epoll.{Epoll, EpollDomainSocketChannel, EpollEventLoopGroup}
import io.netty.channel.kqueue.{KQueueDomainSocketChannel, KQueueEventLoopGroup}
import io.netty.channel.unix.DomainSocketAddress
import monix.eval.Task
import simulacrum.typeclass

import scala.util.Either

@typeclass trait ExecutionEngineService[F[_]] {
  def sendDeploy(deploy: Deploy): F[Either[Throwable, ExecutionEffect]]
  def executeEffects(c: CommutativeEffects): F[Either[Throwable, Done]]
}

class GrpcExecutionEngineService(addr: Path, maxMessageSize: Int)
    extends ExecutionEngineService[Task]
    with Closeable {

  private val channelType =
    if (Epoll.isAvailable) classOf[EpollDomainSocketChannel] else classOf[KQueueDomainSocketChannel]

  private val eventLoopGroup =
    if (Epoll.isAvailable) new EpollEventLoopGroup() else new KQueueEventLoopGroup()

  private val channel: ManagedChannel =
    NettyChannelBuilder
      .forAddress(new DomainSocketAddress(addr.toFile))
      .channelType(channelType)
      .maxInboundMessageSize(maxMessageSize)
      .eventLoopGroup(eventLoopGroup)
      .usePlaintext()
      .build()

  private val stub: IpcGrpcMonix.ExecutionEngineServiceStub = IpcGrpcMonix.stub(channel)

  override def close(): Unit = {
    val terminated = channel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
    if (!terminated) {
      println(
        "warn: did not shutdown after 10 seconds, retrying with additional 10 seconds timeout"
      )
      channel.awaitTermination(10, TimeUnit.SECONDS)
    }
  }
  override def sendDeploy(deploy: Deploy): Task[Either[Throwable, ExecutionEffect]] =
    stub.sendDeploy(deploy).map { response =>
      response.result match {
        case DeployResult.Result.Empty           => Left(new RuntimeException("empty response"))
        case DeployResult.Result.Effects(effect) => Right(effect)
        case DeployResult.Result.Error(error)    => Left(new RuntimeException(error.toProtoString))
      }
    }

  override def executeEffects(c: CommutativeEffects): Task[Either[Throwable, Done]] =
    stub
      .executeEffects(c)
      .map(
        response =>
          response.result match {
            case PostEffectsResult.Result.Empty      => Left(new RuntimeException("empty response"))
            case PostEffectsResult.Result.Success(v) => Right(v)
            case PostEffectsResult.Result.Error(effectsError) =>
              Left(new RuntimeException(effectsError.toProtoString))
          }
      )
}
