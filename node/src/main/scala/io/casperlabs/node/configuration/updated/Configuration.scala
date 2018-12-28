package io.casperlabs.node.configuration.updated
import java.nio.file.Path

import io.casperlabs.comm.PeerNode
import io.casperlabs.node.configuration.updated.TomlReader.Implicits._
import io.casperlabs.shared.StoreType
import org.rogach.scallop.ScallopOption
import shapeless._

import scala.concurrent.duration.FiniteDuration

sealed trait Configuration extends Product with Serializable {
  def grpcHost: Option[String]
  def grpcPort: Option[Int]
  def grpcPortInternal: Option[Int]
  def fallbackTo[A <: Configuration](other: A): Either[String, A]
}
object Configuration {
  final case class Diagnostics(
      grpcHost: Option[String],
      grpcPort: Option[Int],
      grpcPortInternal: Option[Int]
  ) extends Configuration {
    override def fallbackTo[A <: Configuration](other: A): Either[String, A] =
      other match {
        case other: Diagnostics =>
          val gen = Generic[Diagnostics]
          val ha  = gen.to(this)
          val hb  = gen.to(other: Diagnostics)
          object mapper extends Poly1 {
            implicit def caseOptionString = at[(Option[String], Option[String])] {
              case (x, y) => x.orElse(y)
            }
            implicit def caseOptionInt = at[(Option[Int], Option[Int])] {
              case (x, y) => x.orElse(y)
            }
          }
          val result = ha
            .zip(hb)
            .map(mapper)
          Right(gen.from(result).asInstanceOf[A])
        case x => Left(s"Expected Configuration.Diagnostics got $x")
      }
  }
  final case class Run(
      grpcHost: Option[String],
      grpcPort: Option[Int],
      grpcPortInternal: Option[Int],
      dynamicHostAddress: Option[Boolean],
      noUpnp: Option[Boolean],
      defaultTimeout: Option[Int],
      certificate: Option[Path],
      key: Option[Path],
      secureRandomNonBlocking: Option[Boolean],
      port: Option[Int],
      httpPort: Option[Int],
      kademliaPort: Option[Int],
      numValidators: Option[Int],
      bondsFile: Option[String],
      knownValidators: Option[String],
      walletsFile: Option[String],
      minimumBond: Option[Long],
      maximumBond: Option[Long],
      hasFaucet: Option[Boolean],
      bootstrap: Option[PeerNode],
      standalone: Option[Boolean],
      requiredSignatures: Option[Int],
      deployTimestamp: Option[Long],
      duration: Option[FiniteDuration],
      interval: Option[FiniteDuration],
      genesisValidator: Option[Boolean],
      host: Option[String],
      dataDir: Option[Path],
      mapSize: Option[Long],
      storeType: Option[StoreType],
      maxNumOfConnections: Option[Int],
      maxMessageSize: Option[Int],
      threadPoolSize: Option[Int],
      casperBlockStoreSize: Option[Long],
      validatorPublicKey: Option[String],
      validatorPrivateKey: Option[String],
      validatorPrivateKeyPath: Option[Path],
      validatorSigAlgorithm: Option[String],
      shardId: Option[String]
  ) extends Configuration {
    override def fallbackTo[A <: Configuration](other: A): Either[String, A] =
      other match {
        case other: Run =>
          val gen = Generic[Run]
          val ha  = gen.to(this)
          val hb  = gen.to(other: Run)
          object mapper extends Poly1 {
            implicit def caseOptionString = at[(Option[String], Option[String])] {
              case (x, y) => x.orElse(y)
            }

            implicit def caseOptionInt = at[(Option[Int], Option[Int])] {
              case (x, y) => x.orElse(y)
            }

            implicit def caseOptionLong = at[(Option[Long], Option[Long])] {
              case (x, y) => x.orElse(y)
            }

            implicit def caseOptionPath = at[(Option[Path], Option[Path])] {
              case (x, y) => x.orElse(y)
            }

            implicit def caseOptionStoreType = at[(Option[StoreType], Option[StoreType])] {
              case (x, y) => x.orElse(y)
            }

            implicit def caseOptionFiniteDuration =
              at[(Option[FiniteDuration], Option[FiniteDuration])] {
                case (x, y) => x.orElse(y)
              }

            implicit def caseOptionPeerNode =
              at[(Option[PeerNode], Option[PeerNode])] {
                case (x, y) => x.orElse(y)
              }

            implicit def caseOptionBoolean = at[(Option[Boolean], Option[Boolean])] {
              case (x, y) => x.orElse(y)
            }
          }
          val result = ha
            .zip(hb)
            .map(mapper)
          Right(gen.from(result).asInstanceOf[A])
        case x => Left(s"Expected Configuration.Diagnostics, got $x")
      }
  }

  def parse(args: Array[String]): Either[String, Configuration] =
    try {
      implicit def scallopOptionToOption[A](scallopOption: ScallopOption[A]): Option[A] =
        scallopOption.toOption

      val options = Options(args)

      def retrieveConfiguration[A <: Configuration: TomlReader](cliConf: A): Either[String, A] = {
        val fileConfEither = options.configFile.toOption.map(TomlReader[A].from) match {
          case Some(Right(config)) => Right(Some(config))
          case Some(Left(error))   => Left(error)
          case None                => Right(None)
        }

        val Right(Some(c)) = fileConfEither
        for {
          defaultConf          <- TomlReader[A].default
          maybeFileConf        <- fileConfEither
          withOneLevelFallback <- maybeFileConf.map(cliConf.fallbackTo).getOrElse(Right(cliConf))
          result               <- withOneLevelFallback.fallbackTo(defaultConf)
        } yield result
      }

      options.subcommand match {
        case Some(options.run) =>
          retrieveConfiguration(
            Run(
              options.grpcHost,
              options.grpcPort,
              options.grpcPortInternal,
              options.run.dynamicHostAddress,
              options.run.noUpnp,
              options.run.defaultTimeout,
              options.run.certificate,
              options.run.key,
              options.run.secureRandomNonBlocking,
              options.run.port,
              options.run.httpPort,
              options.run.kademliaPort,
              options.run.numValidators,
              options.run.bondsFile,
              options.run.knownValidators,
              options.run.walletsFile,
              options.run.minimumBond,
              options.run.maximumBond,
              options.run.hasFaucet,
              options.run.bootstrap,
              options.run.standalone,
              options.run.requiredSigs,
              options.run.deployTimestamp,
              options.run.duration,
              options.run.interval,
              options.run.genesisValidator,
              options.run.host,
              options.run.dataDir,
              options.run.mapSize,
              options.run.storeType,
              options.run.maxNumOfConnections,
              options.run.maxMessageSize,
              options.run.threadPoolSize,
              options.run.casperBlockStoreSize,
              options.run.validatorPublicKey,
              options.run.validatorPrivateKey,
              options.run.validatorPrivateKeyPath,
              options.run.validatorSigAlgorithm,
              options.run.shardId
            )
          )
        case Some(options.diagnostics) =>
          retrieveConfiguration(
            Diagnostics(
              options.grpcHost,
              options.grpcPort,
              options.grpcPortInternal
            )
          )
      }
    } catch {
      case ex: Throwable =>
        Left(s"Couldn't parse arguments: ${args.mkString(", ")}, reason: ${ex.getMessage}")
    }
}
