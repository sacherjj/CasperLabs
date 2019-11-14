package io.casperlabs.node

import cats.implicits._
import io.casperlabs.catscontrib._
import io.casperlabs.comm._
import io.casperlabs.ipc.ChainSpec
import io.casperlabs.node.configuration.Configuration.Command.{Diagnostics, Run}
import io.casperlabs.node.configuration._
import io.casperlabs.node.diagnostics.client.GrpcDiagnosticsService
import io.casperlabs.node.effects._
import io.casperlabs.shared._
import monix.eval.Task
import monix.execution.Scheduler
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object Main {

  //implicit val log: Log[Task] = effects.log

  implicit val uncaughtExceptionHandler = new UncaughtExceptionHandler(shutdownTimeout = 1.minute)

  def main(args: Array[String]): Unit =
    Configuration
      .parse(args, sys.env)
      .andThen({
        case (command, configuration) =>
          ChainSpecReader
            .fromConf(configuration)
            .map(chainSpec => (command, configuration, chainSpec))
      })
      .fold(
        errors => println(errors.mkString_("", "\n", "")), {
          case (command, conf, chainSpec) =>
            // Create a scheduler to execute the program and block waiting on it to finish.
            implicit val scheduler: Scheduler = Scheduler.forkJoin(
              parallelism = Math.max(java.lang.Runtime.getRuntime.availableProcessors(), 4),
              // We could move this to config, but NodeRuntime creates even more.
              // Let's see if it helps with the issue we see in long term tests where
              // block processing just stops at some point.
              maxThreads = 64,
              name = "node-runner",
              reporter = uncaughtExceptionHandler
            )

            val exec = updateLoggingProps(conf) >> mainProgram(command, conf, chainSpec)

            // This uses Scala `blocking` under the hood, so make sure the thread pool we use supports it.
            exec.runSyncUnsafe()
        }
      )

  private def updateLoggingProps(conf: Configuration): Task[Unit] = Task {
    //https://github.com/grpc/grpc-java/issues/1577#issuecomment-228342706
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    sys.props.update("node.data.dir", conf.server.dataDir.toAbsolutePath.toString)
  }

  private def mainProgram(
      command: Configuration.Command,
      conf: Configuration,
      chainSpec: ChainSpec
  )(
      implicit scheduler: Scheduler
  ): Task[Unit] = {
    implicit val diagnosticsService: GrpcDiagnosticsService =
      new diagnostics.client.GrpcDiagnosticsService(
        conf.server.host.getOrElse("localhost"),
        conf.grpc.portInternal,
        conf.server.maxMessageSize
      )

    implicit val consoleIO: ConsoleIO[Task] = (str: String) => Task(println(str))

    val program = command match {
      case Diagnostics => diagnostics.client.Runtime.diagnosticsProgram[Task]
      case Run         => nodeProgram(conf, chainSpec)
    }

    program
      .guarantee {
        Task.delay(diagnosticsService.close())
      }
      .doOnFinish {
        case Some(ex) =>
          log.error(s"Unexpected error: $ex") *>
            Task
              .delay(System.exit(1))
              .delayExecution(500.millis) // A bit of time for logs to flush.

        case None =>
          Task.delay(System.exit(0))
      }
  }

  private def nodeProgram(conf: Configuration, chainSpec: ChainSpec)(
      implicit scheduler: Scheduler
  ): Task[Unit] = {
    implicit val log = Log.useLogger[Task] {
      Log.mkLogger(level = conf.log.level, jsonPath = conf.log.jsonPath)
    }
    val node =
      for {
        _       <- log.info(s"${api.VersionInfo.get -> "version" -> null}")
        runtime <- NodeRuntime(conf, chainSpec)
        _       <- runtime.main
      } yield ()

    // Return an error for logging and exit code to be done in `mainProgram`.
    def raise(msg: String) =
      Task.raiseError(new Exception(msg) with NoStackTrace)

    node.attempt >>= {
      case Right(_) =>
        Task.unit
      case Left(error) =>
        raise(s"Failed! Reason: $error")
    }
  }
}
