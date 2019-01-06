package io.casperlabs.casper
import java.io.File
import java.nio.file.{Files, Path}

import cats.Applicative
import cats.effect.ExitCase.Error
import cats.effect.{ContextShift, Resource, Sync}
import com.typesafe.scalalogging.Logger
import io.casperlabs.casper.util.comm.GrpcExecutionEngineService
import io.casperlabs.casper.util.rholang.SmartContractsApi
import io.casperlabs.shared.StoreType
import monix.eval.Task
import monix.execution.Scheduler

import scala.reflect.io.Directory

object Resources {
  val logger: Logger = Logger(this.getClass.getName.stripSuffix("$"))

  def mkTempDir[F[_]: Applicative](prefix: String): Resource[F, Path] =
    Resource.makeCase(Applicative[F].pure(Files.createTempDirectory(prefix)))(
      (path, exitCase) =>
        Applicative[F].pure(exitCase match {
          case Error(ex) =>
            logger
              .error(
                s"Exception thrown while using the tempDir '$path'. Temporary dir NOT deleted.",
                ex
              )
          case _ => new Directory(new File(path.toString)).deleteRecursively()
        })
    )

  def mkRuntime(
      prefix: String,
      storageSize: Long = 1024 * 1024,
      storeType: StoreType = StoreType.LMDB,
      socket: String,
      maxMessageSize: Int
  )(implicit scheduler: Scheduler): Resource[Task, SmartContractsApi[Task]] =
    mkTempDir[Task](prefix)
      .flatMap { tmpDir =>
        Resource.make[Task, SmartContractsApi[Task]](Task.delay {
          implicit val executionEngineService: GrpcExecutionEngineService =
            new GrpcExecutionEngineService(socket, maxMessageSize)
          SmartContractsApi
            .noOpApi[Task](tmpDir, storageSize, StoreType.LMDB)
        })(rt => rt.close())
      }
}
