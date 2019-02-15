package io.casperlabs.smartcontracts

import cats.Applicative
import cats.implicits._
import io.casperlabs.ipc._
import com.google.protobuf.ByteString

object ExecutionEngineServiceInstances {
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
        //This function returns the same `DeployResult` for all deploys,
        //regardless of their wasm code. The is in lieu of having a real wasm engine.
        deploys.map(_ => DeployResult(DeployResult.Result.Effects(ee))).asRight[Throwable].pure[F]

      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry]
      ): F[Either[Throwable, ByteString]] = {
        //This function increments the prestate by adding 1 to one of the bytes.
        //The purpose of this is simply to have the output post-state be different
        //than the input pre-state. `effects` is not used.
        val arr = if (prestate.isEmpty) zero.clone() else prestate.toByteArray
        val maybeIndex = arr.zipWithIndex
          .find { case (b, _) => b < Byte.MaxValue }
          .map { case (_, i) => i }
        val newArr = maybeIndex
          .fold(zero)(i => { arr.update(i, (arr(i) + 1).toByte); arr })

        ByteString.copyFrom(newArr).asRight[Throwable].pure[F]
      }

      override def close(): F[Unit] = ().pure[F]
    }
}
