package io.casperlabs.storage

import java.nio.file.{Files, Paths}

import cats.effect.Blocker
import doobie.util.ExecutionContexts
import doobie.util.transactor.Transactor
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared.Time
import monix.execution.schedulers.CanBlock.permit
import monix.execution.Scheduler.Implicits.global
import monix.eval.Task
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, Suite}

import scala.concurrent.duration._
import scala.util.Try

trait SQLiteFixture[A] extends BeforeAndAfterEach with BeforeAndAfterAll { self: Suite =>
  def db: String
  def createTestResource: Task[A]

  protected implicit val xa: Transactor[Task] = Transactor.fromDriverManager[Task](
    "org.sqlite.JDBC",
    s"jdbc:sqlite:$db",
    "",
    "",
    Blocker.liftExecutionContext(ExecutionContexts.synchronous)
  )
  protected implicit val metricsNOP: Metrics[Task] = new MetricsNOP[Task]
  protected implicit val time: Time[Task] = new Time[Task] {
    override def currentMillis: Task[Long] = Task(System.currentTimeMillis())

    override def nanoTime: Task[Long] = Task(System.nanoTime())

    override def sleep(duration: FiniteDuration): Task[Unit] = Task.sleep(duration)
  }

  protected val flyway: Flyway = {
    val conf =
      Flyway
        .configure()
        .dataSource(s"jdbc:sqlite:$db", "", "")
        .locations(new Location("classpath:/db/migration"))
    conf.load()
  }

  protected def runSQLiteTest[B](test: A => Task[B], timeout: FiniteDuration = 5.seconds): B =
    runSQLiteTest(createTestResource, test, timeout)

  /* If you need customise resources creation for some tests */
  protected def runSQLiteTest[B](
      resources: Task[A],
      test: A => Task[B],
      timeout: FiniteDuration
  ): B = {
    val program = for {
      _ <- Task(cleanupTables())
      _ <- Task(setupTables())
      a <- resources
      b <- test(a)
    } yield b
    program.runSyncUnsafe(timeout)
  }

  protected def setupTables(): Unit = flyway.migrate()

  protected def cleanupTables(): Unit = flyway.clean()

  protected def cleanupDatabase(): Unit = Try(Files.delete(Paths.get(db)))

  override protected def beforeEach(): Unit = cleanupTables()

  override protected def afterEach(): Unit = cleanupTables()

  override protected def beforeAll(): Unit = cleanupDatabase()

  override protected def afterAll(): Unit = cleanupDatabase()
}
