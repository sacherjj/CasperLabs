package io.casperlabs.client
import java.io._
import java.nio.ByteOrder

import cats.effect._
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import guru.nidi.graphviz.engine.Format
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.client.configuration.Streaming
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.codec.{Base16, Base64}
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.shared.{FilesAPI, Log}

import scala.concurrent.duration._
import scala.language.higherKinds

//TODO: Remove @silent annotation after resolving warning
@silent(".*")
class DeployRuntime[F[_]: MonadThrowable: FilesAPI: DeployService: Log: Timer](
    graphvizWriteToFile: (File, Format, String) => F[Unit]
) {

  val BONDING_WASM_FILE                    = "bonding.wasm"
  val UNBONDING_WASM_FILE                  = "unbonding.wasm"
  val TRANSFER_WASM_FILE                   = "transfer_to_account.wasm"
  val SET_ACCOUNT_KEY_THRESHOLDS_WASM_FILE = "set_key_thresholds.wasm"
  val ADD_KEY_WASM_FILE                    = "add_associated_key.wasm"
  val REMOVE_KEY_WASM_FILE                 = "remove_associated_key.wasm"
  val UPDATE_KEY_WASM_FILE                 = "update_associated_key.wasm"

  val DefaultGasPrice = 10L

  def propose(
      ignoreOutput: Boolean = false
  ): F[Unit] =
    logResult(DeployService[F].propose(), ignoreOutput)

  def showBlock(hash: String): F[Unit] =
    logResult(DeployService[F].showBlock(hash))

  def showDeploys(hash: String): F[Unit] =
    logResult(DeployService[F].showDeploys(hash))

  def showDeploy(hash: String): F[Unit] =
    logResult(DeployService[F].showDeploy(hash))

  def showBlocks(depth: Int): F[Unit] =
    logResult(DeployService[F].showBlocks(depth))

  def unbond(
      maybeAmount: Option[Long],
      nonce: Long,
      maybeSessionCode: Option[File],
      privateKeyFile: File
  ): F[Unit] =
    deploy(
      nonce = nonce,
      privateKeyFile = privateKeyFile,
      maybeSessionCode = maybeSessionCode,
      defaultSessionCodeResourceName = UNBONDING_WASM_FILE,
      args = serializeArgs(
        Array(
          serializeOption(maybeAmount, serializeLong)
        )
      )
    )

  def bond(
      amount: Long,
      nonce: Long,
      maybeSessionCode: Option[File],
      privateKeyFile: File
  ): F[Unit] =
    deploy(
      nonce = nonce,
      privateKeyFile = privateKeyFile,
      maybeSessionCode = maybeSessionCode,
      defaultSessionCodeResourceName = BONDING_WASM_FILE,
      args = serializeArgs(
        Array(serializeLong(amount))
      )
    )

  def queryState(
      blockHash: String,
      keyVariant: String,
      keyValue: String,
      path: String
  ): F[Unit] =
    logResult(
      DeployService[F]
        .queryState(blockHash, keyVariant, keyValue, path)
        .map(_.map(Printer.printToUnicodeString(_)))
    )

  def balance(address: String, blockHash: String): F[Unit] =
    logResult {
      (for {
        value <- DeployService[F].queryState(blockHash, "address", address, "").rethrow
        _ <- MonadThrowable[F]
              .raiseError(new IllegalStateException(s"Expected Account type value under $address."))
              .whenA(!value.value.isAccount)
        account = value.getAccount
        mintPublic <- MonadThrowable[F].fromOption(
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

  def visualizeDag(
      depth: Int,
      showJustificationLines: Boolean,
      maybeOut: Option[File],
      maybeStreaming: Option[Streaming]
  ): F[Unit] =
    logResult({
      def askDag =
        DeployService[F]
          .visualizeDag(depth, showJustificationLines)
          .rethrow

      val sleep = Timer[F].sleep(5.seconds)

      def subscribe(
          out: File,
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
              val newFile = streaming match {
                case Streaming.Single => out
                case Streaming.Multiple =>
                  val absoluteName = out.getAbsolutePath

                  val extension = "." + absoluteName.split('.').last
                  new File(absoluteName.stripSuffix(extension) + s"_$index" + extension)
              }
              graphvizWriteToFile(newFile, format, dag) >>
                sleep >>
                subscribe(out, streaming, format, index + 1, dag.some)
            }
        }

      // Total because it's validated in 'Options'
      def parseFormat(out: String) = Format.valueOf(out.split('.').last.toUpperCase)

      val eff = (maybeOut, maybeStreaming) match {
        case (None, None) =>
          askDag
        case (Some(out), None) =>
          askDag >>= { dag =>
            graphvizWriteToFile(out, parseFormat(out.getName), dag).map(_ => "Success")
          }
        case (Some(out), Some(streaming)) =>
          subscribe(out, streaming, parseFormat(out.getName)).map(_ => "Success")
        case (None, Some(_)) =>
          MonadThrowable[F].raiseError[String](new Throwable("--out must be specified if --stream"))
      }

      eff.attempt
    })

  def transferCLI(
      nonce: Long,
      maybeSessionCode: Option[File],
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
      _ <- deploy(
            nonce = nonce,
            privateKeyFile = privateKeyFile,
            maybeSessionCode = maybeSessionCode,
            defaultSessionCodeResourceName = TRANSFER_WASM_FILE,
            args = serializeArgs(Array(serializeArray(account), serializeLong(amount)))
          )
    } yield ()

  def transferBench(
      nonce: Long,
      maybeSessionCode: Option[File],
      privateKey: PrivateKey,
      publicKey: PublicKey,
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
      sessionCode <- readFileOrDefault(maybeSessionCode, TRANSFER_WASM_FILE)
      args        = serializeArgs(Array(serializeArray(account), serializeLong(amount)))
      _ <- deploy(
            from = None,
            nonce = nonce,
            sessionCode = sessionCode,
            paymentCode = sessionCode.toList.toArray,
            maybeEitherPublicKey = publicKey.asRight[String].some,
            maybeEitherPrivateKey = privateKey.asRight[String].some,
            gasPrice = DefaultGasPrice,
            sessionArgs = args,
            ignoreOutput = true
          )
    } yield ()

  def setAccountKeyThresholds(
      keysManagement: Int,
      deploys: Int,
      nonce: Long,
      privateKeyFile: File,
      maybeSessionCode: Option[File]
  ): F[Unit] =
    deploy(
      nonce = nonce,
      privateKeyFile = privateKeyFile,
      maybeSessionCode = maybeSessionCode,
      defaultSessionCodeResourceName = SET_ACCOUNT_KEY_THRESHOLDS_WASM_FILE,
      args = serializeArgs(???)
    )

  def addKey(
      publicKey: File,
      weight: Int,
      nonce: Long,
      privateKeyFile: File,
      maybeSessionCode: Option[File]
  ): F[Unit] =
    deploy(
      nonce = nonce,
      privateKeyFile = privateKeyFile,
      maybeSessionCode = maybeSessionCode,
      defaultSessionCodeResourceName = ADD_KEY_WASM_FILE,
      args = serializeArgs(???)
    )

  def removeKey(
      publicKey: File,
      nonce: Long,
      privateKeyFile: File,
      maybeSessionCode: Option[File]
  ): F[Unit] =
    deploy(
      nonce = nonce,
      privateKeyFile = privateKeyFile,
      maybeSessionCode = maybeSessionCode,
      defaultSessionCodeResourceName = REMOVE_KEY_WASM_FILE,
      args = serializeArgs(???)
    )

  def updateKey(
      publicKey: File,
      newWeight: Int,
      nonce: Long,
      privateKeyFile: File,
      maybeSessionCode: Option[File]
  ): F[Unit] =
    deploy(
      nonce = nonce,
      privateKeyFile = privateKeyFile,
      maybeSessionCode = maybeSessionCode,
      defaultSessionCodeResourceName = UPDATE_KEY_WASM_FILE,
      args = serializeArgs(???)
    )

  def deploy(
      from: Option[String],
      nonce: Long,
      sessionCode: Array[Byte],
      paymentCode: Array[Byte],
      maybeEitherPublicKey: Option[Either[String, PublicKey]],
      maybeEitherPrivateKey: Option[Either[String, PrivateKey]],
      gasPrice: Long,
      sessionArgs: ByteString = ByteString.EMPTY,
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
      accountPublicKey <- MonadThrowable[F].fromOption(
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

    logResult(
      deploy
        .flatMap(DeployService[F].deploy)
        .handleError(
          ex => Left(new RuntimeException(s"Couldn't make deploy, reason: ${ex.getMessage}", ex))
        ),
      ignoreOutput
    )
  }

  private def deploy(
      nonce: Long,
      privateKeyFile: File,
      maybeSessionCode: Option[File],
      defaultSessionCodeResourceName: String,
      args: ByteString
  ): F[Unit] =
    for {
      rawPrivateKey <- FilesAPI[F].readString(privateKeyFile.toPath)
      privateKey <- MonadThrowable[F].fromOption(
                     Ed25519.tryParsePrivateKey(rawPrivateKey),
                     new IllegalArgumentException(
                       s"Failed to parse private key file ${privateKeyFile.getPath}"
                     )
                   )
      publicKey <- MonadThrowable[F].fromOption(
                    Ed25519.tryToPublic(privateKey),
                    new RuntimeException(
                      "Failed to compute Ed25519 public key from given private key."
                    )
                  )
      sessionCode <- readFileOrDefault(maybeSessionCode, defaultSessionCodeResourceName)
      _ <- deploy(
            from = None,
            nonce = nonce,
            sessionCode = sessionCode,
            paymentCode = sessionCode.toList.toArray,
            maybeEitherPublicKey = publicKey.asRight[String].some,
            maybeEitherPrivateKey = privateKey.asRight[String].some,
            gasPrice = DefaultGasPrice,
            sessionArgs = args
          )
    } yield ()

  private[client] def logResult(
      program: F[Either[Throwable, String]],
      ignoreOutput: Boolean = false
  ): F[Unit] =
    for {
      result <- MonadThrowable[F].attempt(program)
      _ <- result.joinRight match {
            case Left(ex) => Log[F].error("Command failed", ex)
            case Right(msg) =>
              Log[F].info(msg).whenA(!ignoreOutput)
          }
    } yield ()

  private def hash[T <: scalapb.GeneratedMessage](data: T): ByteString =
    ByteString.copyFrom(Blake2b256.hash(data.toByteArray))

  // When not provided, fallbacks to the contract version packaged with the client.
  private def readFileOrDefault(
      maybeFile: Option[File],
      defaultName: String
  ): F[Array[Byte]] = {
    val readFromResources = FilesAPI[F].readBytesFromResources(defaultName)
    maybeFile
      .map(file => FilesAPI[F].readBytes(file.toPath).attempt.map(_.toOption))
      .fold(readFromResources)(effect => effect.flatMap(_.fold(readFromResources)(_.pure[F])))
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

  private def serializeArgs(args: Array[Array[Byte]]): ByteString = {
    val argsBytes    = args.flatMap(serializeArray)
    val argsBytesLen = serializeInt(args.length)
    ByteString.copyFrom(argsBytesLen ++ argsBytes)
  }

  implicit class DeployOps(d: consensus.Deploy) {
    def withHashes: Deploy = {
      val h = d.getHeader.withBodyHash(hash(d.getBody))
      d.withHeader(h).withDeployHash(hash(h))
    }

    def sign(privateKey: PrivateKey, publicKey: PublicKey): Deploy = {
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
