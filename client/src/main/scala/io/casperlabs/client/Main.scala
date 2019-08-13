package io.casperlabs.client

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.implicits._
import guru.nidi.graphviz.engine.{Format, Graphviz, GraphvizJdkEngine}
import io.casperlabs.client.configuration._
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.shared.{FilesAPI, Log, UncaughtExceptionHandler}
import monix.eval.Task
import monix.execution.Scheduler

object Main {

  implicit val log: Log[Task] = Log.log

  def main(args: Array[String]): Unit = {
    implicit val scheduler: Scheduler = Scheduler.io(
      "node-runner",
      reporter = UncaughtExceptionHandler
    )

    val exec =
      for {
        maybeConf <- Task(Configuration.parse(args))
        _ <- maybeConf.fold(Log[Task].error("Couldn't parse CLI args into configuration")) {
              case (conn, conf) =>
                implicit val deployService: GrpcDeployService =
                  new GrpcDeployService(conn, scheduler)
                implicit val filesAPI: FilesAPI[Task] = FilesAPI.create[Task]
                val graphvizWriteToFile = (file: File, format: Format, data: String) =>
                  Task(Graphviz.fromString(data).render(format).toFile(file))
                val deployRuntime = new DeployRuntime[Task](graphvizWriteToFile)
                Task(Graphviz.useEngine(new GraphvizJdkEngine)) >>
                  program[Task](deployRuntime, conf).doOnFinish(_ => Task(deployService.close()))
            }
      } yield ()

    exec.runSyncUnsafe()
  }

  def program[F[_]](
      deployRuntime: DeployRuntime[F],
      configuration: Configuration
  ): F[Unit] =
    configuration match {
      case ShowBlock(hash)   => deployRuntime.showBlock(hash)
      case ShowDeploy(hash)  => deployRuntime.showDeploy(hash)
      case ShowDeploys(hash) => deployRuntime.showDeploys(hash)
      case ShowBlocks(depth) => deployRuntime.showBlocks(depth)
      case Unbond(
          amount,
          nonce,
          contractCode,
          privateKey
          ) =>
        deployRuntime.unbond(
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
        deployRuntime.bond(
          amount,
          nonce,
          contractCode,
          privateKey
        )
      case Transfer(
          amount,
          recipientPublicKeyBase64,
          nonce,
          contractCode,
          privateKey
          ) =>
        deployRuntime.transferCLI(
          nonce,
          contractCode,
          privateKey,
          recipientPublicKeyBase64,
          amount
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
        deployRuntime.deploy(
          from,
          nonce,
          Files.readAllBytes(sessionCode.toPath),
          Files.readAllBytes(paymentCode.toPath),
          maybePublicKey.map(
            file =>
              new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8).asLeft[PublicKey]
          ),
          maybePrivateKey.map(
            file =>
              new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8).asLeft[PrivateKey]
          ),
          gasPrice
        )

      case Propose =>
        deployRuntime.propose()

      case VisualizeDag(depth, showJustificationLines, out, streaming) =>
        deployRuntime.visualizeDag(depth, showJustificationLines, out, streaming)

      case Query(hash, keyType, keyValue, path) =>
        deployRuntime.queryState(hash, keyType, keyValue, path)

      case Balance(address, blockHash) =>
        deployRuntime.balance(address, blockHash)

      case SetThresholds(keysManagement, deploys, nonce, privateKey, maybeSessionCode) =>
        deployRuntime.setAccountKeyThresholds(
          keysManagement,
          deploys,
          nonce,
          privateKey,
          maybeSessionCode
        )
      case AddKey(publicKey, weight, nonce, privateKey, maybeSessionCode) =>
        deployRuntime.addKey(publicKey, weight, nonce, privateKey, maybeSessionCode)
      case RemoveKey(publicKey, nonce, privateKey, maybeSessionCode) =>
        deployRuntime.removeKey(publicKey, nonce, privateKey, maybeSessionCode)
      case UpdateKey(publicKey, newWeight, nonce, privateKey, maybeSessionCode) =>
        deployRuntime.updateKey(publicKey, newWeight, nonce, privateKey, maybeSessionCode)
    }
}
