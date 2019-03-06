package io.casperlabs.casper.util
import java.io.File
import java.nio.file.{Files, Path}

import cats.Applicative
import cats.implicits._
import cats.effect.ExitCase.Error
import cats.effect.Resource
import com.typesafe.scalalogging.Logger
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.exceptions.{TestCanceledException, TestPendingException}
import scala.reflect.io.Directory

/**
  * Create by hzzhenglu on 2019-01-10
  */
object Resources {
  val logger: Logger = Logger(this.getClass.getName.stripSuffix("$"))

  def mkTempDir[F[_]: Applicative](prefix: String): Resource[F, Path] =
    Resource.makeCase(Applicative[F].pure(Files.createTempDirectory(prefix)))(
      (path, exitCase) =>
        Applicative[F].pure(exitCase match {
          case Error(ex)
              if !ex.isInstanceOf[TestCanceledException] &&
                !ex.isInstanceOf[TestPendingException] =>
            logger
              .error(
                s"Exception thrown while using the tempDir '$path'. Temporary dir NOT deleted.",
                ex
              )
          case _ => new Directory(new File(path.toString)).deleteRecursively()
        })
    )

  def mkRuntime(
      prefix: String
  )(implicit scheduler: Scheduler): Resource[Task, ExecutionEngineService[Task]] =
    mkTempDir[Task](prefix)
      .flatMap { tmpDir =>
        Resource.make[Task, ExecutionEngineService[Task]](Task.delay {
          ExecutionEngineService.noOpApi()
        })(_ => Task.unit)
      }
}
