package io.casperlabs.casper.deploybuffer

import java.nio.file.{Files, Paths}

import doobie._
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.shared.Time
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.CanBlock.permit
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.Location
import org.scalatest._

import scala.concurrent.duration._
import scala.util.Try

class DeployBufferImplSpec extends DeployBufferSpec with BeforeAndAfterEach with BeforeAndAfterAll {
  private val db = Paths.get("/", "tmp", "deploy_buffer_spec.db")

  private implicit val xa: Transactor[Task] = Transactor
    .fromDriverManager[Task](
      "org.sqlite.JDBC",
      s"jdbc:sqlite:$db",
      "",
      "",
      ExecutionContexts.synchronous
    )
  private implicit val metricsNOP: Metrics[Task] = new MetricsNOP[Task]
  private implicit val time: Time[Task] = new Time[Task] {
    override def currentMillis: Task[Long] = Task(System.currentTimeMillis())

    override def nanoTime: Task[Long] = Task(System.nanoTime())

    override def sleep(duration: FiniteDuration): Task[Unit] = Task.sleep(duration)
  }

  private val flyway = {
    val conf =
      Flyway
        .configure()
        .dataSource(s"jdbc:sqlite:$db", "", "")
        .locations(new Location("classpath:db/migration"))
    conf.load()
  }

  override protected def testFixture(test: DeployBuffer[Task] => Task[Unit]): Unit = {
    val program = for {
      _            <- Task(cleanupTables())
      _            <- Task(setupTables())
      deployBuffer <- DeployBufferImpl.create[Task]
      _            <- test(deployBuffer)
    } yield ()
    program.runSyncUnsafe(5.seconds)
  }

  private def setupTables(): Unit = flyway.migrate()

  private def cleanupTables(): Unit = flyway.clean()

  private def cleanupDatabase(): Unit =
    Try(Files.delete(Paths.get("/", "tmp", "deploy_buffer_spec.db")))

  override protected def beforeEach(): Unit = cleanupTables()

  override protected def afterEach(): Unit = cleanupTables()

  override protected def beforeAll(): Unit = cleanupDatabase()

  override protected def afterAll(): Unit = cleanupDatabase()
}
