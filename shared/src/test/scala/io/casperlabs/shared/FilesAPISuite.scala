package io.casperlabs.shared

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.UUID

import cats.effect.SyncIO
import io.casperlabs.shared.Log.NOPLog
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpec}

import scala.util.Try

class FilesAPISuite extends WordSpec with Matchers with BeforeAndAfterEach {

  import FilesAPISuite.TestFixture
  val nonExistentFile: Path = Paths.get(s"/tmp/$random.txt")
  val nonExistentFileWithParents: Path = Paths.get(
    s"/tmp/$random/$random/$random.txt"
  )
  val existingFile: Path       = Paths.get(s"/tmp/$random.txt")
  val existingFileData: String = random

  override protected def beforeEach(): Unit = {
    Try(Files.delete(nonExistentFile))
    Try(Files.delete(nonExistentFileWithParents))
    Try(Files.delete(nonExistentFileWithParents.getParent))
    Try(Files.delete(nonExistentFileWithParents.getParent.getParent))
    Try(Files.delete(existingFile))
    Try(Files.write(existingFile, existingFileData.getBytes()))
  }

  override protected def afterEach(): Unit = {
    Try(Files.delete(existingFile))
    Try(Files.delete(nonExistentFile))
    Try(Files.delete(nonExistentFileWithParents))
    Try(Files.delete(nonExistentFileWithParents.getParent))
    Try(Files.delete(nonExistentFileWithParents.getParent.getParent))
  }

  def random: String = UUID.randomUUID().toString

  "FilesAPI implementation" when {
    "file doesn't exist" should {

      "return None when readBytes" in TestFixture { api =>
        for {
          maybeData <- api.readBytes(nonExistentFile)
        } yield {
          maybeData shouldBe None
        }
      }
      "return None when readString" in TestFixture { api =>
        for {
          maybeData <- api.readString(nonExistentFile, StandardCharsets.UTF_8)
        } yield {
          maybeData shouldBe None
        }
      }
      "create file when writeBytes" in TestFixture { api =>
        for {
          _ <- api.writeBytes(nonExistentFile, "Hello".getBytes(StandardCharsets.UTF_8))
        } yield {
          Files.exists(nonExistentFile) shouldBe true
        }
      }
      "create file when writeString" in TestFixture { api =>
        for {
          _ <- api.writeString(nonExistentFile, "Hello")
        } yield {
          Files.exists(nonExistentFile) shouldBe true
        }
      }
    }

    "file and parent directories don't exist" should {
      "create parents directories and file when writeBytes" in TestFixture { api =>
        for {
          _ <- api.writeBytes(nonExistentFileWithParents, "Hello".getBytes(StandardCharsets.UTF_8))
        } yield {
          Files.exists(nonExistentFileWithParents) shouldBe true
        }
      }

      "create parents directories and file when writeString" in TestFixture { api =>
        for {
          _ <- api.writeString(nonExistentFileWithParents, "Hello")
        } yield {
          Files.exists(nonExistentFileWithParents) shouldBe true
        }
      }
    }

    "file exists" should {
      "return Some when readBytes" in TestFixture { api =>
        for {
          maybeData <- api.readBytes(existingFile)
        } yield {
          maybeData shouldBe an[Some[_]]
        }
      }
      "return Some when readString" in TestFixture { api =>
        for {
          maybeData <- api.readString(existingFile)
        } yield {
          maybeData shouldBe an[Some[_]]
        }
      }
      "override file when writeBytes if called without additional options" in TestFixture { api =>
        val newData = random.getBytes(StandardCharsets.UTF_8)
        for {
          _ <- api.writeBytes(existingFile, newData)
        } yield {
          Files
            .readAllBytes(existingFile)
            .toList should contain theSameElementsInOrderAs newData.toList
        }
      }
      "override file when writeString if called without additional options" in TestFixture { api =>
        val newData = random
        for {
          _ <- api.writeString(existingFile, newData)
        } yield {
          import scala.collection.JavaConverters._
          Files
            .readAllLines(existingFile)
            .asScala
            .mkString
            .trim shouldBe newData
        }
      }
    }
  }
}

object FilesAPISuite {
  private implicit val NOPLog: NOPLog[SyncIO] = new NOPLog[SyncIO]

  object TestFixture {
    def apply(test: FilesAPI[SyncIO] => SyncIO[Unit]): Unit =
      FilesAPI.create[SyncIO].flatMap(test).unsafeRunSync()
  }
}
