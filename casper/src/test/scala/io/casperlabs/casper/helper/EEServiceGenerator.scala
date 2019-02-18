package io.casperlabs.casper.helper
import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.ipc
import io.casperlabs.smartcontracts.ExecutionEngineService

object EEServiceGenerator {
  //TODO: Give a better implementation for use in testing; this one is too simplistic.
  def simpleEEApi[F[_]: Applicative](): ExecutionEngineService[F] =
    new ExecutionEngineService[F] {
      import ipc._
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
        //This function returns the same `DeployResult` for all deploys,
        //regardless of their wasm code. It pretends to have run all the deploys,
        //but it doesn't really; it just returns the same result no matter what.
        deploys.map(_ => DeployResult(DeployResult.Result.Effects(ee))).asRight[Throwable].pure[F]

      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry]
      ): F[Either[Throwable, ByteString]] = {
        //This function increments the prestate by interpreting as an integer and adding 1.
        //The purpose of this is simply to have the output post-state be different
        //than the input pre-state. `effects` is not used.
        val arr    = if (prestate.isEmpty) zero.clone() else prestate.toByteArray
        val n      = BigInt(arr)
        val newArr = pad((n + 1).toByteArray, 32)

        ByteString.copyFrom(newArr).asRight[Throwable].pure[F]
      }

      override def close(): F[Unit] = ().pure[F]
    }

  private def pad(x: Array[Byte], length: Int): Array[Byte] =
    if (x.length < length) Array.fill(length - x.length)(0.toByte) ++ x
    else x
}
