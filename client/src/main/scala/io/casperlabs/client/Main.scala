package io.casperlabs.client

import java.io.FileInputStream

import cats.effect.{Sync, Timer}
import io.casperlabs.client.configuration._
import io.casperlabs.shared.{Log, UncaughtExceptionHandler}
import monix.eval.Task
import monix.execution.Scheduler

object Main {

  implicit val log: Log[Task] = Log.log

  def main(args: Array[String]): Unit = {
    implicit val scheduler: Scheduler = Scheduler.computation(
      Math.max(java.lang.Runtime.getRuntime.availableProcessors(), 2),
      "node-runner",
      reporter = UncaughtExceptionHandler
    )

    val exec =
      for {
        maybeConf <- Task(Configuration.parse(args))
        _ <- maybeConf.fold(Log[Task].error("Couldn't parse CLI args into configuration")) {
              case (conn, conf) =>
                val deployService = new GrpcDeployService(conn)
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
      case ShowBlock(hash)   => DeployRuntime.showBlock(hash)
      case ShowDeploy(hash)  => DeployRuntime.showDeploy(hash)
      case ShowDeploys(hash) => DeployRuntime.showDeploys(hash)
      case ShowBlocks(depth) => DeployRuntime.showBlocks(depth)
      case Unbond(
          amount,
          nonce,
          contractCode,
          privateKey
          ) =>
        DeployRuntime.unbond(
          amount,
          nonce,
          contractCode,
          privateKey
        )
      case Bond(
          amount,
          nonce,
          contractCode,
          privateKey
          ) =>
        DeployRuntime.bond(
          amount,
          nonce,
          contractCode,
          privateKey
        )
      case Deploy(
          from,
          nonce,
          sessionCode,
          paymentCode,
          maybePublicKey,
          maybePrivateKey,
          gasPrice
          ) =>
        DeployRuntime.deployFileProgram(
          from,
          nonce,
          new FileInputStream(sessionCode),
          new FileInputStream(paymentCode),
          maybePublicKey,
          maybePrivateKey,
          gasPrice
        )

      case Propose =>
        DeployRuntime.propose()

      case VisualizeDag(depth, showJustificationLines, out, streaming) =>
        DeployRuntime.visualizeDag(depth, showJustificationLines, out, streaming)

      case Query(hash, keyType, keyValue, path) =>
        DeployRuntime.queryState(hash, keyType, keyValue, path)
    }
}
