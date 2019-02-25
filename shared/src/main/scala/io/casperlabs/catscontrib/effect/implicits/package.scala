package io.casperlabs.catscontrib.effect

import cats._
import cats.data.EitherT
import cats.effect.ExitCase.{Completed, Error}
import cats.effect._
import cats.syntax.applicativeError._
import cats.syntax.functor._
import cats.syntax.flatMap._
import monix.eval.{Task, TaskLift}

import scala.util.{Failure, Success, Try}
import scala.util.control.NonFatal

package object implicits {

  implicit val syncId: Sync[Id] =
    new Sync[Id] {
      def pure[A](x: A): cats.Id[A] = x

      def handleErrorWith[A](fa: cats.Id[A])(f: Throwable => cats.Id[A]): cats.Id[A] =
        try {
          fa
        } catch {
          case NonFatal(e) => f(e)
        }

      @SuppressWarnings(Array("org.wartremover.warts.Throw"))
      def raiseError[A](e: Throwable): cats.Id[A] = throw e

      def flatMap[A, B](fa: cats.Id[A])(f: A => cats.Id[B]): cats.Id[B] =
        catsInstancesForId.flatMap(fa)(f)

      def tailRecM[A, B](a: A)(f: A => cats.Id[Either[A, B]]): cats.Id[B] =
        catsInstancesForId.tailRecM(a)(f)

      @SuppressWarnings(Array("org.wartremover.warts.Throw"))
      def bracketCase[A, B](acquire: A)(use: A => B)(release: (A, ExitCase[Throwable]) => Unit): B =
        Try(use(acquire)) match {
          case Success(result) =>
            release(acquire, ExitCase.Completed)
            result

          case Failure(e) =>
            release(acquire, ExitCase.error(e))
            throw e
        }

      def suspend[A](thunk: => A): A = thunk
    }

  implicit val bracketTry: Bracket[Try, Throwable] = new Bracket[Try, Throwable] {
    private val trySyntax = cats.implicits.catsStdInstancesForTry

    override def bracketCase[A, B](
        acquire: Try[A]
    )(use: A => Try[B])(release: (A, ExitCase[Throwable]) => Try[Unit]): Try[B] =
      acquire.flatMap(
        resource =>
          trySyntax
            .attempt(use(resource))
            .flatMap((result: Either[Throwable, B]) => {
              val releaseEff =
                result match {
                  case Left(err) => release(resource, Error(err))
                  case Right(_)  => release(resource, Completed)
                }

              trySyntax.productR(releaseEff)(result.toTry)
            })
      )

    override def raiseError[A](e: Throwable): Try[A] = trySyntax.raiseError[A](e)

    override def handleErrorWith[A](fa: Try[A])(f: Throwable => Try[A]): Try[A] =
      trySyntax.handleErrorWith(fa)(f)

    override def pure[A](x: A): Try[A] = trySyntax.pure(x)

    override def flatMap[A, B](fa: Try[A])(f: A => Try[B]): Try[B] = trySyntax.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => Try[Either[A, B]]): Try[B] = trySyntax.tailRecM(a)(f)
  }

  implicit def taskLiftEitherT[F[_]: TaskLift: Functor, E] = new TaskLift[EitherT[F, E, ?]] {
    override def taskLift[A](task: Task[A]): EitherT[F, E, A] =
      EitherT.liftF(TaskLift[F].taskLift(task))
  }

  implicit def bracketEffect[F[_], E](
      implicit
      br: Bracket[F, Throwable],
      mErr: MonadError[EitherT[F, E, ?], Throwable]
  ) = new Bracket[EitherT[F, E, ?], Throwable] {

    override def bracketCase[A, B](acquire: EitherT[F, E, A])(
        use: A => EitherT[F, E, B]
    )(release: (A, ExitCase[Throwable]) => EitherT[F, E, Unit]): EitherT[F, E, B] = {
      case class Th(a: E) extends Throwable

      def embedE[X](et: EitherT[F, E, X]): F[X] =
        et.value.flatMap[X] {
          case Left(e)  => br.raiseError[X](Th(e))
          case Right(v) => br.pure(v)
        }

      EitherT(
        br.bracketCase(embedE(acquire))(x => embedE(use(x)))((a, e) => embedE(release(a, e)))
          .attempt
          .flatMap[Either[E, B]] {
            case Left(Th(v)) => br.pure(Left(v))
            case Left(e)     => br.raiseError(e)
            case Right(v)    => br.pure(Right(v))
          }
      )
    }

    override def flatMap[A, B](fa: EitherT[F, E, A])(f: A => EitherT[F, E, B]): EitherT[F, E, B] =
      mErr.flatMap(fa)(f)

    override def tailRecM[A, B](a: A)(f: A => EitherT[F, E, Either[A, B]]): EitherT[F, E, B] =
      mErr.tailRecM(a)(f)

    override def raiseError[A](e: Throwable): EitherT[F, E, A] =
      mErr.raiseError(e)

    override def handleErrorWith[A](
        fa: EitherT[F, E, A]
    )(f: Throwable => EitherT[F, E, A]): EitherT[F, E, A] =
      mErr.handleErrorWith(fa)(f)

    override def pure[A](x: A): EitherT[F, E, A] = mErr.pure(x)
  }
}
