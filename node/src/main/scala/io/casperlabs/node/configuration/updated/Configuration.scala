package io.casperlabs.node.configuration.updated
import java.nio.file.Path

import io.casperlabs.comm.PeerNode
import io.casperlabs.shared.StoreType
import shapeless._

import scala.concurrent.duration.FiniteDuration

sealed trait Configuration extends Product with Serializable {
  def grpcHost: Option[String]
  def grpcPort: Option[Int]
  def grpcPortInternal: Option[Int]
  def fallbackTo(other: Configuration): Option[Configuration]
}
object Configuration {
  final case class Diagnostics(
      grpcHost: Option[String],
      grpcPort: Option[Int],
      grpcPortInternal: Option[Int]
  ) extends Configuration {
    def fallbackTo(other: Configuration): Option[Configuration] =
      (this, other) match {
        case (a: Diagnostics, b: Diagnostics) =>
          val gen = Generic[Diagnostics]
          val ha  = gen.to(a)
          val hb  = gen.to(b)
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
          Option(gen.from(result))
        case _ => None
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
    override def fallbackTo(other: Configuration): Option[Configuration] =
      (this, other) match {
        case (a: Run, b: Run) =>
          val gen = Generic[Run]
          val ha  = gen.to(a)
          val hb  = gen.to(b)
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
          Option(gen.from(result))
        case _ => None
      }
  }
}
