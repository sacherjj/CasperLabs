package io.casperlabs.comm.discovery

import java.util.concurrent.atomic.AtomicInteger

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.Random

import cats._
import cats.effect.Timer
import cats.effect.concurrent.MVar
import cats.implicits._
import io.casperlabs.comm._
import scala.concurrent.duration._

abstract class KademliaServiceRuntime[F[_]: Monad: Timer, E <: Environment] extends TestRuntime {

  def createEnvironment(port: Int): F[E]

  def createKademliaService(env: E, timeout: FiniteDuration): F[KademliaService[F]]

  def extract[A](fa: F[A]): A

  def twoNodesEnvironment[A](block: (E, E) => F[A]): F[A] =
    for {
      e1 <- createEnvironment(getFreePort)
      e2 <- createEnvironment(getFreePort)
      _  = require(e1 != e2, "Oop, we picked the same port twice!")
      r  <- block(e1, e2)
    } yield r

  trait Runtime[A] {
    protected def pingHandler: PingHandler[F]
    protected def lookupHandler: LookupHandler[F]
    def run(): Result
    trait Result {
      def localNode: Node
      def apply(): A
    }
  }

  abstract class TwoNodesRuntime[A](
      val pingHandler: PingHandler[F] = Handler.pingHandler,
      val lookupHandler: LookupHandler[F] = Handler.lookupHandlerNil,
      // Give it ample time on Drone.
      timeout: FiniteDuration = 5.seconds
  ) extends Runtime[A] {
    def execute(kademlia: KademliaService[F], local: Node, remote: Node): F[A]

    def run(): TwoNodesResult =
      extract(
        twoNodesEnvironment { (e1, e2) =>
          for {
            kademliaLocal  <- createKademliaService(e1, timeout)
            kademliaRemote <- createKademliaService(e2, timeout)
            local          = e1.peer
            remote         = e2.peer
            _ <- kademliaLocal.receive(
                  Handler.pingHandler[F].handle(local),
                  Handler.lookupHandlerNil[F].handle(local)
                )
            _ <- kademliaRemote.receive(
                  pingHandler.handle(remote),
                  lookupHandler.handle(remote)
                )
            r <- execute(kademliaLocal, local, remote)
            _ <- kademliaRemote.shutdown()
            _ <- kademliaLocal.shutdown()
          } yield
            new TwoNodesResult {
              def localNode: Node  = local
              def remoteNode: Node = remote
              def apply(): A       = r
            }
        }
      )

    trait TwoNodesResult extends Result {
      def remoteNode: Node
    }
  }

  abstract class TwoNodesRemoteDeadRuntime[A](
      val pingHandler: PingHandler[F] = Handler.pingHandler,
      val lookupHandler: LookupHandler[F] = Handler.lookupHandlerNil
  ) extends Runtime[A] {
    def execute(kademliaService: KademliaService[F], local: Node, remote: Node): F[A]

    def run(): TwoNodesResult =
      extract(
        twoNodesEnvironment { (e1, e2) =>
          for {
            kademliaLocal <- createKademliaService(e1, timeout = 500.millis)
            local         = e1.peer
            remote        = e2.peer
            _ <- kademliaLocal.receive(
                  Handler.pingHandler[F].handle(local),
                  Handler.lookupHandlerNil[F].handle(local)
                )
            r <- execute(kademliaLocal, local, remote)
            _ <- kademliaLocal.shutdown()
          } yield
            new TwoNodesResult {
              def localNode: Node  = local
              def remoteNode: Node = remote
              def apply(): A       = r
            }
        }
      )

    trait TwoNodesResult extends Result {
      def remoteNode: Node
    }
  }

}

trait Environment {
  def peer: Node
  def host: String
  def port: Int
}

final class DispatcherCallback[F[_]: Functor](state: MVar[F, Unit]) {
  def notifyThatDispatched(): F[Unit] = state.tryPut(()).void
  def waitUntilDispatched(): F[Unit]  = state.take
}

abstract class Handler[F[_]: Monad: Timer, R] {
  def received: Seq[(Node, R)] = receivedMessages
  protected val receivedMessages: mutable.MutableList[(Node, R)] =
    mutable.MutableList.empty[(Node, R)]
}

final class PingHandler[F[_]: Monad: Timer](
    delay: Option[FiniteDuration] = None
) extends Handler[F, Node] {
  def handle(peer: Node): Node => F[Unit] =
    p =>
      for {
        _ <- receivedMessages.synchronized(receivedMessages += ((peer, p))).pure[F]
        _ <- delay.fold(().pure[F])(implicitly[Timer[F]].sleep)
      } yield ()
}

final class LookupHandler[F[_]: Monad: Timer](
    response: Seq[Node],
    delay: Option[FiniteDuration] = None
) extends Handler[F, (Node, NodeIdentifier)] {
  def handle(peer: Node): (Node, NodeIdentifier) => F[Seq[Node]] =
    (p, a) =>
      for {
        _ <- receivedMessages.synchronized(receivedMessages += ((peer, (p, a)))).pure[F]
        _ <- delay.fold(().pure[F])(implicitly[Timer[F]].sleep)
      } yield response
}

object Handler {
  def pingHandler[F[_]: Monad: Timer]: PingHandler[F] = new PingHandler[F]

  def pingHandlerWithDelay[F[_]: Monad: Timer](delay: FiniteDuration): PingHandler[F] =
    new PingHandler[F](Some(delay))

  def lookupHandlerNil[F[_]: Monad: Timer]: LookupHandler[F] = new LookupHandler[F](Nil)

  def lookupHandlerWithDelay[F[_]: Monad: Timer](delay: FiniteDuration): LookupHandler[F] =
    new LookupHandler[F](Nil, Some(delay))

  def lookupHandler[F[_]: Monad: Timer](result: Seq[Node]): LookupHandler[F] =
    new LookupHandler[F](result)
}
