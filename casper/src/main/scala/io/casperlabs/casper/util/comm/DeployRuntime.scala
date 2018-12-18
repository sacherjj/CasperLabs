package io.casperlabs.casper.util.comm

import java.nio.file.{Files, Paths}

import cats.effect.Sync
import cats.implicits._
import cats.{Apply, Monad}
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.shared.Time

import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util._

object DeployRuntime {

  def propose[F[_]: Monad: Sync: DeployService](): F[Unit] =
    gracefulExit(
      for {
        response <- DeployService[F].createBlock()
      } yield response.map(r => s"Response: $r")
    )

  def showBlock[F[_]: Monad: Sync: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showBlock(BlockQuery(hash)))

  def showBlocks[F[_]: Monad: Sync: DeployService](depth: Int): F[Unit] =
    gracefulExit(DeployService[F].showBlocks(BlocksQuery(depth)))

  def deployFileProgram[F[_]: Monad: Sync: DeployService](
      purseAddress: String,
      gasLimit: Long,
      gasPrice: Long,
      nonce: Long,
      sessionsCodeFile: String,
      paymentCodeFile: String
  ): F[Unit] = {
    def readFile(filename: String) =
      Sync[F].fromTry(
        Try(ByteString.copyFrom(Files.readAllBytes(Paths.get(filename))))
      )

    gracefulExit(
      Apply[F]
        .map2(
          readFile(sessionsCodeFile),
          readFile(paymentCodeFile)
        ) {
          case (sessionCode, paymentCode) =>
            //TODO: allow user to specify their public key
            DeployData()
              .withTimestamp(System.currentTimeMillis())
              .withSessionCode(sessionCode)
              .withPaymentCode(paymentCode)
              .withAddress(ByteString.copyFromUtf8(purseAddress))
              .withGasLimit(gasLimit)
              .withGasPrice(gasPrice)
              .withNonce(nonce)
        }
        .flatMap(DeployService[F].deploy)
        .handleError(
          ex => Left(new RuntimeException(s"Couldn't make deploy, reason: ${ex.getMessage}", ex))
        )
    )
  }

  //Simulates user requests by randomly deploying things to Casper.
  def deployDemoProgram[F[_]: Monad: Sync: Time: DeployService]: F[Unit] =
    singleDeploy[F].forever

  private def singleDeploy[F[_]: Monad: Time: Sync: DeployService]: F[Unit] =
    for {
      id <- Sync[F].delay { scala.util.Random.nextInt(100) }
      d  <- ProtoUtil.basicDeployData[F](id)
      _ <- Sync[F].delay {
            println(
              s"Sending the demo deploy to Casper."
            )
          }
      response <- DeployService[F].deploy(d)
      msg      = response.fold(processError(_).getMessage, "Response: " + _)
      _        <- Sync[F].delay(println(msg))
      _        <- Time[F].sleep(4.seconds)
    } yield ()

  private def gracefulExit[F[_]: Monad: Sync, A](program: F[Either[Throwable, String]]): F[Unit] =
    for {
      result <- Sync[F].attempt(program)
      _ <- result.joinRight match {
            case Left(ex) =>
              Sync[F].delay {
                println(processError(ex).getMessage)
                System.exit(1)
              }
            case Right(msg) => Sync[F].delay(println(msg))
          }
    } yield ()

  private def processError(t: Throwable): Throwable =
    Option(t.getCause).getOrElse(t)

}
