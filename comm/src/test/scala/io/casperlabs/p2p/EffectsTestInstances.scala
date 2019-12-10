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
import logstage.{IzLogger, LogIO}
import logstage.UnsafeLogIO.UnsafeLogIOSyncSafeInstance
import izumi.logstage.api.logger.LogSink
import izumi.logstage.api.rendering.logunits.LogFormat
import izumi.logstage.api.{Log => IzLog}
import izumi.fundamentals.platform.language.CodePositionMaterializer
import izumi.functional.mono.SyncSafe

/** Eagerly evaluated instances to do reasoning about applied effects */
object EffectsTestInstances {

  class LogicalTime[F[_]: Sync](init: => Long = 0) extends Time[F] {
    var clock: Long = init

    def currentMillis: F[Long] = Sync[F].delay {
      this.clock = clock + 1
      clock
    }

    def nanoTime: F[Long] = Sync[F].delay {
      this.clock = clock + 1
      clock
    }

    def sleep(duration: FiniteDuration): F[Unit] = Sync[F].delay(())

    def reset(): Unit = this.clock = init
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
}
