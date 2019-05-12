package io.casperlabs.casper

import cats.Monad
import cats.data.EitherT
import cats.effect.Sync
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.MonadTrans
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.shared.MaybeCell
import io.casperlabs.storage.ApprovedBlockWithTransforms

object LastApprovedBlock extends LastApprovedBlockInstances {
  type LastApprovedBlock[F[_]] = MaybeCell[F, ApprovedBlockWithTransforms]

  def apply[F[_]](implicit ev: LastApprovedBlock[F]): LastApprovedBlock[F] = ev

  def of[F[_]: Sync]: F[LastApprovedBlock[F]] =
    MaybeCell.of[F, ApprovedBlockWithTransforms]

  def unsafe[F[_]: Sync](init: Option[ApprovedBlockWithTransforms] = None): LastApprovedBlock[F] =
    MaybeCell.unsafe[F, ApprovedBlockWithTransforms](init)

  def forTrans[F[_]: Monad, T[_[_], _]: MonadTrans](
      implicit C: LastApprovedBlock[F]
  ): LastApprovedBlock[T[F, ?]] =
    new MaybeCell[T[F, ?], ApprovedBlockWithTransforms] {
      override def get: T[F, Option[ApprovedBlockWithTransforms]] =
        C.get.liftM[T]
      override def set(a: ApprovedBlockWithTransforms): T[F, Unit] =
        C.set(a).liftM[T]
    }

}

sealed abstract class LastApprovedBlockInstances {
  implicit def eitherTLastApprovedBlock[E, F[_]: Monad: MaybeCell[
    ?[_],
    ApprovedBlockWithTransforms
  ]]: MaybeCell[EitherT[F, E, ?], ApprovedBlockWithTransforms] =
    LastApprovedBlock.forTrans[F, EitherT[?[_], E, ?]]
}
