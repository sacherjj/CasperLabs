package io.casperlabs.node

import java.nio.file.Path
import java.sql.DriverManager

import cats._
import cats.effect._
import cats.implicits._
import cats.mtl._
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.casperlabs.comm.CachedConnections.ConnectionsCache
import io.casperlabs.comm._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.rp.Connect._
import io.casperlabs.comm.rp._
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
      gossipingRelayFactor: Int,
      gossipingRelaySaturation: Int,
      ingressScheduler: Scheduler,
      egressScheduler: Scheduler
  )(init: List[Node])(
      implicit
      peerNodeAsk: NodeAsk[Task],
      log: Log[Task],
      metrics: Metrics[Task]
  ): Resource[Task, NodeDiscovery[Task]] =
    NodeDiscoveryImpl
      .create[Task](
        id,
        port,
        timeout,
        gossipingRelayFactor,
        gossipingRelaySaturation,
        ingressScheduler,
        egressScheduler
      )(init)

  def time(implicit timer: Timer[Task]): Time[Task] =
    new Time[Task] {
      def currentMillis: Task[Long]                   = timer.clock.realTime(MILLISECONDS)
      def nanoTime: Task[Long]                        = timer.clock.monotonic(NANOSECONDS)
      def sleep(duration: FiniteDuration): Task[Unit] = timer.sleep(duration)
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
  // TODO: Investigate possible performance improvements if make use of recommended compile-time options
  // https://www.sqlite.org/compile.html#recommended_compile_time_options
  def doobieTransactor(
      transactEC: ExecutionContext, // for JDBC, can be unbounded
      serverDataDir: Path,
      log: Log[Task]
  ): Resource[Task, Transactor[Task]] = {
    val connectionResource = Resource.make(
      Task {
        val connection =
          DriverManager.getConnection(s"jdbc:sqlite:${serverDataDir.resolve("sqlite.db")}")
        connection.setAutoCommit(false)
        connection
      }
    )(
      connection =>
        Task(connection.close())
          .handleErrorWith(e => log.error("Failed to close the SQLite connection", e))
    )
    // Using a transactor based on a single connection because with the default settings we got SQLITE_BUSY errors.
    // The SQLite docs say the driver is thread safe, but only one connection should be made per process
    // (the file locking mechanism depends on process IDs, closing one connection would invalidate the locks for all of them).
    connectionResource
      .map { connection =>
        val xa = Transactor.fromConnection[Task](connection, transactEC)
        // Foreign keys support must be enabled explicitly in SQLite
        // https://www.sqlite.org/foreignkeys.html#fk_enable,
        Transactor.before
          .set(
            xa,
            sql"PRAGMA foreign_keys = ON;".update.run.void >> Transactor.before
              .get(xa)
          )
      }
  }
}
