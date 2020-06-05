package io.casperlabs.node

import java.nio.file.Path

import cats._
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import cats.mtl._
import doobie.hikari.HikariTransactor
import doobie.implicits._
import doobie.util.transactor.Transactor
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.rp.Connect._
import io.casperlabs.comm.rp._
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.shared._
import monix.eval._
import monix.execution._
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.WildcardFileFilter
import org.apache.commons.io.filefilter.IOFileFilter

package object effects {
  import com.zaxxer.hikari.HikariConfig

  def nodeDiscovery(
      id: NodeIdentifier,
      port: Int,
      timeout: FiniteDuration,
      alivePeersCacheExpirationPeriod: FiniteDuration,
      gossipingRelayFactor: Int,
      gossipingRelaySaturation: Int,
      ingressScheduler: Scheduler,
      egressScheduler: Scheduler
  )(
      implicit
      peerNodeAsk: NodeAsk[Task],
      bootstrapsAsk: BootstrapsAsk[Task],
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
        egressScheduler,
        alivePeersCacheExpirationPeriod = alivePeersCacheExpirationPeriod
      )

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

  def bootstrapsAsk(implicit state: MonadState[Task, RPConf]): ApplicativeAsk[Task, List[Node]] =
    new DefaultApplicativeAsk[Task, List[Node]] {
      val applicative: Applicative[Task] = Applicative[Task]
      def ask: Task[List[Node]]          = state.get.map(_.bootstraps)
    }

  /**
    * @see https://tpolecat.github.io/doobie/docs/14-Managing-Connections.html#about-threading
    * @param connectEC for waiting on connections, should be bounded
    * @param transactEC for JDBC, can be unbounded
    * @return Write and read Transactors
    */
  def doobieTransactors(
      conf: Configuration,
      connectEC: (String, Int, Int) => ExecutionContext,
      transactEC: (String, Int) => ExecutionContext
  ): Resource[Task, (Transactor[Task], Transactor[Task])] = {
    val serverDataDir = conf.server.dataDir
    val readThreads   = conf.server.dbReadThreads.value
    val writeThreads  = conf.server.dbWriteThreads.value
    val readPoolSize  = conf.server.dbReadConnections.value

    def mkConfig(
        poolName: String,
        poolSize: Int,
        foreignKeys: Boolean,
        connectionTimeout: FiniteDuration
    ) = {
      val config = new HikariConfig()
      config.setDriverClassName("org.sqlite.JDBC")
      config.setJdbcUrl(s"jdbc:sqlite:${serverDataDir.resolve("sqlite.db")}")
      config.setMinimumIdle(1)
      config.setMaximumPoolSize(poolSize)
      // `autoCommit=true` is a default for Hikari; doobie sets `autoCommit=false`.
      // From doobie's docs:
      // * - Auto-commit will be set to `false`;
      // * - the transaction will `commit` on success and `rollback` on failure;
      // Setting it to false to avoid seeing resets in the log every time.
      config.setAutoCommit(false)
      // Foreign keys support must be enabled explicitly in SQLite; it doesn't affect read logic though.
      // https://www.sqlite.org/foreignkeys.html#fk_enable
      config.addDataSourceProperty("foreign_keys", foreignKeys.toString)
      // NOTE: We still saw at least one SQLITE_BUSY error in testing, despite the 1 sized pool.
      // NODE-1019 will add logging, maybe we'll learn more.
      config.addDataSourceProperty("busy_timeout", "5000")
      config.addDataSourceProperty("journal_mode", "WAL")
      config.setConnectionTimeout(connectionTimeout.toMillis)
      config.setPoolName(poolName)
      config
    }

    def mkTransactor(config: HikariConfig, threads: Int): Resource[Task, HikariTransactor[Task]] =
      HikariTransactor
        .fromHikariConfig[Task](
          config,
          connectEC(config.getPoolName, config.getMaximumPoolSize, threads),
          Blocker.liftExecutionContext(transactEC(config.getPoolName, config.getMaximumPoolSize))
        )

    // Using a connection pool with maximum size of 1 for writers because with the default settings we got SQLITE_BUSY errors.
    // The SQLite docs say the driver is thread safe, but only one connection should be made per process
    // (the file locking mechanism depends on process IDs, closing one connection would invalidate the locks for all of them).
    val writeXaConfig =
      mkConfig("write", poolSize = 1, foreignKeys = true, connectionTimeout = 30.seconds)

    // Using a separate Transactor for read operations because
    // we use fs2.Stream as a return type in some places which hold an opened connection
    // preventing acquiring a connection in other places if we use a connection pool with size of 1.
    val readXaConfig =
      mkConfig("read", poolSize = readPoolSize, foreignKeys = false, connectionTimeout = 30.seconds)

    // Hint: Use config.setLeakDetectionThreshold(10000) to detect connection leaking
    for {
      writeXa <- mkTransactor(writeXaConfig, writeThreads)
      readXa  <- mkTransactor(readXaConfig, readThreads)
    } yield (writeXa, readXa)
  }

  def periodicStorageSizeMetrics[F[_]: Concurrent: Timer: Metrics](
      conf: Configuration,
      updatePeriod: FiniteDuration = 15.seconds
  ): Resource[F, F[Unit]] = {
    implicit val metricsSource = Metrics.BaseSource / "storage"
    val serverDataDir          = conf.server.dataDir.toFile
    // The node configuration doesn't know where the global state is,
    // but by default it's .mdb files under a subdirectory of the data dir.
    // NOTE: Doesn't work with docker, separate containers.
    val maybeGlobalStateDir =
      Option(conf.server.dataDir.resolve("global_state")).map(_.toFile).filter(_.exists)

    val sqlFileFilter: IOFileFilter = new WildcardFileFilter("sqlite*")
    val sqlDirFilter: IOFileFilter  = null

    val getSizes = Sync[F].delay {
      val dataDirSize        = FileUtils.sizeOfDirectory(serverDataDir)
      val sqliteFiles        = FileUtils.listFiles(serverDataDir, sqlFileFilter, sqlDirFilter)
      val sqliteSize         = sqliteFiles.asScala.map(_.length).sum
      val globalStateDirSize = maybeGlobalStateDir.fold(0L)(FileUtils.sizeOfDirectory)
      (dataDirSize, sqliteSize, globalStateDirSize)
    }

    val update = for {
      (dirS, sqlS, gsS) <- getSizes
      _                 <- Metrics[F].setGauge("data-dir-size-bytes", dirS)
      _                 <- Metrics[F].setGauge("sqlite-size-bytes", sqlS)
      _                 <- Metrics[F].setGauge("global-state-size-bytes", gsS)
      _                 <- Timer[F].sleep(updatePeriod)
    } yield ()

    update.forever.background
  }

  def periodicThreadPoolMetrics[F[_]: Concurrent: Timer: Metrics](
      schedulerFactory: SchedulerFactory,
      updatePeriod: FiniteDuration = 15.seconds
  ): Resource[F, F[Unit]] = {
    implicit val metricsSource = Metrics.BaseSource / "threads"
    val update = for {
      poolStats <- Sync[F].delay(schedulerFactory.getStats)
      _ <- poolStats.toList.traverse {
            case (name, stats) =>
              for {
                _ <- Metrics[F].setGauge(s"$name-pool-size", stats.poolSize.toLong)
                _ <- Metrics[F]
                      .setGauge(s"$name-active-thread-count", stats.activeThreadCount.toLong)
                _ <- Metrics[F].setGauge(s"$name-task-queue-size", stats.taskQueueSize)
              } yield ()
          }
      _ <- Timer[F].sleep(updatePeriod)
    } yield ()

    update.forever.background
  }
}
