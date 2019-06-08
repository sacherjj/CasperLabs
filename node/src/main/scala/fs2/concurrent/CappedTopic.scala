package fs2.concurrent

import cats.Eq
import cats.syntax.all._
import cats.effect.{Concurrent, Sync}

import scala.collection.immutable.{Queue => ScalaQueue}
import fs2.internal.Token
import fs2._

/**
  * Asynchronous CappedTopic.
  *
  * CappedTopic allows you to distribute `A` published by arbitrary number of publishers to arbitrary number of subscribers.
  *
  * CappedTopic drops the oldest message from the underlying queue if it reaches `maxQueued` size.
  *
  * Additionally the subscriber has possibility to terminate whenever size of enqueued elements is over certain size
  * by using `subscribeSize`.
  */
abstract class CappedTopic[F[_], A] { self =>

  /**
    * Publishes elements from source of `A` to this topic.
    * [[Pipe]] equivalent of `publish1`.
    */
  def publish: Pipe[F, A, Unit]

  /**
    * Publishes one `A` to topic.
    *
    * If any of the subscribers is over the `maxQueued` limit, this will drop the oldest message from the queue
    */
  def publish1(a: A): F[Unit]

  /**
    * Subscribes for `A` values that are published to this topic.
    *
    * Pulling on the returned stream opens a "subscription", which allows up to
    * `maxQueued` elements to be enqueued as a result of publication.
    *
    * The first element in the stream is always the last published `A` at the time
    * the stream is first pulled from, followed by each published `A` value from that
    * point forward.
    *
    * If at any point, the queue backing the subscription has `maxQueued` elements in it,
    * any further publications will drop the oldest message from the queue
    *
    * @param maxQueued maximum number of elements to enqueue to the subscription
    * queue before starting dropping messages
    */
  def subscribe(maxQueued: Int): Stream[F, A]

  /**
    * Like [[subscribe]] but emits an approximate number of queued elements for this subscription
    * with each emitted `A` value.
    */
  def subscribeSize(maxQueued: Int): Stream[F, (A, Int)]

  /**
    * Signal of current active subscribers.
    */
  def subscribers: Stream[F, Int]

  /**
    * Returns an alternate view of this `Topic` where its elements are of type `B`,
    * given two functions, `A => B` and `B => A`.
    */
  def imap[B](f: A => B)(g: B => A): CappedTopic[F, B] =
    new CappedTopic[F, B] {
      def publish: Pipe[F, B, Unit] = sfb => self.publish(sfb.map(g))
      def publish1(b: B): F[Unit]   = self.publish1(g(b))
      def subscribe(maxQueued: Int): Stream[F, B] =
        self.subscribe(maxQueued).map(f)
      def subscribers: Stream[F, Int] = self.subscribers
      def subscribeSize(maxQueued: Int): Stream[F, (B, Int)] =
        self.subscribeSize(maxQueued).map { case (a, i) => f(a) -> i }
    }
}

object CappedTopic {

  def apply[F[_], A](initial: A)(implicit F: Concurrent[F]): F[CappedTopic[F, A]] = {
    implicit def eqInstance: Eq[Strategy.State[A]] =
      Eq.instance[Strategy.State[A]](_.subscribers.keySet == _.subscribers.keySet)

    PubSub(PubSub.Strategy.Inspectable.strategy(Strategy.boundedSubscribers(initial))).map {
      pubSub =>
        new CappedTopic[F, A] {

          def subscriber(size: Int): Stream[F, ((Token, Int), Stream[F, ScalaQueue[A]])] =
            Stream
              .bracket(
                Sync[F]
                  .delay((new Token, size))
                  .flatTap(selector => pubSub.subscribe(Right(selector)))
              )(selector => pubSub.unsubscribe(Right(selector)))
              .map { selector =>
                selector ->
                  pubSub.getStream(Right(selector)).flatMap {
                    case Right(q) => Stream.emit(q)
                    case Left(_)  => Stream.empty // impossible
                  }

              }

          def publish: Pipe[F, A, Unit] =
            _.evalMap(publish1)

          def publish1(a: A): F[Unit] =
            pubSub.publish(a)

          def subscribe(maxQueued: Int): Stream[F, A] =
            subscriber(maxQueued).flatMap { case (_, s) => s.flatMap(Stream.emits) }

          def subscribeSize(maxQueued: Int): Stream[F, (A, Int)] =
            subscriber(maxQueued).flatMap {
              case (selector, stream) =>
                stream
                  .flatMap { q =>
                    Stream.emits(q.zipWithIndex.map { case (a, idx) => (a, q.size - idx) })
                  }
                  .evalMap {
                    case (a, remQ) =>
                      pubSub.get(Left(None)).map {
                        case Left(s) =>
                          (a, s.subscribers.get(selector).map(_.size + remQ).getOrElse(remQ))
                        case Right(_) => (a, -1) // impossible
                      }
                  }
            }

          def subscribers: Stream[F, Int] =
            Stream
              .bracket(Sync[F].delay(new Token))(token => pubSub.unsubscribe(Left(Some(token))))
              .flatMap { token =>
                pubSub.getStream(Left(Some(token))).flatMap {
                  case Left(s)  => Stream.emit(s.subscribers.size)
                  case Right(_) => Stream.empty //impossible

                }
              }
        }
    }
  }

  private[fs2] object Strategy {

    final case class State[A](
        last: A,
        subscribers: Map[(Token, Int), ScalaQueue[A]]
    )

    /**
      * Strategy for topic, where every subscriber can specify max size of queued elements.
      * If that subscription is exceeded any other `publish` to the topic will drop the oldest message from the queue.
      *
      * @param start  Initial value of the topic.
      */
    def boundedSubscribers[F[_], A](
        start: A
    ): PubSub.Strategy[A, ScalaQueue[A], State[A], (Token, Int)] =
      new PubSub.Strategy[A, ScalaQueue[A], State[A], (Token, Int)] {
        def initial: State[A]                       = State(start, Map.empty)
        def accepts(i: A, state: State[A]): Boolean = true

        def publish(i: A, state: State[A]): State[A] =
          State(
            last = i,
            subscribers = state.subscribers.map {
              case (k @ (_, max), v) => (k, if (v.size < max) v :+ i else v.tail :+ i)
            }
          )

        // Register empty queue
        def regEmpty(selector: (Token, Int), state: State[A]): State[A] =
          state.copy(subscribers = state.subscribers + (selector -> ScalaQueue.empty))

        def get(selector: (Token, Int), state: State[A]): (State[A], Option[ScalaQueue[A]]) =
          state.subscribers.get(selector) match {
            case None =>
              (regEmpty(selector, state), Some(ScalaQueue(state.last)))
            case r @ Some(q) =>
              if (q.isEmpty) (state, None)
              else (regEmpty(selector, state), r)

          }

        def empty(state: State[A]): Boolean =
          false

        def subscribe(selector: (Token, Int), state: State[A]): (State[A], Boolean) =
          (state, true) // no subscribe necessary, as we always subscribe by first attempt to `get`

        def unsubscribe(selector: (Token, Int), state: State[A]): State[A] =
          state.copy(subscribers = state.subscribers - selector)
      }
  }
}
