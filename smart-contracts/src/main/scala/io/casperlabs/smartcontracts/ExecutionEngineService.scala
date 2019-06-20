package io.casperlabs.smartcontracts

import java.nio.file.Path

import cats.effect.{Resource, Sync}
import cats.implicits._
import cats.{Applicative, Defer}
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.consensus.state.{Unit => SUnit, _}
import io.casperlabs.ipc._
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService.Stub
import monix.eval.{Task, TaskLift}
import simulacrum.typeclass
import scala.util.{Either, Try}
import io.casperlabs.catscontrib.MonadThrowable

@typeclass trait ExecutionEngineService[F[_]] {
  def emptyStateHash: ByteString
  def exec(
      prestate: ByteString,
      deploys: Seq[Deploy],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, Seq[DeployResult]]]
  def runGenesis(
      deploys: Seq[Deploy],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, GenesisResult]]
  def commit(prestate: ByteString, effects: Seq[TransformEntry]): F[Either[Throwable, ByteString]]
  def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]]
  def setBonds(bonds: Map[PublicKey, Long]): F[Unit]
  def query(state: ByteString, baseKey: Key, path: Seq[String]): F[Either[Throwable, Value]]
  def verifyWasm(contracts: ValidateRequest): F[Either[String, Unit]]
}

