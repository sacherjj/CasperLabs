package io.casperlabs.casper

import java.nio.file.{Path, Paths}

import cats.Monad
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.configuration.{ignore, relativeToDataDir, SubConfig}
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.crypto.codec.{Base64}
import io.casperlabs.shared.{Log, LogSource}

import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.{Failure, Success, Try}

final case class CasperConf(
    validatorPublicKey: Option[String],
    validatorPublicKeyPath: Option[Path],
    validatorPrivateKey: Option[String],
    validatorPrivateKeyPath: Option[Path],
    validatorSigAlgorithm: String,
    knownValidatorsFile: Option[Path],
    requiredSigs: Int,
    chainSpecPath: Option[Path],
    standalone: Boolean,
    autoProposeEnabled: Boolean,
    autoProposeCheckInterval: FiniteDuration,
    autoProposeBallotInterval: FiniteDuration,
    autoProposeAccInterval: FiniteDuration,
    autoProposeAccCount: Int,
    maxBlockSizeBytes: Int,
    minTtl: FiniteDuration
) extends SubConfig

object CasperConf {
  def parseValidatorsFile[F[_]: Monad: Sync: Log](
      knownValidatorsFile: Option[Path]
  ): F[Set[PublicKeyBS]] =
    knownValidatorsFile match {
      //TODO: Add default set? Throw error?
      case None => Set.empty[PublicKeyBS].pure[F]

      case Some(path) =>
        Sync[F]
          .delay {
            Try(
              Source
                .fromFile(path.toFile)
                .getLines()
                .map(line => PublicKey(ByteString.copyFrom(Base64.tryDecode(line).get)))
                .toSet
            )
          }
          .flatMap {
            case Success(validators) => validators.pure[F]

            case Failure(ex) =>
              Log[F]
                .error(s"Error while parsing known validators file: $ex")
                .map[Set[PublicKeyBS]](_ => throw ex)
          }
    }
}
