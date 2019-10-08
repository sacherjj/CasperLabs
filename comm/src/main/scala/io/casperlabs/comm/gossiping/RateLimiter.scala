package io.casperlabs.comm.gossiping

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import io.casperlabs.comm.ServiceError.ResourceExhausted

import scala.concurrent.duration.{Duration, FiniteDuration}
import upperbound._

/**
  * Purely functional rate limiter
  * @tparam F Effect type (usually IO)
  * @tparam B Grouping type. Each instance of [[B]] will limit only its own group.
  */
trait RateLimiter[F[_], B] {

  /**
    * Applies rate limiting algorithm to [[fa]].
    * Returns the same effect, but can block (semantically, without blocking real threads)
    * if rate limit exceeded or fail with [[ResourceExhausted]].
    *
    * Safe to be invoked concurrently.
    *
    * @param fa Effect to rate limit
    * @param b Serves for grouping [[fa]]'s into rate limiting groups.
    *          Each group rate limited separately from others.
    * @param priority Job priority to run,
    *                 jobs are expected to be sorted in desc order if they are in waiting state
    * @tparam A Returning value of effect
    * @return The wrapped effect
    * @throws ResourceExhausted
    */
  def await[A](b: B, fa: F[A], priority: Int = 0): F[A]
}

object RateLimiter {
  private type Finalizer[F[_]] = F[Unit]

  /* Summoner */
  def apply[F[_], B](implicit rt: RateLimiter[F, B]): RateLimiter[F, B] = rt

  /**
    *
    * @param elementsPerPeriod Maximum number of requests per instance of [[B]] in [[period]].
    *                          Must be > 0.
    * @param period Time window for calculating rate limiting.
    *               Must be > Duration.Zero.
    * @param maxQueueSize Queue size of incoming and delayed effects, if queue is full fails effect with [[ResourceExhausted]].
    *                     Must be > 0.
    * @tparam F Effect type (usually IO)
    * @tparam B Grouping type. Each instance of [[B]] will limit only its own group.
    * @return RateLimiter
    */
  def create[F[_]: Concurrent: Timer, B](
      elementsPerPeriod: Int,
      period: FiniteDuration,
      maxQueueSize: Int
  ): Resource[F, RateLimiter[F, B]] = {
    require(elementsPerPeriod > 0)
    require(period > Duration.Zero)
    require(maxQueueSize > 0)

    val createRateLimiter = Semaphore[F](1L).map { lock =>
      // To group limiters and their finalizers by instances of B
      val limiters =
        scala.collection.mutable.Map.empty[B, (Limiter[F], Finalizer[F])]
      Resource.make[F, RateLimiter[F, B]](acquire = Sync[F].delay {
        new RateLimiter[F, B] {
          override def await[A](b: B, fa: F[A], priority: Int): F[A] = getOrCreateLimiter(b) >>= {
            implicit limiter =>
              Limiter.await(fa, priority).adaptError {
                case _: LimitReachedException => ResourceExhausted("Rate exceeded")
              }
          }

          private def getOrCreateLimiter(b: B): F[Limiter[F]] = lock.withPermit {
            for {
              maybeLimiter <- Sync[F].delay(limiters.get(b))
              (limiter, finalizer) <- maybeLimiter.fold(
                                       Limiter
                                         .start[F](Rate(elementsPerPeriod, period), maxQueueSize)
                                         .allocated
                                     )(_.pure[F])
              _ <- Sync[F].delay(limiters += (b -> (limiter -> finalizer)))
            } yield limiter
          }
        }
      })(
        release = _ =>
          lock.withPermit {
            Sync[F].delay(limiters.values.map(_._2).toList) >>= {
              _.traverse(_.attempt.void).void
            }
          }
      )
    }
    Resource.liftF(createRateLimiter).flatten
  }

  /* Does not apply any rate limiting, immediately returns [[fa]] */
  def noOp[F[_], B]: RateLimiter[F, B] = new RateLimiter[F, B] {
    override def await[A](b: B, fa: F[A], priority: Int = 0): F[A] = fa
  }
}