class GrpcExecutionEngineService[F[_]: Defer: Sync: Log: TaskLift: Metrics] private[smartcontracts] (
    addr: Path,
    maxMessageSize: Int,
    initialBonds: Map[Array[Byte], Long],
    stub: Stub
) extends ExecutionEngineService[F] {
  import GrpcExecutionEngineService.EngineMetricsSource

  private var bonds = initialBonds.map(p => Bond(ByteString.copyFrom(p._1), p._2)).toSeq

  override def emptyStateHash: ByteString = {
    val arr: Array[Byte] = Array(
      202, 169, 195, 180, 73, 241, 1, 207, 158, 155, 105, 130, 222, 149, 113, 83, 244, 33, 11, 132,
      57, 102, 129, 52, 188, 253, 43, 243, 67, 176, 41, 151
    ).map(_.toByte)
    ByteString.copyFrom(arr)
  }

  def sendMessage[A, B, R](msg: A, rpc: Stub => A => Task[B])(f: B => R): F[R] =
    rpc(stub)(msg).to[F].map(f(_)).recoverWith {
      case ex: io.grpc.StatusRuntimeException
          if ex.getStatus.getCode == io.grpc.Status.Code.UNAVAILABLE &&
            ex.getCause != null &&
            ex.getCause.isInstanceOf[java.io.FileNotFoundException] =>
        Sync[F].raiseError(
          new java.io.FileNotFoundException(
            s"It looks like the Execution Engine is not listening at the socket file ${addr}"
          )
        )
    }

  override def exec(
      prestate: ByteString,
      deploys: Seq[Deploy],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, Seq[DeployResult]]] =
    for {
      result <- sendMessage(ExecRequest(prestate, deploys, Some(protocolVersion)), _.exec) {
                 _.result match {
                   case ExecResponse.Result.Success(ExecResult(deployResults)) =>
                     Right(deployResults)
                   //TODO: Capture errors better than just as a string
                   case ExecResponse.Result.Empty =>
                     Left(new SmartContractEngineError("empty response"))
                   case ExecResponse.Result.MissingParent(RootNotFound(missing)) =>
                     Left(
                       new SmartContractEngineError(
                         s"Missing states: ${Base16.encode(missing.toByteArray)}"
                       )
                     )
                 }
               }
      _ <- result.fold(
            _ => ().pure[F],
            deployResults => {
              val gasSpent =
                deployResults.foldLeft(0L)((a, d) => a + d.value.executionResult.fold(0L)(_.cost))
              Metrics[F].incrementCounter(
                "gas_spent",
                gasSpent
              )
            }
          )
    } yield result

  override def runGenesis(
      deploys: Seq[Deploy],
      protocolVersion: ProtocolVersion
  ): F[Either[Throwable, GenesisResult]] =
    deploys.length match {
      case 0 =>
        GenesisResult()
          .withPoststateHash(emptyStateHash)
          .withEffect(ExecutionEffect())
          .asRight[Throwable]
          .pure[F]
      case 1 =>
        val code = deploys.head.getSession.code
        for {
          request <- MonadThrowable[F].fromTry(Try(GenesisRequest.parseFrom(code.toByteArray)))
          response <- sendMessage(request, _.runGenesis) {
                       _.result match {
                         case GenesisResponse.Result.Success(result) =>
                           Right(result)
                         case GenesisResponse.Result.FailedDeploy(error) =>
                           Left(new SmartContractEngineError(error.message))
                         case GenesisResponse.Result.Empty =>
                           Left(new SmartContractEngineError("empty response"))
                       }
                     }
        } yield response
      case _ =>
        MonadThrowable[F].raiseError(
          new IllegalArgumentException("Currently only 1 deploy is supported.")
        )
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
          Left(SmartContractEngineError("empty response"))
        case CommitResponse.Result.MissingPrestate(RootNotFound(hash)) =>
          Left(SmartContractEngineError(s"Missing pre-state: ${Base16.encode(hash.toByteArray)}"))
        case CommitResponse.Result.FailedTransform(PostEffectsError(message)) =>
          Left(SmartContractEngineError(s"Error executing transform: $message"))
        case CommitResponse.Result.KeyNotFound(value) =>
          Left(SmartContractEngineError(s"Key not found in global state: $value"))
        case CommitResponse.Result.TypeMismatch(err) =>
          Left(SmartContractEngineError(err.toString))
      }
    }

  override def query(
      state: ByteString,
      baseKey: Key,
      path: Seq[String]
  ): F[Either[Throwable, Value]] =
    sendMessage(QueryRequest(state, Some(baseKey), path), _.query) {
      _.result match {
        case QueryResponse.Result.Success(value) => Right(value)
        case QueryResponse.Result.Empty          => Left(SmartContractEngineError("empty response"))
        case QueryResponse.Result.Failure(err)   => Left(SmartContractEngineError(err))
      }
    }

  override def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]] =
    // FIXME: Implement bonds!
    bonds.pure[F]

  // Todo Pass in the genesis bonds until we have a solution based on the BlockStore.
  override def setBonds(newBonds: Map[PublicKey, Long]): F[Unit] =
    Defer[F].defer(Applicative[F].pure {
      bonds = newBonds.map {
        case (validator, weight) => Bond(ByteString.copyFrom(validator), weight)
      }.toSeq
    })
  override def verifyWasm(contracts: ValidateRequest): F[Either[String, Unit]] =
    stub.validate(contracts).to[F] >>= (
      _.result match {
        case ValidateResponse.Result.Empty =>
          Sync[F].raiseError(
            new IllegalStateException("Execution Engine service has sent a corrupted reply")
          )
        case ValidateResponse.Result.Success(_) =>
          ().asRight[String].pure[F]
        case ValidateResponse.Result.Failure(cause: String) =>
          cause.asLeft[Unit].pure[F]
      }
    )
}

object ExecutionEngineService {
  type Stub = IpcGrpcMonix.ExecutionEngineServiceStub

}

object GrpcExecutionEngineService {
  private implicit val EngineMetricsSource: Metrics.Source =
    Metrics.Source(Metrics.BaseSource, "engine")

  def apply[F[_]: Sync: Log: TaskLift: Metrics](
      addr: Path,
      maxMessageSize: Int,
      initBonds: Map[Array[Byte], Long]
  ): Resource[F, GrpcExecutionEngineService[F]] =
    new ExecutionEngineConf[F](addr, maxMessageSize, initBonds).apply
}
