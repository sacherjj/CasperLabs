package io.casperlabs.client
import java.io._
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import cats.effect.{Sync, Timer}
import cats.implicits._
import com.google.protobuf.ByteString
import guru.nidi.graphviz.engine._
import io.casperlabs.casper.consensus
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.client.configuration.Streaming
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.{Base16, Base64}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.shared.FilesAPI
import org.apache.commons.io._

import scala.concurrent.duration._
import scala.language.higherKinds

object DeployRuntime {

  val BONDING_WASM_FILE   = "bonding.wasm"
  val UNBONDING_WASM_FILE = "unbonding.wasm"
  val TRANSFER_WASM_FILE  = "transfer_to_account.wasm"

  def propose[F[_]: Sync: DeployService](
      exit: Boolean = true,
      ignoreOutput: Boolean = false
  ): F[Unit] =
    gracefulExit(DeployService[F].propose().map(_.map(r => s"Response: $r")), exit, ignoreOutput)

  def showBlock[F[_]: Sync: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showBlock(hash))

  def showDeploys[F[_]: Sync: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showDeploys(hash))

  def showDeploy[F[_]: Sync: DeployService](hash: String): F[Unit] =
    gracefulExit(DeployService[F].showDeploy(hash))

  def showBlocks[F[_]: Sync: DeployService](depth: Int): F[Unit] =
    gracefulExit(DeployService[F].showBlocks(depth))

  def unbond[F[_]: Sync: DeployService](
      maybeAmount: Option[Long],
      nonce: Long,
      sessionCode: Option[File],
      privateKeyFile: File
  ): F[Unit] = {
    val args: Array[Array[Byte]] = Array(
      serializeOption(maybeAmount, serializeLong)
    )
    val argsSer = serializeArgs(args)

    for {
      sessionCode <- readFileOrDefault[F](sessionCode, UNBONDING_WASM_FILE)
      // currently, sessionCode == paymentCode in order to get some gas limit for the execution
      paymentCode   = sessionCode.toList.toArray
      rawPrivateKey <- readFileAsString[F](privateKeyFile)
      _ <- deployFileProgram[F](
            from = None,
            nonce = nonce,
            sessionCode = sessionCode,
            paymentCode = paymentCode,
            maybeEitherPublicKey = None,
            maybeEitherPrivateKey = rawPrivateKey.asLeft[PrivateKey].some,
            gasPrice = 10L, // gas price is fixed at the moment for 10:1
            sessionArgs = ByteString.copyFrom(argsSer)
          )
    } yield ()
  }

