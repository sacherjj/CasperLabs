package io.casperlabs.benchmarks
import cats.effect.{ExitCode, Timer}
import cats.temp.par._
import io.casperlabs.benchmarks.Options.Configuration
import io.casperlabs.benchmarks.Options.Configuration._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.client.{DeployRuntime, DeployService, GrpcDeployService}
import io.casperlabs.shared.{FilesAPI, Log, UncaughtExceptionHandler}
import monix.eval.{Task, TaskApp}
import monix.execution.Scheduler

object Main {
  implicit val log: Log[Task] = Log.log

  def main(args: Array[String]): Unit = {
    implicit val scheduler: Scheduler = Scheduler.computation(
      Math.max(java.lang.Runtime.getRuntime.availableProcessors(), 4),
      "node-runner",
      reporter = UncaughtExceptionHandler
    )

    val exec = for {
      maybeConf <- Task(Configuration.parse(args))
      _ <- maybeConf.fold(Log[Task].error("Couldn't parse CLI args into configuration")) {
            case (conn, conf) =>
              implicit val deployService: GrpcDeployService = new GrpcDeployService(conn, scheduler)
              implicit val filesAPI: FilesAPI[Task]         = FilesAPI.create[Task]
              val deployRuntime                             = new DeployRuntime[Task]((_, _, _) => Task.unit)
              program[Task](deployRuntime, conf).doOnFinish(_ => Task(deployService.close()))
          }
    } yield ()

    exec.runSyncUnsafe()
  }

  def program[F[_]: MonadThrowable: DeployService: Timer: FilesAPI: Log: Par](
      deployRuntime: DeployRuntime[F],
      configuration: Configuration
  ): F[Unit] =
    configuration match {
      case Benchmark(output, initialFundsAccountPrivateKey, maybeTransferContract) =>
        Benchmarks.run[F](
          deployRuntime,
          output,
          initialFundsAccountPrivateKey,
          maybeTransferContract
        )
    }
}
