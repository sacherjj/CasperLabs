package io.casperlabs.smartcontracts

import java.nio.file.Path
import java.util.concurrent.TimeUnit

import cats.{Applicative, Monad}
import cats.syntax.applicative._
import cats.syntax.either._
import cats.syntax.functor._
import io.casperlabs.catscontrib.ToAbstractContext
import io.casperlabs.ipc._
import io.casperlabs.models.SmartContractEngineError
import io.grpc.ManagedChannel
import io.grpc.netty.NettyChannelBuilder
import io.netty.channel.epoll.{Epoll, EpollDomainSocketChannel, EpollEventLoopGroup}
import io.netty.channel.kqueue.{KQueueDomainSocketChannel, KQueueEventLoopGroup}
import io.netty.channel.unix.DomainSocketAddress
import simulacrum.typeclass

import com.google.protobuf.ByteString
import monix.eval.Task

import scala.util.Either

@typeclass trait ExecutionEngineService[F[_]] {
  //TODO: should this be effectful?
  def emptyStateHash: ByteString
  def exec(
      prestate: ByteString,
      deploys: Seq[Deploy]
  ): F[Either[Throwable, Seq[DeployResult]]]
  def commit(prestate: ByteString, effects: Seq[TransformEntry]): F[Either[Throwable, ByteString]]
  def close(): F[Unit]
}

class GrpcExecutionEngineService[F[_]: Monad: ToAbstractContext](addr: Path, maxMessageSize: Int)
    extends ExecutionEngineService[F] {

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

  override def emptyStateHash: ByteString = ByteString.copyFrom(Array.fill(32)(0.toByte))

  override def close(): F[Unit] =
    ToAbstractContext[F].fromTask(Task.delay {
      val terminated = channel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
      if (!terminated) {
        println(
          "warn: did not shutdown after 10 seconds, retrying with additional 10 seconds timeout"
        )
        channel.awaitTermination(10, TimeUnit.SECONDS)
      }
    })

  override def exec(
      prestate: ByteString,
      deploys: Seq[Deploy]
  ): F[Either[Throwable, Seq[DeployResult]]] =
    ToAbstractContext[F].fromTask(stub.exec(ExecRequest(Seq(prestate), deploys)).attempt).map {
      case Left(err) => Left(err)
      case Right(ExecResponse(result)) =>
        result match {
          case ExecResponse.Result.Success(ExecResult(_, deployResults)) =>
            Right(deployResults)
          //TODO: Capture errors better than just as a string
          case ExecResponse.Result.Empty => Left(new SmartContractEngineError("empty response"))
          case ExecResponse.Result.MissingParents(MultipleRootsNotFound(missing)) =>
            Left(new SmartContractEngineError(s"Missing states: ${missing.mkString(",")}"))
        }
    }

  override def commit(
      prestate: ByteString,
      effects: Seq[TransformEntry]
  ): F[Either[Throwable, ByteString]] =
    ToAbstractContext[F]
      .fromTask(stub.commit(CommitRequest(prestate, effects)).attempt)
      .map {
        case Left(err) => Left(err)
        case Right(CommitResponse(result)) =>
          result match {
            case CommitResponse.Result.Success(CommitResult(poststateHash)) => Right(poststateHash)
            case CommitResponse.Result.Empty                                => Left(new SmartContractEngineError("empty response"))
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
      override def close(): F[Unit]       = ().pure
    }

  def simpleApi[F[_]: Applicative](): ExecutionEngineService[F] =
    new ExecutionEngineService[F] {
      private val zero          = Array.fill(32)(0.toByte)
      private val key           = Key(Key.KeyInstance.Hash(KeyHash(ByteString.copyFrom(zero))))
      private val transform     = Transform(Transform.TransformInstance.Identity(TransformIdentity()))
      private val op            = Op(Op.OpInstance.Read(ReadOp()))
      private val transforEntry = TransformEntry(Some(key), Some(transform))
      private val opEntry       = OpEntry(Some(key), Some(op))
      private val ee            = ExecutionEffect(Seq(opEntry), Seq(transforEntry), 0)

      override def emptyStateHash: ByteString = ByteString.copyFrom(zero)

      override def exec(
          prestate: ByteString,
          deploys: Seq[Deploy]
      ): F[Either[Throwable, Seq[DeployResult]]] =
        deploys.map(_ => DeployResult(DeployResult.Result.Effects(ee))).asRight[Throwable].pure

      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry]
      ): F[Either[Throwable, ByteString]] = {
        val arr = if (prestate.isEmpty) zero.clone() else prestate.toByteArray
        val maybeIndex = arr.zipWithIndex
          .find { case (b, _) => b < Byte.MaxValue }
          .map { case (_, i) => i }
        val newArr = maybeIndex
          .fold(zero)(i => { arr.update(i, (arr(i) + 1).toByte); arr })

        ByteString.copyFrom(newArr).asRight[Throwable].pure[F]
      }

      override def close(): F[Unit] = ().pure
    }
}
