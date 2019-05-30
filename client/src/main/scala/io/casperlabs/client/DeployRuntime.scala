package io.casperlabs.client
import java.io.File
import java.nio.file.Files

import cats.Apply
import cats.effect.{Sync, Timer}
import cats.syntax.all._
import com.google.protobuf.ByteString
import guru.nidi.graphviz.engine._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.consensus
import io.casperlabs.client.configuration.Streaming
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.Base16
import java.nio.charset.StandardCharsets
import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.Try

object DeployRuntime {

  def propose[F[_]: Sync: DeployService](): F[Unit] =
    gracefulExit(
      for {
        response <- DeployService[F].propose()
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
      nonce: Long,
      sessionCode: File,
      paymentCode: File,
      maybePublicKeyFile: Option[File],
      maybePrivateKeyFile: Option[File]
  ): F[Unit] = {
    def readFile(file: File): F[ByteString] =
      Sync[F].fromTry(
        Try(ByteString.copyFrom(Files.readAllBytes(file.toPath)))
      )

    def readFileAsString(file: File): F[String] =
      for {
        raw <- readFile(file)
        str = new String(raw.toByteArray, StandardCharsets.UTF_8)
      } yield str

    val deploy = for {
      session <- readFile(sessionCode)
      payment <- readFile(paymentCode)
      maybePrivateKey <- {
        maybePrivateKeyFile.fold(none[PrivateKey].pure[F]) { file =>
          readFileAsString(file).map(Ed25519.tryParsePrivateKey)
        }
      }
      maybePublicKey <- {
        maybePublicKeyFile.map { file =>
          // In the future with multiple signatures and recovery the
          // account public key  can be different then the private key
          // we sign with, so not checking that they match.
          readFileAsString(file).map(Ed25519.tryParsePublicKey)
        } getOrElse {
          maybePrivateKey.flatMap(Ed25519.tryToPublic).pure[F]
        }
      }
    } yield {
      val deploy = consensus
        .Deploy()
        .withHeader(
          consensus.Deploy
            .Header()
            .withTimestamp(System.currentTimeMillis)
            .withAccountPublicKey(
              // Allowing --from until we explicitly require signing.
              // It's an account address but there's no other field to carry it.
              maybePublicKey.map(ByteString.copyFrom(_)) getOrElse ByteString.copyFromUtf8(from)
            )
            .withNonce(nonce)
        )
        .withBody(
          consensus.Deploy
            .Body()
            .withSession(consensus.Deploy.Code().withCode(session))
            .withPayment(consensus.Deploy.Code().withCode(payment))
        )
        .withHashes

      maybePrivateKey.map(deploy.sign) getOrElse deploy
    }

    gracefulExit(
      deploy
        .flatMap(DeployService[F].deploy)
        .handleError(
          ex => Left(new RuntimeException(s"Couldn't make deploy, reason: ${ex.getMessage}", ex))
        )
    )
  }

  private[client] def gracefulExit[F[_]: Sync](program: F[Either[Throwable, String]]): F[Unit] =
    for {
      result <- Sync[F].attempt(program)
      _ <- result.joinRight match {
            case Left(ex) =>
              Sync[F].delay {
                System.err.println(processError(ex).getMessage)
                System.exit(1)
              }
            case Right(msg) =>
              Sync[F].delay {
                println(msg)
                System.exit(0)
              }
          }
    } yield ()

  private def processError(t: Throwable): Throwable =
    Option(t.getCause).getOrElse(t)

  private def hash[T <: scalapb.GeneratedMessage](data: T): ByteString =
    ByteString.copyFrom(Blake2b256.hash(data.toByteArray))

  implicit class DeployOps(d: consensus.Deploy) {
    def withHashes = {
      val h = d.getHeader.withBodyHash(hash(d.getBody))
      d.withHeader(h).withDeployHash(hash(h))
    }

    def sign(privateKey: PrivateKey) = {
      val sig = Ed25519.sign(d.deployHash.toByteArray, privateKey)
      d.withApprovals(
        List(
          consensus
            .Approval()
            .withApproverPublicKey(d.getHeader.accountPublicKey)
            .withSignature(
              consensus
                .Signature()
                .withSigAlgorithm(Ed25519.name)
                .withSig(ByteString.copyFrom(sig))
            )
        )
      )
    }
  }

}
