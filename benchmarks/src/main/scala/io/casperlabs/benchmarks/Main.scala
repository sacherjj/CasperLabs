package io.casperlabs.benchmarks

import cats.Id
import cats.effect.{Sync, Timer}
import io.casperlabs.benchmarks.Options.Configuration
import io.casperlabs.benchmarks.Options.Configuration._
import io.casperlabs.catscontrib.effect.implicits.syncId
import io.casperlabs.client.{DeployService, GrpcDeployService}
import io.casperlabs.shared.{FilesAPI, Log, UncaughtExceptionHandler}
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import scala.concurrent.duration._
import logstage.IzLogger

object Main {
  val logger                  = Log.mkLogger()
  implicit val logId: Log[Id] = Log.log[Id](logger)
  implicit val log: Log[Task] = Log.useLogger[Task](logger)

  def main(args: Array[String]): Unit = {
    implicit val scheduler: Scheduler = Scheduler.io(
      "benchmarking-io",
      reporter = new UncaughtExceptionHandler(shutdownTimeout = 5.seconds),
      executionModel = ExecutionModel.SynchronousExecution
    )

    val exec =
      for {
        maybeConf <- Task(Configuration.parse(args))
        _ <- maybeConf.fold(Log[Task].error("Couldn't parse CLI args into configuration")) {
              case (conn, conf) =>
                implicit val deployService: GrpcDeployService =
                  new GrpcDeployService(conn, scheduler)
                implicit val filesAPI: FilesAPI[Task] = FilesAPI.create[Task]
                program[Task](conf).doOnFinish(_ => Task(deployService.close()))
            }
      } yield ()

    exec.runSyncUnsafe()
  }

  def program[F[_]: Sync: DeployService: Timer: FilesAPI: Log](
      configuration: Configuration
  ): F[Unit] =
    configuration match {
      case Benchmark(output, initialFundsAccountPrivateKey, initialFundsAccountPublicKey) =>
        Benchmarks.run[F](
          output,
          initialFundsAccountPrivateKey,
          initialFundsAccountPublicKey
        )
    }
}
