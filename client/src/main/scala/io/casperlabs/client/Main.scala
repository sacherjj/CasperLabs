package io.casperlabs.client

import cats.effect.{Sync, Timer}
import cats.syntax.functor._
import cats.syntax.flatMap._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.client.configuration._
import io.casperlabs.ipc
import io.casperlabs.casper.protocol
import io.casperlabs.shared.{Log, LogSource, UncaughtExceptionLogger}
import monix.eval.Task
import monix.execution.Scheduler

object Main {

  implicit val logSource: LogSource = LogSource(this.getClass)
  implicit val log: Log[Task]       = Log.log

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
      case Deploy(_, _, from, gasLimit, gasPrice, nonce, sessionCode, paymentCode) =>
        DeployRuntime.deployFileProgram(from, gasLimit, gasPrice, nonce, sessionCode, paymentCode)
      case _: Propose =>
        DeployRuntime.propose()
      case VisualizeDag(_, _, depth, showJustificationLines, out, streaming) =>
        DeployRuntime.visualizeDag(depth, showJustificationLines, out, streaming)

      case Query(_, _, hash, keyType, keyValue, path) =>
        val keyBytes = ByteString.copyFrom(Base16.decode(keyValue))
        val key = keyType.toLowerCase match {
          case "hash" =>
            ipc.Key(ipc.Key.KeyInstance.Hash(ipc.KeyHash(keyBytes)))
          case "uref" =>
            ipc.Key(ipc.Key.KeyInstance.Uref(ipc.KeyURef(keyBytes)))
          case "address" =>
            ipc.Key(ipc.Key.KeyInstance.Account(ipc.KeyAddress(keyBytes)))
        }
        DeployRuntime.gracefulExit(
          DeployService[F].queryState(protocol.QueryStateRequest(hash, key.toByteString, path))
        )
    }
}
