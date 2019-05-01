package io.casperlabs.client

import cats.effect.{Sync, Timer}
import io.casperlabs.casper.protocol
import io.casperlabs.client.configuration._
import io.casperlabs.shared.{Log, UncaughtExceptionLogger}
import monix.eval.Task
import monix.execution.Scheduler

object Main {

  implicit val log: Log[Task] = Log.log

  def main(args: Array[String]): Unit = {
    implicit val scheduler: Scheduler = Scheduler.computation(
      Math.max(java.lang.Runtime.getRuntime.availableProcessors(), 2),
      "node-runner",
      reporter = UncaughtExceptionLogger
    )

    val exec =
      for {
        maybeConf <- Task(Configuration.parse(args))
        _ <- maybeConf.fold(Log[Task].error("Couldn't parse CLI args into configuration")) { conf =>
              val deployService = new GrpcDeployService(
                conf.host,
                conf.port
              )
              program(conf)(Sync[Task], deployService, Timer[Task])
                .doOnFinish(_ => Task(deployService.close()))
            }
      } yield ()

    exec.runSyncUnsafe()
  }

  def program[F[_]: Sync: DeployService: Timer](
      configuration: Configuration
  ): F[Unit] =
    configuration match {
      case ShowBlock(_, _, hash)   => DeployRuntime.showBlock(hash)
      case ShowBlocks(_, _, depth) => DeployRuntime.showBlocks(depth)
      case Deploy(_, _, from, gasLimit, nonce, sessionCode, paymentCode) =>
        DeployRuntime.deployFileProgram(from, gasLimit, nonce, sessionCode, paymentCode)
      case _: Propose =>
        DeployRuntime.propose()
      case VisualizeDag(_, _, depth, showJustificationLines, out, streaming) =>
        DeployRuntime.visualizeDag(depth, showJustificationLines, out, streaming)

      case Query(_, _, hash, keyType, keyValue, path) =>
        DeployRuntime.gracefulExit(
          DeployService[F].queryState(protocol.QueryStateRequest(hash, keyType, keyValue, path))
        )
    }
}
