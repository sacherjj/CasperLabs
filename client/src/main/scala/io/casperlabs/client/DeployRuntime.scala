package io.casperlabs.client
import java.io._
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.Show
import cats.effect.{Sync, Timer}
import cats.implicits._
import com.google.protobuf.ByteString
import guru.nidi.graphviz.engine._
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.consensus.state
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.client.configuration.{DeployConfig, Streaming}
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.{Base16, Base64}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.shared.FilesAPI
import org.apache.commons.io._

import scala.concurrent.duration._
import scala.language.higherKinds
import scala.util.Try

object DeployRuntime {

  val BONDING_WASM_FILE   = "bonding.wasm"
  val UNBONDING_WASM_FILE = "unbonding.wasm"
  val TRANSFER_WASM_FILE  = "transfer_to_account.wasm"
  val PAYMENT_WASM_FILE   = "standard_payment.wasm"

  def propose[F[_]: Sync: DeployService](
      exit: Boolean = true,
      ignoreOutput: Boolean = false
  ): F[Unit] =
    gracefulExit(
      DeployService[F]
        .propose()
        .map(_.map(hash => s"Response: Success! Block $hash created and added.")),
      exit,
      ignoreOutput
    )

  def showBlock[F[_]: Sync: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showBlock(hash).map(_.map(Printer.printToUnicodeString)))

  def showDeploys[F[_]: Sync: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showDeploys(hash))

  def showDeploy[F[_]: Sync: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showDeploy(hash))

  def showBlocks[F[_]: Sync: DeployService](depth: Int): F[Unit] =
    gracefulExit(DeployService[F].showBlocks(depth))

  private def optionalArg[T](name: String, maybeValue: Option[T])(
      f: T => Deploy.Arg.Value.Value
  ) = {
    val value: Deploy.Arg.Value.Value = maybeValue match {
      case None    => Deploy.Arg.Value.Value.Empty
      case Some(x) => f(x)
    }
    Deploy
      .Arg(name)
      .withValue(
        Deploy.Arg.Value().withOptionalValue(Deploy.Arg.Value(value))
      )
  }

  private def arg[T](name: String, value: Deploy.Arg.Value.Value) =
    Deploy
      .Arg(name)
      .withValue(Deploy.Arg.Value(value))

  private def longArg(name: String, value: Long) =
    arg(name, Deploy.Arg.Value.Value.LongValue(value))

  private def bigIntArg(name: String, value: BigInt) =
    arg(name, Deploy.Arg.Value.Value.BigInt(state.BigInt(value.toString, bitWidth = 512)))

  private def bytesArg(name: String, value: Array[Byte]) =
    arg(name, Deploy.Arg.Value.Value.BytesValue(ByteString.copyFrom(value)))

  // This is true for any array but I didn't want to go as far as writing type classes.
  // Binary format of an array is constructed from:
  // length (u32/integer in binary) ++ bytes
  private def serializeArray(ba: Array[Byte]): Array[Byte] = {
    val serLen = serializeInt(ba.length)
    serLen ++ ba
  }

  private def serializeInt(i: Int): Array[Byte] =
    java.nio.ByteBuffer
      .allocate(4)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putInt(i)
      .array()

  def unbond[F[_]: Sync: DeployService](
      maybeAmount: Option[Long],
      deployConfig: DeployConfig,
      privateKeyFile: File
  ): F[Unit] =
    for {
      rawPrivateKey <- readFileAsString[F](privateKeyFile)
      _ <- deployFileProgram[F](
            from = None,
            deployConfig.withSessionResource(UNBONDING_WASM_FILE),
            maybeEitherPublicKey = None,
            maybeEitherPrivateKey = rawPrivateKey.asLeft[PrivateKey].some,
            sessionArgs =
              List(optionalArg("amount", maybeAmount)(Deploy.Arg.Value.Value.LongValue(_)))
          )
    } yield ()

  def bond[F[_]: Sync: DeployService](
      amount: Long,
      deployConfig: DeployConfig,
      privateKeyFile: File
  ): F[Unit] =
    for {
      rawPrivateKey <- readFileAsString[F](privateKeyFile)
      _ <- deployFileProgram[F](
            from = None,
            deployConfig.withSessionResource(BONDING_WASM_FILE),
            maybeEitherPublicKey = None,
            maybeEitherPrivateKey = rawPrivateKey.asLeft[PrivateKey].some,
            sessionArgs = List(longArg("amount", amount))
          )
    } yield ()

  def queryState[F[_]: Sync: DeployService](
      blockHash: String,
      keyVariant: String,
      keyValue: String,
      path: String
  ): F[Unit] =
    gracefulExit(
      DeployService[F]
        .queryState(blockHash, keyVariant, keyValue, path)
        .map(_.map(Printer.printToUnicodeString(_)))
    )

