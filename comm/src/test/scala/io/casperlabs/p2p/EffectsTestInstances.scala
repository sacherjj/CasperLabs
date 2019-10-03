package io.casperlabs.p2p

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import cats._
import cats.effect.Sync
import cats.implicits._
import io.casperlabs.comm.rp._
import io.casperlabs.catscontrib._
import io.casperlabs.comm._
import io.casperlabs.comm.transport._
import io.casperlabs.comm.discovery._
import io.casperlabs.shared._

/** Eagerly evaluated instances to do reasoning about applied effects */
object EffectsTestInstances {

  class LogicalTime[F[_]: Sync] extends Time[F] {
    var clock: Long = 0

    def currentMillis: F[Long] = Sync[F].delay {
      this.clock = clock + 1
      clock
    }

    def nanoTime: F[Long] = Sync[F].delay {
      this.clock = clock + 1
      clock
    }

    def sleep(duration: FiniteDuration): F[Unit] = Sync[F].delay(())

    def reset(): Unit = this.clock = 0
  }

  class NodeDiscoveryStub[F[_]: Sync]() extends NodeDiscovery[F] {

    var nodes: List[Node] = List.empty[Node]
    def reset(): Unit =
      nodes = List.empty[Node]
    def recentlyAlivePeersAscendingDistance: F[List[Node]] = Sync[F].delay {
      nodes
    }
    def discover: F[Unit]                           = ???
    def lookup(id: NodeIdentifier): F[Option[Node]] = ???
    def banTemp(node: Node): F[Unit]                = ???
  }

  def createRPConfAsk[F[_]: Applicative](
      local: Node,
      defaultTimeout: FiniteDuration = FiniteDuration(1, MILLISECONDS),
      clearConnections: ClearConnectionsConf = ClearConnectionsConf(1, 1)
  ) =
    new ConstApplicativeAsk[F, RPConf](
      RPConf(local, List(local), defaultTimeout, clearConnections)
    )

  class LogStub[F[_]: Sync](prefix: String = "", printEnabled: Boolean = false) extends Log[F] {

    @volatile var debugs: Vector[String]    = Vector.empty[String]
    @volatile var infos: Vector[String]     = Vector.empty[String]
    @volatile var warns: Vector[String]     = Vector.empty[String]
    @volatile var errors: Vector[String]    = Vector.empty[String]
    @volatile var causes: Vector[Throwable] = Vector.empty[Throwable]

    // To be able to reconstruct the timeline.
    var all: Vector[String] = Vector.empty[String]

    def reset(): Unit = synchronized {
      debugs = Vector.empty[String]
      infos = Vector.empty[String]
      warns = Vector.empty[String]
      errors = Vector.empty[String]
      causes = Vector.empty[Throwable]
      all = Vector.empty[String]
    }
    def isTraceEnabled(implicit ev: LogSource): F[Boolean]  = false.pure[F]
    def trace(msg: String)(implicit ev: LogSource): F[Unit] = ().pure[F]
    def debug(msg: String)(implicit ev: LogSource): F[Unit] = sync {
      if (printEnabled) println(s"DEBUG $prefix $msg")
      debugs = debugs :+ msg
      all = all :+ msg
    }
    def info(msg: String)(implicit ev: LogSource): F[Unit] = sync {
      if (printEnabled) println(s"INFO  $prefix $msg")
      infos = infos :+ msg
      all = all :+ msg
    }
    def warn(msg: String)(implicit ev: LogSource): F[Unit] = sync {
      if (printEnabled) println(s"WARN  $prefix $msg")
      warns = warns :+ msg
      all = all :+ msg
    }
    def error(msg: String)(implicit ev: LogSource): F[Unit] = sync {
      if (printEnabled) println(s"ERROR $prefix $msg")
      errors = errors :+ msg
      all = all :+ msg
    }
    def error(msg: String, cause: scala.Throwable)(implicit ev: LogSource): F[Unit] = sync {
      if (printEnabled) println(s"ERROR $prefix $msg: $cause")
      causes = causes :+ cause
      errors = errors :+ msg
      all = all :+ msg
    }

    private def sync(thunk: => Unit): F[Unit] = Sync[F].delay(synchronized(thunk))
  }

}
