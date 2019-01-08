package io.casperlabs.casper
import java.io.File
import java.nio.file.{Files, Path, Paths}

import cats.Applicative
import cats.effect.ExitCase.Error
import cats.effect.{ContextShift, Resource, Sync}
import com.typesafe.scalalogging.Logger
import io.casperlabs.shared.StoreType
import io.casperlabs.smartcontracts.GrpcExecutionEngineService
import io.casperlabs.smartcontracts.SmartContractsApi
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
      maxMessageSize: Int = 4 * 1024 * 1024
  )(implicit scheduler: Scheduler): Resource[Task, SmartContractsApi[Task]] =
    mkTempDir[Task](prefix)
      .flatMap { tmpDir =>
        Resource.make[Task, SmartContractsApi[Task]](Task.delay {
          implicit val executionEngineService: GrpcExecutionEngineService =
            new GrpcExecutionEngineService(
              Paths.get(tmpDir.toString, ".casper-node.sock").toString,
              maxMessageSize
            )
          SmartContractsApi
            .noOpApi[Task](tmpDir, storageSize, StoreType.LMDB)
        })(rt => rt.close())
      }
}
