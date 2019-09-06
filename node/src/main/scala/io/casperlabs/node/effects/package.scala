package io.casperlabs.node

import java.nio.file.Path

import cats._
import cats.effect._
import cats.implicits._
import cats.mtl._
import doobie.free.connection
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
  import com.zaxxer.hikari.HikariConfig

  def log: Log[Task] = Log.log

  def nodeDiscovery(
      id: NodeIdentifier,
      port: Int,
      timeout: FiniteDuration,
      gossipingEnabled: Boolean,
      gossipingRelayFactor: Int,
      gossipingRelaySaturation: Int,
      ingressScheduler: Scheduler,
      egressScheduler: Scheduler
  )(init: Option[Node])(
      implicit
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
        gossipingRelaySaturation,
        ingressScheduler,
        egressScheduler
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

  // https://tpolecat.github.io/doobie/docs/14-Managing-Connections.html#about-threading
  def doobieTransactor(
      connectEC: ExecutionContext,  // for waiting on connections, should be bounded
      transactEC: ExecutionContext, // for JDBC, can be unbounded
      serverDataDir: Path
  ): Resource[Effect, Transactor[Effect]] = {
    val config = new HikariConfig()
    config.setDriverClassName("org.sqlite.JDBC")
    config.setJdbcUrl(s"jdbc:sqlite:${serverDataDir.resolve("sqlite.db")}")
    config.setMinimumIdle(1)
    config.setMaximumPoolSize(1)
    // Using a connection pool with maximum size of 1 becuase with the default settings we got SQLITE_BUSY errors.
    // The SQLite docs say the driver is thread safe, but only one connection should be made per process
    // (the file locking mechanism depends on process IDs, closing one connection would invalidate the locks for all of them).
    HikariTransactor
      .fromHikariConfig[Effect](
        config,
        connectEC,
        transactEC
      )
      .map { xa =>
        // Foreign keys support must be enabled explicitly in SQLite
        // https://www.sqlite.org/foreignkeys.html#fk_enable
        val mxa = Transactor.before
          .set(xa, sql"PRAGMA foreign_keys = ON;".update.run.void >> Transactor.before.get(xa))
        // `autoCommit=true` is a default for Hikari; doobie sets `autoCommit=false`.
        // From doobie's docs:
        // * - Auto-commit will be set to `false`;
        // * - the transaction will `commit` on success and `rollback` on failure;
        Transactor.before.modify(mxa, _ >> connection.setAutoCommit(false))
      }
  }
}