  def balance[F[_]: Sync: DeployService](address: String, blockHash: String): F[Unit] =
    gracefulExit {
      (for {
        value <- DeployService[F].queryState(blockHash, "address", address, "").rethrow
        _ <- Sync[F]
              .raiseError(new IllegalStateException(s"Expected Account type value under $address."))
              .whenA(!value.value.isAccount)
        account = value.getAccount
        mintPublic <- Sync[F].fromOption(
                       account.knownUrefs.find(_.name == "mint").flatMap(_.key),
                       new IllegalStateException(
                         "Account's known_urefs map did not contain Mint contract address."
                       )
                     )
        localKeyValue = {
          val mintPublicHex = Base16.encode(mintPublic.getUref.uref.toByteArray) // Assuming that `mintPublic` is of `URef` type.
          val purseAddrHex = {
            val purseAddr    = account.getPurseId.uref.toByteArray
            val purseAddrSer = serializeArray(purseAddr)
            Base16.encode(purseAddrSer)
          }
          s"$mintPublicHex:$purseAddrHex"
        }
        balanceURef <- DeployService[F].queryState(blockHash, "local", localKeyValue, "").rethrow
        balance <- DeployService[F]
                    .queryState(
                      blockHash,
                      "uref",
                      Base16.encode(balanceURef.getKey.getUref.uref.toByteArray),
                      ""
                    )
                    .rethrow
      } yield s"Balance:\n$address : ${balance.getBigInt.value}").attempt
    }

