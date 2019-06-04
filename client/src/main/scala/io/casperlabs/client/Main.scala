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
        _ <- maybeConf.fold(Log[Task].error("Couldn't parse CLI args into configuration")) {
              case (conn, conf) =>
                val deployService = new GrpcDeployService(
                  conn.host,
                  conn.portExternal,
                  conn.portInternal
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
      case ShowBlock(hash) => DeployRuntime.showBlock(hash)

      case ShowBlocks(depth) => DeployRuntime.showBlocks(depth)

      case Deploy(from, nonce, sessionCode, paymentCode, maybePublicKey, maybePrivateKey) =>
        DeployRuntime.deployFileProgram(
          from,
          nonce,
          sessionCode,
          paymentCode,
          maybePublicKey,
          maybePrivateKey
        )

      case Propose =>
        DeployRuntime.propose()

      case VisualizeDag(depth, showJustificationLines, out, streaming) =>
        DeployRuntime.visualizeDag(depth, showJustificationLines, out, streaming)

      case Query(hash, keyType, keyValue, path) =>
        DeployRuntime.queryState(hash, keyType, keyValue, path)
    }
}
