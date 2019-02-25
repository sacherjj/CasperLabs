package io.casperlabs.smartcontracts

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import cats.effect.{Resource, Sync}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._
import cats.syntax.flatMap._
import cats.syntax.apply._
import cats.{Applicative, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.shared.Log
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.epoll.{Epoll, EpollDomainSocketChannel, EpollEventLoopGroup}
import io.netty.channel.kqueue.{KQueueDomainSocketChannel, KQueueEventLoopGroup}
import io.netty.channel.unix.DomainSocketAddress
import monix.eval.{Task, TaskLift}
import simulacrum.typeclass

import scala.util.Either

@typeclass trait ExecutionEngineService[F[_]] {
  //TODO: should this be effectful?
  def emptyStateHash: ByteString
  def exec(
      prestate: ByteString,
      deploys: Seq[Deploy]
  ): F[Either[Throwable, Seq[DeployResult]]]
  def commit(prestate: ByteString, effects: Seq[TransformEntry]): F[Either[Throwable, ByteString]]
}

class GrpcExecutionEngineService[F[_]: Sync: Log: TaskLift] private (
    addr: Path,
    maxMessageSize: Int
) extends ExecutionEngineService[F] {
  type Stub = IpcGrpcMonix.ExecutionEngineServiceStub

  private val channelType =
    if (Epoll.isAvailable) classOf[EpollDomainSocketChannel] else classOf[KQueueDomainSocketChannel]

  private val eventLoopGroup =
    if (Epoll.isAvailable) new EpollEventLoopGroup() else new KQueueEventLoopGroup()

  private val channel: F[ManagedChannel] =
    Sync[F].delay(
      NettyChannelBuilder
        .forAddress(new DomainSocketAddress(addr.toFile))
        .channelType(channelType)
        .maxInboundMessageSize(maxMessageSize)
        .eventLoopGroup(eventLoopGroup)
        .usePlaintext()
        .build()
    )

  private val stubF: F[IpcGrpcMonix.ExecutionEngineServiceStub] = channel.map(IpcGrpcMonix.stub)

  override def emptyStateHash: ByteString = ByteString.copyFrom(Array.fill(32)(0.toByte))

  private def close(): F[Unit] = {
    def await(channel: ManagedChannel): F[Boolean] =
      Sync[F].delay(channel.awaitTermination(10, TimeUnit.SECONDS))

    val retry = for {
      _ <- Log[F].warn(
            "Execution engine service did not shutdown after 10 seconds, retrying with additional 10 seconds timeout"
          )
      c <- channel
      _ <- await(c)
    } yield ()

    val terminated = channel map (_.shutdown()) >>= await

    Log[F].info("Shutting down execution engine service...") *> terminated >>= retry.unlessA
  }

  def sendMessage[A, B, R](msg: A, rpc: Stub => A => Task[B])(f: B => R): F[R] =
    for {
      stub     <- stubF
      response <- rpc(stub)(msg).to[F]
    } yield f(response)

  override def exec(
      prestate: ByteString,
      deploys: Seq[Deploy]
  ): F[Either[Throwable, Seq[DeployResult]]] =
    sendMessage(ExecRequest(prestate, deploys), _.exec) {
      _.result match {
        case ExecResponse.Result.Success(ExecResult(deployResults)) =>
          Right(deployResults)
        //TODO: Capture errors better than just as a string
        case ExecResponse.Result.Empty =>
          Left(new SmartContractEngineError("empty response"))
        case ExecResponse.Result.MissingParent(RootNotFound(missing)) =>
          Left(
            new SmartContractEngineError(s"Missing states: ${Base16.encode(missing.toByteArray)}")
          )
      }
    }

  override def commit(
      prestate: ByteString,
      effects: Seq[TransformEntry]
  ): F[Either[Throwable, ByteString]] =
    sendMessage(CommitRequest(prestate, effects), _.commit) {
      _.result match {
        case CommitResponse.Result.Success(CommitResult(poststateHash)) =>
          Right(poststateHash)
        case CommitResponse.Result.Empty =>
          Left(new SmartContractEngineError("empty response"))
        case CommitResponse.Result.MissingPrestate(RootNotFound(hash)) =>
          Left(new SmartContractEngineError(s"Missing pre-state: $hash"))
        case CommitResponse.Result.FailedTransform(PostEffectsError(message)) =>
          Left(new SmartContractEngineError(s"Error executing transform: $message"))
      }
    }
}

object ExecutionEngineService {
  def noOpApi[F[_]: Applicative](): ExecutionEngineService[F] =
    new ExecutionEngineService[F] {
      override def emptyStateHash: ByteString = ByteString.EMPTY
      override def exec(
          prestate: ByteString,
          deploys: Seq[Deploy]
      ): F[Either[Throwable, Seq[DeployResult]]] =
        Seq.empty[DeployResult].asRight[Throwable].pure
      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry]
      ): F[Either[Throwable, ByteString]] = ByteString.EMPTY.asRight[Throwable].pure
    }
}

object GrpcExecutionEngineService {
  def apply[F[_]: Sync: Log: TaskLift](
      addr: Path,
      maxMessageSize: Int
  ): Resource[F, GrpcExecutionEngineService[F]] =
    Resource.make(
      Sync[F].delay(new GrpcExecutionEngineService[F](addr, maxMessageSize))
    )(_.close())
}
