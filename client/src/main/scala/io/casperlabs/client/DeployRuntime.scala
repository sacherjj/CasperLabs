package io.casperlabs.client
import java.io.File
import java.nio.file.Files

import cats.data.EitherT
import cats.{Apply, Monad}
import cats.effect.{Sync, Timer}
import cats.syntax.all._
import com.google.protobuf.ByteString
import guru.nidi.graphviz.engine._
import io.casperlabs.casper.protocol.{BlockQuery, BlocksQuery, DeployData, VisualizeDagQuery}
import io.casperlabs.client.configuration.Streaming

import scala.util.Try
import scala.concurrent.duration._
import scala.language.higherKinds

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

  def visualizeDag[F[_]: Sync: DeployService: Timer](
      depth: Int,
      showJustificationLines: Boolean,
      maybeOut: Option[String],
      maybeStreaming: Option[Streaming]
  ): F[Unit] =
    gracefulExit({
      def askDag =
        DeployService[F]
          .visualizeDag(VisualizeDagQuery(depth, showJustificationLines))
          .rethrow

      val useJdkRenderer = Sync[F].delay(Graphviz.useEngine(new GraphvizJdkEngine))

      def writeToFile(out: String, format: Format, dag: String) =
        Sync[F].delay(
          Graphviz
            .fromString(dag)
            .render(format)
            .toFile(new File(s"$out"))
        ) >> Sync[F].delay(println(s"Wrote $out"))

      val sleep = Timer[F].sleep(5.seconds)

      def subscribe(
          out: String,
          streaming: Streaming,
          format: Format,
          index: Int = 0,
          prevDag: Option[String] = None
      ): F[Unit] =
        askDag >>= {
          dag =>
            if (prevDag.contains(dag)) {
              sleep >>
                subscribe(out, streaming, format, index, prevDag)
            } else {
              val f = format.name().toLowerCase
              val filename = streaming match {
                case Streaming.Single => out
                case Streaming.Multiple =>
                  val extension = "." + out.split('.').last
                  out.stripSuffix(extension) + s"_$index" + extension
              }
              writeToFile(filename, format, dag) >>
                sleep >>
                subscribe(out, streaming, format, index + 1, dag.some)
            }
        }

      def parseFormat(out: String) = Sync[F].delay(Format.valueOf(out.split('.').last.toUpperCase))

      val eff = (maybeOut, maybeStreaming) match {
        case (None, None) =>
          askDag
        case (Some(out), None) =>
          useJdkRenderer >>
            askDag >>= { dag =>
            parseFormat(out) >>=
              (format => writeToFile(out, format, dag).map(_ => "Success"))
          }
        case (Some(out), Some(streaming)) =>
          useJdkRenderer >>
            parseFormat(out) >>=
            (subscribe(out, streaming, _).map(_ => "Success"))
        case (None, Some(_)) =>
          Sync[F].raiseError[String](new Throwable("--out must be specified if --stream"))
      }
      eff.attempt
    })

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

  private def gracefulExit[F[_]: Sync](program: F[Either[Throwable, String]]): F[Unit] =
    for {
      result <- Sync[F].attempt(program)
      _ <- result.joinRight match {
            case Left(ex) =>
              Sync[F].delay {
                System.err.println(processError(ex).getMessage)
                System.exit(1)
              }
            case Right(msg) => Sync[F].delay(println(msg))
          }
    } yield ()

  private def processError(t: Throwable): Throwable =
    Option(t.getCause).getOrElse(t)

}