  def bond[F[_]: Sync: DeployService](
      amount: Long,
      nonce: Long,
      sessionCode: Option[File],
      privateKeyFile: File
  ): F[Unit] = {
    val args: Array[Array[Byte]] = Array(serializeLong(amount))
    val argsSer: Array[Byte]     = serializeArgs(args)

    for {
      sessionCode <- readFileOrDefault[F](sessionCode, BONDING_WASM_FILE)
      // currently, sessionCode == paymentCode in order to get some gas limit for the execution
      paymentCode   = sessionCode.toList.toArray
      rawPrivateKey <- readFileAsString[F](privateKeyFile)
      _ <- deployFileProgram[F](
            from = None,
            nonce = nonce,
            sessionCode = sessionCode,
            paymentCode = paymentCode,
            maybeEitherPublicKey = None,
            maybeEitherPrivateKey = rawPrivateKey.asLeft[PrivateKey].some,
            gasPrice = 10L, // gas price is fixed at the moment for 10:1
            sessionArgs = ByteString.copyFrom(argsSer)
          )
    } yield ()
  }

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
        mintPrivate <- DeployService[F]
                        .queryState(
                          blockHash,
                          "uref",
                          Base16.encode(mintPublic.getUref.uref.toByteArray), // I am assuming that "mint" points to URef type key.
                          ""
                        )
                        .rethrow
        localKeyValue = {
          val mintPrivateHex = Base16.encode(mintPrivate.getKey.getUref.uref.toByteArray) // Assuming that `mint_private` is of `URef` type.
          val purseAddrHex = {
            val purseAddr    = account.getPurseId.uref.toByteArray
            val purseAddrSer = serializeArray(purseAddr)
            Base16.encode(purseAddrSer)
          }
          s"$mintPrivateHex:$purseAddrHex"
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

  def transfer[F[_]: Sync: DeployService: FilesAPI](
      nonce: Long,
      sessionCode: Option[File],
      privateKeyFile: File,
      recipientPublicKeyBase64: String,
      amount: Long
  ): F[Unit] =
    for {
      account <- MonadThrowable[F].fromOption(
                  Base64.tryDecode(recipientPublicKeyBase64),
                  new IllegalArgumentException(
                    s"Failed to parse base64 encoded account: $recipientPublicKeyBase64"
                  )
                )
      sessionCode <- readFileOrDefault[F](sessionCode, TRANSFER_WASM_FILE)
      // currently, sessionCode == paymentCode in order to get some gas limit for the execution
      paymentCode   = sessionCode.toList.toArray
      args          = serializeArgs(Array(serializeArray(account), serializeLong(amount)))
      rawPrivateKey <- readFileAsString[F](privateKeyFile)
      _ <- deployFileProgram[F](
            from = None,
            nonce = nonce,
            sessionCode = sessionCode,
            paymentCode = paymentCode,
            maybeEitherPublicKey = None,
            maybeEitherPrivateKey = rawPrivateKey.asLeft[PrivateKey].some,
            gasPrice = 10L,
            ByteString.copyFrom(args)
          )
    } yield ()

  def deployFileProgram[F[_]: Sync: DeployService](
      from: Option[String],
      nonce: Long,
      sessionCode: Array[Byte],
      paymentCode: Array[Byte],
      maybeEitherPublicKey: Option[Either[String, PublicKey]],
      maybeEitherPrivateKey: Option[Either[String, PrivateKey]],
      gasPrice: Long,
      sessionArgs: ByteString = ByteString.EMPTY,
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

    val session = ByteString.copyFrom(sessionCode)
    val payment = ByteString.copyFrom(paymentCode)

    val deploy = for {
      accountPublicKey <- Sync[F].fromOption(
                           from
                             .map(account => ByteString.copyFrom(Base16.decode(account)))
                             .orElse(maybePublicKey.map(ByteString.copyFrom)),
                           new IllegalArgumentException("--from or --public-key must be presented")
                         )
    } yield {
      val deploy = consensus
        .Deploy()
        .withHeader(
          consensus.Deploy
            .Header()
            .withTimestamp(System.currentTimeMillis)
            .withAccountPublicKey(accountPublicKey)
            .withNonce(nonce)
            .withGasPrice(gasPrice)
        )
        .withBody(
          consensus.Deploy
            .Body()
            .withSession(consensus.Deploy.Code().withCode(session).withArgs(sessionArgs))
            .withPayment(consensus.Deploy.Code().withCode(payment))
        )
        .withHashes

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

  private[client] def gracefulExit[F[_]: Sync](
      program: F[Either[Throwable, String]],
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
                  println(msg)
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

  // When not provided fallbacks to the contract version packaged with the client.
  private def readFileOrDefault[F[_]: Sync](
      file: Option[File],
      defaultName: String
  ): F[Array[Byte]] = {
    def consumeInputStream(is: InputStream): Array[Byte] = {
      val baos = new ByteArrayOutputStream()
      IOUtils.copy(is, baos)
      baos.toByteArray
    }
    Sync[F].delay {
      file
        .map(f => Files.readAllBytes(f.toPath))
        .getOrElse(consumeInputStream(getClass.getClassLoader.getResourceAsStream(defaultName)))

    }
  }

  private def readFileAsString[F[_]: Sync](file: File): F[String] = Sync[F].delay {
    new String(Files.readAllBytes(file.toPath), StandardCharsets.UTF_8)
  }

  private def serializeLong(l: Long): Array[Byte] =
    java.nio.ByteBuffer
      .allocate(8)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putLong(l)
      .array()

  private def serializeInt(i: Int): Array[Byte] =
    java.nio.ByteBuffer
      .allocate(4)
      .order(ByteOrder.LITTLE_ENDIAN)
      .putInt(i)
      .array()

  // Option serializes as follows:
  // None:        [0]
  // Some(value): [1] ++ serialized value
  private def serializeOption[T](in: Option[T], fSer: T => Array[Byte]): Array[Byte] =
    in.map(value => Array[Byte](1) ++ fSer(value)).getOrElse(Array[Byte](0))

  // This is true for any array but I didn't want to go as far as writing type classes.
  // Binary format of an array is constructed from:
  // length (u64/integer in binary) ++ bytes
  private def serializeArray(ba: Array[Byte]): Array[Byte] = {
    val serLen = serializeInt(ba.length)
    serLen ++ ba
  }

  private def serializeArgs(args: Array[Array[Byte]]): Array[Byte] = {
    val argsBytes    = args.flatMap(serializeArray)
    val argsBytesLen = serializeInt(args.length)
    argsBytesLen ++ argsBytes
  }

  implicit class DeployOps(d: consensus.Deploy) {
    def withHashes = {
      val h = d.getHeader.withBodyHash(hash(d.getBody))
      d.withHeader(h).withDeployHash(hash(h))
    }

    def sign(privateKey: PrivateKey, publicKey: PublicKey) = {
      val sig = Ed25519.sign(d.deployHash.toByteArray, privateKey)
      d.withApprovals(
        List(
          consensus
            .Approval()
            .withApproverPublicKey(ByteString.copyFrom(publicKey))
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
