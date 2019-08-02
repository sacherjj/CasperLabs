package io.casperlabs.node.api.graphql

import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import fs2._
import sangria.streaming.SubscriptionStream

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/**
  * Allows to use [[fs2.Stream]] for `Subscription` queries.
  *
  * See example another example implementation [[https://github.com/sangria-graphql/sangria-monix/blob/master/src/main/scala/sangria/streaming/monix.scala]].
  *
  * Needed because http4s uses [[fs2.Stream]].
  */
private[graphql] class Fs2SubscriptionStream[F[_]: Effect](implicit val ec: ExecutionContext)
    extends SubscriptionStream[Stream[F, ?]] {

  override def supported[T[_]](other: SubscriptionStream[T]): Boolean =
    other.isInstanceOf[Fs2SubscriptionStream[F]]

  override def single[T](value: T): Stream[F, T] =
    Stream.emit[F, T](value)

  override def singleFuture[T](value: Future[T]): Stream[F, T] =
    Stream.eval(Async[F].async[T] { callback =>
      value.onComplete {
        case Success(v)         => callback(v.asRight[Throwable])
        case Failure(exception) => callback(exception.asLeft[T])
      }
    })

  override def first[T](s: Stream[F, T]): Future[T] =
    s.head.compile.toList.toIO.unsafeToFuture().map(_.head)

  override def failed[T](t: Throwable): Stream[F, T] =
    t match {
      case NonFatal(e) => Stream.raiseError[F](e)
    }

  override def onComplete[Ctx, Res](result: Stream[F, Res])(op: => Unit): Stream[F, Res] =
    result.onFinalize(Sync[F].delay(op))

  override def flatMapFuture[Ctx, Res, T](
      future: Future[T]
  )(resultFn: T => Stream[F, Res]): Stream[F, Res] =
    singleFuture(future).flatMap(resultFn)

  override def mapFuture[A, B](source: Stream[F, A])(fn: A => Future[B]): Stream[F, B] =
    source.flatMap(
      a =>
        Stream.eval(Async[F].async[B] { callback =>
          fn(a).onComplete {
            case Success(v)         => callback(v.asRight[Throwable])
            case Failure(exception) => callback(exception.asLeft[B])
          }
        })
    )

  override def map[A, B](source: Stream[F, A])(fn: A => B): Stream[F, B] =
    source.map(fn)

  override def merge[T](streams: Vector[Stream[F, T]]): Stream[F, T] =
    Stream(streams: _*).covary[F].flatten

  override def recover[T](stream: Stream[F, T])(fn: Throwable => T): Stream[F, T] =
    stream.recover {
      case NonFatal(e) => fn(e)
    }
}

private[graphql] object Fs2SubscriptionStream {
  def apply[F[_]](
      implicit fs2SubscriptionStream: Fs2SubscriptionStream[F]
  ): Fs2SubscriptionStream[F] =
    fs2SubscriptionStream
}
