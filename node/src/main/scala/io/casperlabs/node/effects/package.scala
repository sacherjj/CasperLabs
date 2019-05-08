package io.casperlabs.node

import java.nio.file.Path

import cats.{~>, Applicative, Monad, Parallel}
import cats.data.EitherT
import cats.effect.{Concurrent, ConcurrentEffect, Fiber, Resource, Timer}
import cats.mtl._
import cats.temp.par.Par
import io.casperlabs.comm.CachedConnections.ConnectionsCache
import io.casperlabs.comm._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.rp.Connect._
import io.casperlabs.comm.rp._
import io.casperlabs.comm.transport._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._
import monix.eval._
import monix.execution._
import monix.eval.instances._

import scala.concurrent.duration._
import scala.io.Source

package object effects {

  def log: Log[Task] = Log.log

  def nodeDiscovery(id: NodeIdentifier, port: Int, timeout: FiniteDuration)(init: Option[Node])(
      implicit
      scheduler: Scheduler,
      peerNodeAsk: NodeAsk[Task],
      log: Log[Task],
      time: Time[Task],
      metrics: Metrics[Task]
  ): Resource[Effect, NodeDiscovery[Task]] =
    NodeDiscoveryImpl
      .create[Task](id, port, timeout)(init)
      .toEffect

  def time(implicit timer: Timer[Task]): Time[Task] =
    new Time[Task] {
      def currentMillis: Task[Long]                   = timer.clock.realTime(MILLISECONDS)
      def nanoTime: Task[Long]                        = timer.clock.monotonic(NANOSECONDS)
      def sleep(duration: FiniteDuration): Task[Unit] = timer.sleep(duration)
    }

  def tcpTransportLayer(
      port: Int,
      certPath: Path,
      keyPath: Path,
      maxMessageSize: Int,
      chunkSize: Int,
      folder: Path
  )(
      implicit scheduler: Scheduler,
      log: Log[Task],
      metrics: Metrics[Task],
      cache: ConnectionsCache[Task, TcpConnTag]
  ): TcpTransportLayer = {
    val cert = Resources.withResource(Source.fromFile(certPath.toFile))(_.mkString)
    val key  = Resources.withResource(Source.fromFile(keyPath.toFile))(_.mkString)
    new TcpTransportLayer(port, cert, key, maxMessageSize, chunkSize, folder, 100)
  }

  def rpConnections: Task[ConnectionsCell[Task]] =
    Cell.mvarCell[Task, Connections](Connections.empty)

  def rpConfAsk(implicit state: MonadState[Task, RPConf]): ApplicativeAsk[Task, RPConf] =
    new DefaultApplicativeAsk[Task, RPConf] {
      val applicative: Applicative[Task] = Applicative[Task]
      def ask: Task[RPConf]              = state.get
    }

  def peerNodeAsk(implicit state: MonadState[Task, RPConf]): ApplicativeAsk[Task, Node] =
    new DefaultApplicativeAsk[Task, Node] {
      val applicative: Applicative[Task] = Applicative[Task]
      def ask: Task[Node]                = state.get.map(_.local)
    }

  implicit def eitherTpeerNodeAsk(
      implicit ev: ApplicativeAsk[Task, Node]
  ): ApplicativeAsk[Effect, Node] =
    ApplicativeAsk[Effect, Node]

  implicit val parEffectInstance: Par[Effect] = Par.fromParallel(CatsParallelForEffect)

  // We could try figuring this out for a type as follows and then we wouldn't have to use `raiseError`:
  // type EffectPar[A] = EitherT[Task.Par, CommError, A]
  object CatsParallelForEffect extends Parallel[Effect, Task.Par] {
    override def applicative: Applicative[Task.Par] = CatsParallelForTask.applicative
    override def monad: Monad[Effect]               = Monad[Effect]

    override val sequential: Task.Par ~> Effect = new (Task.Par ~> Effect) {
      def apply[A](fa: Task.Par[A]): Effect[A] = {
        val task = Task.Par.unwrap(fa)
        EitherT.liftF(task)
      }
    }
    override val parallel: Effect ~> Task.Par = new (Effect ~> Task.Par) {
      def apply[A](fa: Effect[A]): Task.Par[A] = {
        val task = fa.value.flatMap {
          case Left(ce) => Task.raiseError(new RuntimeException(ce.toString))
          case Right(a) => Task.pure(a)
        }
        Task.Par.apply(task)
      }
    }
  }

  implicit def catsConcurrentEffectForEffect(implicit scheduler: Scheduler) =
    new ConcurrentEffect[Effect] {
      val C = implicitly[Concurrent[Effect]]
      val E = implicitly[cats.effect.Effect[Task]]
      val F = implicitly[ConcurrentEffect[Task]]

      // Members declared in cats.Applicative
      def pure[A](x: A): Effect[A] =
        C.pure[A](x)

      // Members declared in cats.ApplicativeError
      def handleErrorWith[A](fa: Effect[A])(f: Throwable => Effect[A]): Effect[A] =
        C.handleErrorWith[A](fa)(f)

      def raiseError[A](e: Throwable): Effect[A] =
        C.raiseError[A](e)

      // Members declared in cats.effect.Async
      def async[A](k: (Either[Throwable, A] => Unit) => Unit): Effect[A] =
        C.async[A](k)

      def asyncF[A](k: (Either[Throwable, A] => Unit) => Effect[Unit]): Effect[A] =
        C.asyncF[A](k)

      // Members declared in cats.effect.Bracket
      def bracketCase[A, B](acquire: Effect[A])(
          use: A => Effect[B]
      )(release: (A, cats.effect.ExitCase[Throwable]) => Effect[Unit]): Effect[B] =
        C.bracketCase[A, B](acquire)(use)(release)

      // Members declared in cats.effect.Concurrent
      def racePair[A, B](
          fa: Effect[A],
          fb: Effect[B]
      ): Effect[Either[(A, Fiber[Effect, B]), (Fiber[Effect, A], B)]] =
        C.racePair[A, B](fa, fb)

      def start[A](fa: Effect[A]): Effect[Fiber[Effect, A]] =
        C.start[A](fa)

      // Members declared in cats.effect.ConcurrentEffect
      def runCancelable[A](fa: Effect[A])(
          cb: Either[Throwable, A] => cats.effect.IO[Unit]
      ): cats.effect.SyncIO[cats.effect.CancelToken[Effect]] =
        F.runCancelable(fa.value) {
          case Left(ex)        => cb(Left(ex))
          case Right(Left(ce)) => cb(Left(new RuntimeException(ce.toString)))
          case Right(Right(x)) => cb(Right(x))
        } map {
          EitherT.liftF(_)
        }

      // Members declared in cats.effect.Effect
      def runAsync[A](fa: Effect[A])(
          cb: Either[Throwable, A] => cats.effect.IO[Unit]
      ): cats.effect.SyncIO[Unit] =
        E.runAsync(fa.value) {
          case Left(ex)        => cb(Left(ex))
          case Right(Left(ce)) => cb(Left(new RuntimeException(ce.toString)))
          case Right(Right(x)) => cb(Right(x))
        }

      // Members declared in cats.FlatMap
      def flatMap[A, B](fa: Effect[A])(f: A => Effect[B]): Effect[B] =
        C.flatMap[A, B](fa)(f)

      def tailRecM[A, B](a: A)(f: A => Effect[Either[A, B]]): Effect[B] =
        C.tailRecM[A, B](a)(f)

      // Members declared in cats.effect.Sync
      def suspend[A](thunk: => Effect[A]): Effect[A] =
        C.suspend[A](thunk)

    }

}
