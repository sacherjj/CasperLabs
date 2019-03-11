package io.casperlabs.node

import cats.effect.ExitCode
import cats.implicits._
import io.casperlabs.catscontrib._
import io.casperlabs.comm._
import io.casperlabs.node.configuration.Configuration.Command.{Diagnostics, Run}
import io.casperlabs.node.configuration._
import io.casperlabs.node.diagnostics.client.GrpcDiagnosticsService
import io.casperlabs.node.effects._
import io.casperlabs.shared._
import monix.eval.{Task, TaskApp}
import monix.execution.Scheduler

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object Main extends TaskApp {

  private implicit lazy val logSource: LogSource = LogSource(this.getClass)
  private implicit lazy val log: Log[Task]       = effects.log

  override def run(args: List[String]): Task[ExitCode] = {
    implicit val scheduler: Scheduler = Scheduler.computation(
      Math.max(java.lang.Runtime.getRuntime.availableProcessors(), 2),
      "node-runner",
      reporter = UncaughtExceptionLogger
    )

    val exec: Task[Unit] =
      for {
        conf <- Task(Configuration.parse(args.toArray, sys.env))
        _ <- conf
              .fold(
                errors => log.error(errors.mkString_("", "\n", "")),
                conf => updateLoggingProps(conf) >> mainProgram(conf)
              )
      } yield ()

    exec.as(ExitCode(0))
  }

  private def updateLoggingProps(conf: Configuration): Task[Unit] = Task {
    sys.props.update("node.data.dir", conf.server.dataDir.toAbsolutePath.toString)
  }

  private def mainProgram(conf: Configuration)(implicit scheduler: Scheduler): Task[Unit] = {
    implicit val diagnosticsService: GrpcDiagnosticsService =
      new diagnostics.client.GrpcDiagnosticsService(
        conf.grpc.host,
        conf.grpc.portInternal,
        conf.server.maxMessageSize
      )

    implicit val consoleIO: ConsoleIO[Task] = (str: String) => Task(println(str))

    val program = conf.command match {
      case Diagnostics => diagnostics.client.Runtime.diagnosticsProgram[Task]
      case Run         => nodeProgram(conf)
    }

    program
      .guarantee {
        Task.delay(diagnosticsService.close())
      }
      .doOnFinish {
        case Some(ex) =>
          log.error(ex.getMessage, ex) *>
            Task
              .delay(System.exit(1))
              .delayExecution(500.millis) // A bit of time for logs to flush.

        case None =>
          Task.delay(System.exit(0))
      }

  }

  private def nodeProgram(conf: Configuration)(implicit scheduler: Scheduler): Task[Unit] = {
    val node =
      for {
        _       <- log.info(VersionInfo.get).toEffect
        runtime <- NodeRuntime(conf)
        _       <- runtime.main
      } yield ()

    // Return an error for logging and exit code to be done in `mainProgram`.
    def raise(msg: String) =
      Task.raiseError(new Exception(msg) with NoStackTrace)

    node.value >>= {
      case Right(_) =>
        Task.unit
      case Left(CouldNotConnectToBootstrap) =>
        raise("Node could not connect to bootstrap node.")
      case Left(InitializationError(msg)) =>
        raise(msg)
      case Left(error) =>
        raise(s"Failed! Reason: '$error")
    }
  }
}
