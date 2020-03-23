package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import io.casperlabs.casper.highway.{ForkChoice, ForkChoiceManager}

class MockForkChoice[F[_]: Applicative](
    resultRef: Ref[F, ForkChoice.Result],
    maybeFun: Option[MockForkChoice.ForkChoiceFun]
) extends ForkChoiceManager[F] {
  override def fromKeyBlock(keyBlockHash: BlockHash): F[ForkChoice.Result] =
    maybeFun.fold(resultRef.get)(_(keyBlockHash, Set.empty).pure[F])

  override def fromJustifications(
      keyBlockHash: BlockHash,
      justifications: Set[BlockHash]
  ): F[ForkChoice.Result] =
    maybeFun.fold(resultRef.get)(_(keyBlockHash, justifications).pure[F])

  def updateLatestMessage(
      keyBlockHash: BlockHash,
      message: Message
  ): F[Unit] = message match {
    case block: Message.Block => set(block).void
    case _: Message.Ballot    => ().pure[F]
  }

  def set(result: ForkChoice.Result): F[Unit] =
    resultRef.set(result)

  def set(block: Message.Block): F[Message.Block] =
    resultRef.set(ForkChoice.Result(block, Set.empty)).as(block)
}

object MockForkChoice {
  type ForkChoiceFun = (BlockHash, Set[BlockHash]) => ForkChoice.Result

  def apply[F[_]: Sync](init: Message.Block, f: Option[ForkChoiceFun] = None) =
    Sync[F].delay(unsafe(init, f))

  def unsafe[F[_]: Sync](init: Message.Block, f: Option[ForkChoiceFun] = None) = {
    val ref = Ref.unsafe[F, ForkChoice.Result](ForkChoice.Result(init, Set.empty))
    new MockForkChoice[F](ref, f)
  }
}
