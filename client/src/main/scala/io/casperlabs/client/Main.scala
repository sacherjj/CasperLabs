package io.casperlabs.client

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.{Id, Parallel}
import cats.effect.{Sync, Timer}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.client.configuration._
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.shared.{FilesAPI, Log, UncaughtExceptionHandler}
import io.casperlabs.catscontrib.effect.implicits.syncId
import monix.eval.Task
import monix.execution.Scheduler
import logstage.IzLogger
import scala.concurrent.duration._

object Main {

  val logger                  = Log.mkLogger()
  implicit val log: Log[Task] = Log.useLogger[Task](logger)
  implicit val logId: Log[Id] = Log.log[Id](logger)

  def main(args: Array[String]): Unit = {
    implicit val scheduler: Scheduler = Scheduler.computation(
      Math.max(java.lang.Runtime.getRuntime.availableProcessors(), 2),
      "node-runner",
      reporter = new UncaughtExceptionHandler(shutdownTimeout = 5.seconds)
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

  def program[F[_]: Sync: DeployService: Timer: FilesAPI: Log: Parallel](
      configuration: Configuration
  ): F[Unit] =
    configuration match {
      case ShowBlock(hash, bytesStandard, json) =>
        DeployRuntime.showBlock[F](hash, bytesStandard, json)
      case ShowDeploy(hash, bytesStandard, json) =>
        DeployRuntime.showDeploy[F](hash, bytesStandard, json)
      case ShowDeploys(hash, bytesStandard, json) =>
        DeployRuntime.showDeploys[F](hash, bytesStandard, json)
      case ShowBlocks(depth, bytesStandard, json) =>
        DeployRuntime.showBlocks[F](depth, bytesStandard, json)
      case Unbond(
          amount,
          contracts,
          privateKey
          ) =>
        DeployRuntime.unbond[F](
          amount,
          contracts,
          privateKey
        )
      case Bond(
          amount,
          contracts,
          privateKey
          ) =>
        DeployRuntime.bond[F](
          amount,
          contracts,
          privateKey
        )
      case Transfer(
          amount,
          recipientPublicKey,
          contracts,
          privateKey
          ) =>
        DeployRuntime.transferCLI[F](
          contracts,
          privateKey,
          recipientPublicKey,
          amount
        )
      case Deploy(
          from,
          contracts,
          maybePublicKey,
          maybePrivateKey
          ) =>
        DeployRuntime.deployFileProgram[F](
          from,
          contracts,
          maybePublicKey.map(
            file =>
              new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8).asLeft[PublicKey]
          ),
          maybePrivateKey.map(
            file =>
              new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8).asLeft[PrivateKey]
          )
        )
      case MakeDeploy(
          from,
          publicKey,
          contracts,
          deployPath
          ) =>
        for {
          baseAccount <- publicKey match {
                          case None =>
                            // This should be safe because we validate that one of --from, --public-key is present.
                            from.get.pure[F]
                          case Some(publicKeyFile) =>
                            for {
                              content <- DeployRuntime.readFileAsString[F](publicKeyFile)
                              publicKey <- Sync[F].fromOption(
                                            Ed25519.tryParsePublicKey(content),
                                            new IllegalArgumentException(
                                              s"Failed to parse public key file ${publicKeyFile.getPath()}"
                                            )
                                          )
                            } yield publicKey
                        }
          deploy = DeployRuntime.makeDeploy[F](
            baseAccount,
            contracts,
            sessionArgs = Nil
          )
          _ <- DeployRuntime.writeDeploy[F](deploy, deployPath)
        } yield ()

      case SendDeploy(deploy) =>
        DeployRuntime.sendDeploy[F](deploy)

      case PrintDeploy(deploy, bytesStandard, json) =>
        DeployRuntime.printDeploy[F](deploy, bytesStandard, json)

      case Sign(deploy, signedDeployOut, publicKey, privateKey) =>
        DeployRuntime.sign[F](deploy, signedDeployOut, publicKey, privateKey)

      case Propose =>
        DeployRuntime.propose[F]()

      case VisualizeDag(depth, showJustificationLines, out, streaming) =>
        DeployRuntime.visualizeDag[F](depth, showJustificationLines, out, streaming)

      case Query(hash, keyType, keyValue, path, bytesStandard, json) =>
        DeployRuntime.queryState[F](hash, keyType, keyValue, path, bytesStandard, json)

      case Balance(address, blockHash) =>
        DeployRuntime.balance[F](address, blockHash)

      case Keygen(outputDirectory) =>
        DeployRuntime.keygen[F](outputDirectory)
    }
}
