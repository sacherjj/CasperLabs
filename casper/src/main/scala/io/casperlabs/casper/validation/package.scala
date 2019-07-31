package io.casperlabs.casper

import cats.{ApplicativeError, Functor}
import cats.mtl.FunctorRaise
import cats.syntax.applicativeError._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.casper.validation.Errors.ValidateErrorWrapper

package object validation {
  type RaiseValidationError[F[_]] = FunctorRaise[F, InvalidBlock]
  object RaiseValidationError {
    def apply[F[_]](implicit ev: RaiseValidationError[F]): RaiseValidationError[F] = ev
  }

  def raiseValidateErrorThroughApplicativeError[F[_]: ApplicativeError[?[_], Throwable]]
      : FunctorRaise[F, InvalidBlock] =
    new FunctorRaise[F, InvalidBlock] {
      override val functor: Functor[F] =
        Functor[F]

      override def raise[A](e: InvalidBlock): F[A] =
        ValidateErrorWrapper(e).raiseError[F, A]
    }

  def ignore(block: Block, reason: String): String =
    ignore(block.blockHash, reason)

  def ignore(block: BlockSummary, reason: String): String =
    ignore(block.blockHash, reason)

  def ignore(blockHash: ByteString, reason: String): String =
    s"Ignoring block ${PrettyPrinter.buildString(blockHash)} because $reason"
}
