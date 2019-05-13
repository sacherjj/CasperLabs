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
import monix.eval.Task
import monix.execution.Scheduler
import org.slf4j.bridge.SLF4JBridgeHandler

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object Main {

  implicit val log: Log[Task] = effects.log

  def main(args: Array[String]): Unit = {
    implicit val scheduler: Scheduler = Scheduler.computation(
      Math.max(java.lang.Runtime.getRuntime.availableProcessors(), 2),
      "node-runner",
      reporter = UncaughtExceptionLogger
    )

    val exec: Task[Unit] =
      for {
        commandAndConf <- Task(Configuration.parse(args.toArray, sys.env))
        _ <- commandAndConf
              .fold(
                errors => log.error(errors.mkString_("", "\n", "")),
                { case (command, conf) => updateLoggingProps(conf) >> mainProgram(command, conf) }
              )
      } yield ()

    exec.runSyncUnsafe()
  }

  private def updateLoggingProps(conf: Configuration): Task[Unit] = Task {
    //https://github.com/grpc/grpc-java/issues/1577#issuecomment-228342706
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    sys.props.update("node.data.dir", conf.server.dataDir.toAbsolutePath.toString)
  }

  private def mainProgram(command: Configuration.Command, conf: Configuration)(
      implicit scheduler: Scheduler
  ): Task[Unit] = {
    implicit val diagnosticsService: GrpcDiagnosticsService =
      new diagnostics.client.GrpcDiagnosticsService(
        conf.grpc.host,
        conf.grpc.portInternal,
        conf.server.maxMessageSize
      )

    implicit val consoleIO: ConsoleIO[Task] = (str: String) => Task(println(str))

    val program = command match {
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
        _       <- log.info(api.VersionInfo.get).toEffect
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
