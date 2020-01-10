package io.casperlabs.casper.helper

import java.nio.file.{Files, Path}

import cats.effect._
import cats.implicits._
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.storage.SQLiteStorage
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{FinalityStorage, IndexedDagStorage}
import io.casperlabs.storage.deploy.DeployStorage
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.schedulers.SchedulerService
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.scalatest.Suite

trait StorageFixture { self: Suite =>
  val scheduler: SchedulerService     = Scheduler.fixedPool("storage-fixture-scheduler", 4)
  implicit val metrics: Metrics[Task] = new MetricsNOP[Task]()
  implicit val log: Log[Task]         = Log.NOPLog[Task]

  def withStorage[R](
      f: BlockStorage[Task] => IndexedDagStorage[Task] => DeployStorage[Task] => FinalityStorage[
        Task
      ] => Task[R]
  ): R = {

    val testProgram = StorageFixture.createStorages[Task]().flatMap {
      case (blockStorage, dagStorage, deployStorage, finalityStorage) =>
        f(blockStorage)(dagStorage)(deployStorage)(finalityStorage)
    }
    testProgram.unsafeRunSync(scheduler)
  }
}

object StorageFixture {
  def createStorages[F[_]: Metrics: Concurrent: ContextShift: Fs2Compiler: Time]()
      : F[(BlockStorage[F], IndexedDagStorage[F], DeployStorage[F], FinalityStorage[F])] = {
    val createDbFile = Concurrent[F].delay(Files.createTempFile("casperlabs-storages-test-", ".db"))

    def createJdbcUrl(p: Path): String = s"jdbc:sqlite:$p"

    def initTables(jdbcUrl: String): F[Unit] =
      Concurrent[F].delay {
        val flyway = {
          val conf =
            Flyway
              .configure()
              .dataSource(jdbcUrl, "", "")
              .locations(new Location("classpath:/db/migration"))
          conf.load()
        }
        flyway.migrate()
      }.void

    def createTransactor(jdbcUrl: String): Transactor.Aux[F, Unit] =
      Transactor
        .fromDriverManager[F](
          "org.sqlite.JDBC",
          jdbcUrl,
          "",
          "",
          Blocker.liftExecutionContext(ExecutionContexts.synchronous)
        )

    for {
      db                <- createDbFile
      jdbcUrl           = createJdbcUrl(db)
      xa                = createTransactor(jdbcUrl)
      _                 <- initTables(jdbcUrl)
      storage           <- SQLiteStorage.create[F](readXa = xa, writeXa = xa)
      indexedDagStorage <- IndexedDagStorage.create[F](storage)
    } yield (storage, indexedDagStorage, storage, storage)
  }
}
