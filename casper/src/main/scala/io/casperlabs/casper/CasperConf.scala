package io.casperlabs.casper

import java.nio.file.{Path, Paths}

import cats.Monad
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.configuration.{ignore, relativeToDataDir, SubConfig}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.{Ed25519, Secp256k1}
import io.casperlabs.shared.{Log, LogSource}

import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.{Failure, Success, Try}

final case class CasperConf(
    validatorPublicKey: Option[String],
    validatorPrivateKey: Option[String],
    validatorPrivateKeyPath: Option[Path],
    validatorSigAlgorithm: String,
    bondsFile: Path,
    knownValidatorsFile: Option[Path],
    numValidators: Int,
    @ignore
    @relativeToDataDir("genesis")
    genesisPath: Path = Paths.get("nonreachable"),
    walletsFile: Path,
    minimumBond: Long,
    maximumBond: Long,
    hasFaucet: Boolean,
    requiredSigs: Int,
    shardId: String,
    standalone: Boolean,
    approveGenesis: Boolean,
    approveGenesisInterval: FiniteDuration,
    approveGenesisDuration: FiniteDuration,
    deployTimestamp: Option[Long],
    ignoreDeploySignature: Boolean
) extends SubConfig

object CasperConf {
  private implicit val logSource: LogSource = LogSource(this.getClass)

  def parseValidatorsFile[F[_]: Monad: Sync: Log](
      knownValidatorsFile: Option[Path]
  ): F[Set[ByteString]] =
    knownValidatorsFile match {
      //TODO: Add default set? Throw error?
      case None => Set.empty[ByteString].pure[F]

      case Some(path) =>
        Sync[F]
          .delay {
            Try(
              Source
                .fromFile(path.toFile)
                .getLines()
                .map(line => ByteString.copyFrom(Base16.decode(line)))
                .toSet
            )
          }
          .flatMap {
            case Success(validators) => validators.pure[F]

            case Failure(ex) =>
              Log[F]
                .error(s"Error while parsing known validators file; $ex: ${ex.getMessage}")
                .map[Set[ByteString]](_ => throw ex)
          }
    }

  def publicKey(
      givenPublicKey: Option[Array[Byte]],
      sigAlgorithm: String,
      privateKey: Array[Byte]
  ): Array[Byte] = {

    val maybeInferred = sigAlgorithm match {
      case "ed25519" =>
        Try(Ed25519.toPublic(privateKey)).toOption

      case "secp256k1" =>
        Try(Secp256k1.toPublic(privateKey)).toOption

      case _ => None
    }

    (maybeInferred, givenPublicKey) match {
      case (Some(k1), Some(k2)) =>
        if (keysMatch(k1, k2)) k1
        else throw new Exception("Public key not compatible with given private key!")

      case (Some(k1), None) => k1

      //TODO: Should this case be an error?
      //Will all supported algorithms be able to infer the public key from private?
      case (None, Some(k2)) => k2

      case (None, None) =>
        throw new Exception("Public key must be specified, cannot infer from private key.")
    }
  }

  private def keysMatch(k1: Array[Byte], k2: Array[Byte]): Boolean =
    k1.zip(k2).forall { case (x, y) => x == y }
}
