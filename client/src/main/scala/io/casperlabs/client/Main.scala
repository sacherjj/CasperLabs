package io.casperlabs.client

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.effect.{Sync, Timer}
import cats.implicits._
import cats.temp.par._
import com.google.protobuf.ByteString
import io.casperlabs.client.configuration._
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.shared.{FilesAPI, Log, UncaughtExceptionHandler}
import monix.eval.Task
import monix.execution.Scheduler
import scala.concurrent.duration._

object Main {

  implicit val log: Log[Task] = Log.log

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

  def program[F[_]: Sync: DeployService: Timer: FilesAPI: Log: Par](
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
          contracts,
          privateKey
          ) =>
        DeployRuntime.unbond(
          amount,
          nonce,
          contracts,
          privateKey
        )
      case Bond(
          amount,
          nonce,
          contracts,
          privateKey
          ) =>
        DeployRuntime.bond(
          amount,
          nonce,
          contracts,
          privateKey
        )
      case Transfer(
          amount,
          recipientPublicKeyBase64,
          nonce,
          contracts,
          privateKey
          ) =>
        DeployRuntime.transferCLI(
          nonce,
          contracts,
          privateKey,
          recipientPublicKeyBase64,
          amount
        )
      case Deploy(
          from,
          nonce,
          contracts,
          maybePublicKey,
          maybePrivateKey,
          gasPrice
          ) =>
        DeployRuntime.deployFileProgram(
          from,
          nonce,
          contracts,
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
      case MakeDeploy(
          from,
          publicKey,
          nonce,
          contracts,
          gasPrice,
          deployPath
          ) =>
        for {
          baseAccount <- publicKey match {
                          case None =>
                            // This should be safe because we validate that one of --from, --public-key is present.
                            ByteString.copyFrom(Base16.decode(from.get)).pure[F]
                          case Some(publicKeyFile) =>
                            for {
                              content <- DeployRuntime.readFileAsString[F](publicKeyFile)
                              publicKey <- Sync[F].fromOption(
                                            Ed25519.tryParsePublicKey(content),
                                            new IllegalArgumentException(
                                              s"Failed to parse public key file ${publicKeyFile.getPath()}"
                                            )
                                          )
                            } yield ByteString.copyFrom(publicKey)
                        }
          deploy = DeployRuntime.makeDeploy(
            baseAccount,
            nonce,
            gasPrice,
            contracts,
            Array.emptyByteArray
          )
          _ <- DeployRuntime.writeDeploy(deploy, deployPath)
        } yield ()

      case SendDeploy(deploy) =>
        DeployRuntime.sendDeploy(deploy)

      case Sign(deploy, signedDeployOut, publicKey, privateKey) =>
        DeployRuntime.sign(deploy, signedDeployOut, publicKey, privateKey)

      case Propose =>
        DeployRuntime.propose()

      case VisualizeDag(depth, showJustificationLines, out, streaming) =>
        DeployRuntime.visualizeDag(depth, showJustificationLines, out, streaming)

      case Query(hash, keyType, keyValue, path) =>
        DeployRuntime.queryState(hash, keyType, keyValue, path)

      case Balance(address, blockHash) =>
        DeployRuntime.balance(address, blockHash)
    }
}
