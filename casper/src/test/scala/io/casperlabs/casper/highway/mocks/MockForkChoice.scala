package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.casper.highway.ForkChoice

class MockForkChoice[F[_]: Applicative](
    resultRef: Ref[F, ForkChoice.Result]
) extends ForkChoice[F] {
  override def fromKeyBlock(keyBlockHash: BlockHash): F[ForkChoice.Result] =
    resultRef.get

  override def fromJustifications(
      keyBlockHash: BlockHash,
      justifications: Set[BlockHash]
  ): F[ForkChoice.Result] =
    resultRef.get

  def set(result: ForkChoice.Result): F[Unit] =
    resultRef.set(result)

  def set(message: Message): F[Message] =
    resultRef.set(ForkChoice.Result(message, Set.empty)).as(message)
}

object MockForkChoice {
  def apply[F[_]: Sync](init: Message) =
    for {
      ref <- Ref.of[F, ForkChoice.Result](ForkChoice.Result(init, Set.empty))
    } yield new MockForkChoice[F](ref)
}