  def visualizeDag[F[_]: Sync: DeployService: Timer](
      depth: Int,
      showJustificationLines: Boolean,
      maybeOut: Option[String],
      maybeStreaming: Option[Streaming]
  ): F[Unit] =
    gracefulExit({
      def askDag =
        DeployService[F]
          .visualizeDag(depth, showJustificationLines)
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

  def transferCLI[F[_]: Sync: DeployService: FilesAPI](
      deployConfig: DeployConfig,
      privateKeyFile: File,
      recipientPublicKeyBase64: String,
      amount: Long
  ): F[Unit] =
    for {
      rawPrivateKey <- readFileAsString[F](privateKeyFile)
      privateKey <- MonadThrowable[F].fromOption(
                     Ed25519.tryParsePrivateKey(rawPrivateKey),
                     new IllegalArgumentException(
                       s"Failed to parse private key file ${privateKeyFile.getPath()}"
                     )
                   )
      publicKey <- MonadThrowable[F].fromOption(
                    Ed25519.tryToPublic(privateKey),
                    new RuntimeException(
                      "Failed to compute Ed25519 public key from given private key."
                    )
                  )
      _ <- transfer[F](
            deployConfig,
            publicKey,
            privateKey,
            recipientPublicKeyBase64,
            amount
          )
    } yield ()

  def transfer[F[_]: Sync: DeployService: FilesAPI](
      deployConfig: DeployConfig,
      senderPublicKey: PublicKey,
      senderPrivateKey: PrivateKey,
      recipientPublicKeyBase64: String,
      amount: Long,
      exit: Boolean = true,
      ignoreOutput: Boolean = false
  ): F[Unit] =
    for {
      account <- MonadThrowable[F].fromOption(
                  Base64.tryDecode(recipientPublicKeyBase64),
                  new IllegalArgumentException(
                    s"Failed to parse base64 encoded account: $recipientPublicKeyBase64"
                  )
                )
      _ <- deployFileProgram[F](
            from = None,
            deployConfig.withSessionResource(TRANSFER_WASM_FILE),
            maybeEitherPublicKey = senderPublicKey.asRight[String].some,
            maybeEitherPrivateKey = senderPrivateKey.asRight[String].some,
            List(
              bytesArg("account", account),
              longArg("amount", amount)
            ),
            exit,
            ignoreOutput
          )
    } yield ()

  private def readKey[F[_]: Sync, A](keyFile: File, keyType: String, f: String => Option[A]): F[A] =
    readFileAsString[F](keyFile) >>= { key =>
      Sync[F].fromOption[A](
        f(key),
        new IllegalArgumentException(s"$keyFile was not valid Ed25519 $keyType key.")
      )
    }

  /** Signs a deploy with provided private key and writes it to the file (if provided),
    * or prints to STDOUT.
    */
  def sign[F[_]: Sync](
      deployBA: Array[Byte],
      output: Option[File],
      publicKeyFile: File,
      privateKeyFile: File
  ): F[Unit] = {

    val program = for {
      publicKey    <- readKey[F, PublicKey](publicKeyFile, "public", Ed25519.tryParsePublicKey _)
      privateKey   <- readKey[F, PrivateKey](privateKeyFile, "private", Ed25519.tryParsePrivateKey _)
      deploy       <- Sync[F].fromTry(Try(Deploy.parseFrom(deployBA)))
      signedDeploy = deploy.sign(privateKey, publicKey)
      _            <- writeDeploy(signedDeploy, output)
    } yield ()

    gracefulExit(program.attempt)
  }

  /** Constructs a [[Deploy]] from the provided arguments and writes it to a file (or STDOUT).
    */
  def makeDeploy[F[_]: Sync](
      from: ByteString,
      deployConfig: DeployConfig,
      sessionArgs: Seq[Deploy.Arg]
  ): Deploy = {
    val session = deployConfig.session(sessionArgs)
    // It is advisable to provide payment via --payment-name or --payment-hash, if it's stored.
    val payment = deployConfig
      .withPaymentResource(PAYMENT_WASM_FILE)
      .payment(
        deployConfig.paymentAmount.map(bigIntArg("amount", _)).toList
      )

    consensus
      .Deploy()
      .withHeader(
        consensus.Deploy
          .Header()
          .withTimestamp(System.currentTimeMillis)
          .withAccountPublicKey(from)
          .withGasPrice(deployConfig.gasPrice)
          .withTtlMillis(deployConfig.timeToLive.getOrElse(0))
          .withDependencies(deployConfig.dependencies)
      )
      .withBody(
        consensus.Deploy
          .Body()
          .withSession(session)
          .withPayment(payment)
      )
      .withHashes
  }

  /** Writes deploy to either a file (if `deployPath` is defined) or STDOUT.
    */
  def writeDeploy[F[_]: Sync](deploy: Deploy, deployPath: Option[File]): F[Unit] =
    gracefulExit(
      deployPath.fold {
        Sync[F].delay(deploy.writeTo(System.out)).attempt
      } { file =>
        Sync[F].delay(Files.write(file.toPath, deploy.toByteArray)).void.attempt
      },
      ignoreOutput = true
    )

  def sendDeploy[F[_]: Sync: DeployService](deployBA: Array[Byte]): F[Unit] =
    gracefulExit {
      for {
        deploy <- Sync[F].fromTry(Try(Deploy.parseFrom(deployBA)))
        result <- DeployService[F].deploy(deploy)
      } yield result
    }

  def deployFileProgram[F[_]: Sync: DeployService](
      from: Option[String],
      deployConfig: DeployConfig,
      maybeEitherPublicKey: Option[Either[String, PublicKey]],
      maybeEitherPrivateKey: Option[Either[String, PrivateKey]],
      sessionArgs: Seq[Deploy.Arg] = Seq.empty,
      exit: Boolean = true,
      ignoreOutput: Boolean = false
  ): F[Unit] = {
    val maybePrivateKey = maybeEitherPrivateKey.fold(none[PrivateKey]) { either =>
      either.fold(Ed25519.tryParsePrivateKey, _.some)
    }
    val maybePublicKey = maybeEitherPublicKey.flatMap { either =>
      // In the future with multiple signatures and recovery the
      // account public key  can be different then the private key
      // we sign with, so not checking that they match.
      either.fold(Ed25519.tryParsePublicKey, _.some)
    } orElse maybePrivateKey.flatMap(Ed25519.tryToPublic)

    val deploy = for {
      accountPublicKey <- Sync[F].fromOption(
                           from
                             .map(account => ByteString.copyFrom(Base16.decode(account)))
                             .orElse(maybePublicKey.map(ByteString.copyFrom)),
                           new IllegalArgumentException("--from or --public-key must be presented")
                         )
    } yield {
      val deploy =
        makeDeploy(accountPublicKey, deployConfig, sessionArgs)
      (maybePrivateKey, maybePublicKey).mapN(deploy.sign) getOrElse deploy
    }

    gracefulExit(
      deploy
        .flatMap(DeployService[F].deploy)
        .handleError(
          ex => Left(new RuntimeException(s"Couldn't make deploy, reason: ${ex.getMessage}", ex))
        ),
      exit,
      ignoreOutput
    )
  }

  private[client] def gracefulExit[F[_]: Sync, A: Show](
      program: F[Either[Throwable, A]],
      exit: Boolean = true,
      ignoreOutput: Boolean = false
  ): F[Unit] =
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
                if (!ignoreOutput) {
                  println(Show[A].show(msg))
                }
                if (exit) {
                  System.exit(0)
                }
              }
          }
    } yield ()

  private def processError(t: Throwable): Throwable =
    Option(t.getCause).getOrElse(t)

  private def hash[T <: scalapb.GeneratedMessage](data: T): ByteString =
    ByteString.copyFrom(Blake2b256.hash(data.toByteArray))

  def readFileAsString[F[_]: Sync](file: File): F[String] = Sync[F].delay {
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
  }

  implicit val ordering: Ordering[ByteString] =
    Ordering.by[ByteString, String](bs => Base16.encode(bs.toByteArray))

  implicit class DeployOps(d: consensus.Deploy) {
    def withHashes = {
      val h = d.getHeader.withBodyHash(hash(d.getBody))
      d.withHeader(h).withDeployHash(hash(h))
    }

    def sign(privateKey: PrivateKey, publicKey: PublicKey): Deploy = {
      val sig = Ed25519.sign(d.deployHash.toByteArray, privateKey)
      val approval = consensus
        .Approval()
        .withApproverPublicKey(ByteString.copyFrom(publicKey))
        .withSignature(
          consensus.Signature().withSigAlgorithm(Ed25519.name).withSig(ByteString.copyFrom(sig))
        )
      val approvals = d.approvals.toList :+ approval
      d.withApprovals(approvals.distinct.sortBy(_.approverPublicKey))
    }
  }
}
