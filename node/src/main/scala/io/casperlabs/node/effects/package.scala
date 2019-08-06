package io.casperlabs.node

import java.nio.file.Path

import cats._
import cats.effect._
import cats.implicits._
import cats.mtl._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.transactor.Transactor
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

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.io.Source

package object effects {

  def log: Log[Task] = Log.log

  def nodeDiscovery(
      id: NodeIdentifier,
      port: Int,
      timeout: FiniteDuration,
      gossipingEnabled: Boolean,
      gossipingRelayFactor: Int,
      gossipingRelaySaturation: Int
  )(init: Option[Node])(
      implicit
      scheduler: Scheduler,
      peerNodeAsk: NodeAsk[Task],
      log: Log[Task],
      metrics: Metrics[Task]
  ): Resource[Effect, NodeDiscovery[Task]] =
    NodeDiscoveryImpl
      .create[Task](
        id,
        port,
        timeout,
        gossipingEnabled,
        gossipingRelayFactor,
        gossipingRelaySaturation
      )(init)
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

  def doobieTransactor(
      connectionEC: ExecutionContext,
      transactionEC: ExecutionContext,
      serverDataDir: Path
  ): Resource[Effect, Transactor[Effect]] =
    HikariTransactor
      .newHikariTransactor[Effect](
        driverClassName = "org.sqlite.JDBC",
        url = s"jdbc:sqlite:${serverDataDir.resolve("sqlite.db")}",
        //TODO: Shouldn't we protect data by user/pass?
        user = "",
        pass = "",
        connectEC = connectionEC,
        transactEC = transactionEC
      )
      .map(
        xa =>
          // Foreign keys support must be enabled explicitly in SQLite
          // https://www.sqlite.org/foreignkeys.html#fk_enable
          Transactor.before
            .set(xa, sql"PRAGMA foreign_keys = ON;".update.run.void >> Transactor.before.get(xa))
      )
}
