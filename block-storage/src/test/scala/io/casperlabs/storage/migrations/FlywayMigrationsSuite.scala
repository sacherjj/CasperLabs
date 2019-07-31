package io.casperlabs.storage.migrations

import java.nio.file.{Files, Paths}

import cats.syntax.either._
import org.flywaydb.core.Flyway
import org.scalatest.{BeforeAndAfterEach, FunSuite}

import scala.util.Try

class FlywayMigrationsSuite extends FunSuite with BeforeAndAfterEach {

  private val db = Paths.get("/", "tmp", "casperlabs-flyway-migration-test.db")

  test("Flyway migration should detect migration SQL scripts and run them") {
    val conf =
      Flyway
        .configure()
        .dataSource(s"jdbc:sqlite:${db.toString}", "", "")
        .locations("classpath:test-migrations")
    val flyway               = conf.load()
    val appliedMigrationsNum = flyway.migrate()
    val validationResult     = Either.catchNonFatal(flyway.validate())
    assert(appliedMigrationsNum == 1)
    assert(validationResult == Right(()))
  }

  override protected def beforeEach(): Unit = cleanup()

  override protected def afterEach(): Unit = cleanup()

  private def cleanup(): Unit = Try(Files.delete(db))
}
