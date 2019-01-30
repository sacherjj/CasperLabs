package io.casperlabs.client
import java.io.File
import java.nio.file.Files

import cats.{Apply, Monad}
import cats.effect.Sync
import cats.syntax.all._
import com.google.protobuf.ByteString
import io.casperlabs.casper.protocol.{BlockQuery, BlocksQuery, DeployData}

import scala.util.Try

object DeployRuntime {

  def propose[F[_]: Sync: DeployService](): F[Unit] =
    gracefulExit(
      for {
        response <- DeployService[F].createBlock()
      } yield response.map(r => s"Response: $r")
    )

  def showBlock[F[_]: Sync: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showBlock(BlockQuery(hash)))

  def showBlocks[F[_]: Sync: DeployService](depth: Int): F[Unit] =
    gracefulExit(DeployService[F].showBlocks(BlocksQuery(depth)))

  def visualizeBlocks[F[_]: Monad: Sync: DeployService](depth: Int): F[Unit] =
    gracefulExit(DeployService[F].visualizeBlocks(BlocksQuery(depth)))

  def deployFileProgram[F[_]: Sync: DeployService](
      from: String,
      gasLimit: Long,
      gasPrice: Long,
      nonce: Long,
      sessionCode: File,
      paymentCode: File
  ): F[Unit] = {
    def readFile(file: File) =
      Sync[F].fromTry(
        Try(ByteString.copyFrom(Files.readAllBytes(file.toPath)))
      )
    gracefulExit(
      Apply[F]
        .map2(
          readFile(sessionCode),
          readFile(paymentCode)
        ) {
          case (session, payment) =>
            //TODO: allow user to specify their public key
            DeployData()
              .withTimestamp(System.currentTimeMillis())
              .withSessionCode(session)
              .withPaymentCode(payment)
              .withAddress(ByteString.copyFromUtf8(from))
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

  private def gracefulExit[F[_]: Sync, A](program: F[Either[Throwable, String]]): F[Unit] =
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
