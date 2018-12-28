package io.casperlabs.node.configuration.updated

import java.nio.file.{Path, Paths}

import io.casperlabs.comm.{Endpoint, NodeIdentifier, PeerNode}
import io.casperlabs.shared.StoreType
import org.scalatest._

import scala.concurrent.duration._
class TomlConfigurationSpec extends FunSuite with Matchers {
  val config =
    """
      |[run]
      |grpc-host = "localhost"
      |grpc-port = 10
      |grpc-port-internal = 10
      |dynamic-host-address = false
      |no-upnp = false
      |default-timeout = 1000
      |certificate = "/tls/certificate.pem"
      |key = "/tls/key.pem"
      |secure-random-non-blocking = false
      |port = 10
      |http-port = 10
      |kademlia-port = 10
      |num-validators = 10
      |bonds-file = "/tmp/bonds-file.txt"
      |known-validators = "v1"
      |wallets-file = "/validator/wallet"
      |minimum-bond = 10
      |maximum-bond = 10
      |has-faucet = false
      |bootstrap = "rnode://de6eed5d00cf080fc587eeb412cb31a75fd10358@52.119.8.109?protocol=40400&discovery=40404"
      |standalone = true
      |required-signatures = 10
      |deploy-timestamp = 10
      |duration = "10 minutes"
      |interval = "30 minutes"
      |genesis-validator = false
      |host = "localhost"
      |data-dir = "/tmp"
      |map-size = 10
      |store-type = "lmdb"
      |max-num-of-connections = 10
      |max-message-size = 10
      |thread-pool-size = 10
      |casper-block-store-size = 10
      |validator-public-key = "test"
      |validator-private-key = "test"
      |validator-private-key-path = "/tls/certificate.pem"
      |validator-sig-algorithm = "test"
      |shard-id = "test"
      |
      |[diagnostics]
      |grpc-host = "localhost"
      |grpc-port = 10
      |grpc-port-internal = 10
      |""".stripMargin

  test("TomlReader should parse Configuration.Run") {
    import TomlReader.Implicits.runCommandToml
    val Right(c) = TomlReader[Configuration.Run].from(config)
    c.grpcHost shouldEqual Some("localhost")
    c.grpcPort shouldEqual Some(10)
    c.grpcPortInternal shouldEqual Some(10)
    c.dynamicHostAddress shouldEqual Some(false)
    c.noUpnp shouldEqual Some(false)
    c.defaultTimeout shouldEqual Some(1000)
    c.certificate shouldEqual Some(Paths.get("/tls/certificate.pem"))
    c.key shouldEqual Some(Paths.get("/tls/key.pem"))
    c.secureRandomNonBlocking shouldEqual Some(false)
    c.port shouldEqual Some(10)
    c.httpPort shouldEqual Some(10)
    c.kademliaPort shouldEqual Some(10)
    c.numValidators shouldEqual Some(10)
    c.bondsFile shouldEqual Some("/tmp/bonds-file.txt")
    c.knownValidators shouldEqual Some("v1")
    c.walletsFile shouldEqual Some("/validator/wallet")
    c.minimumBond shouldEqual Some(10)
    c.maximumBond shouldEqual Some(10)
    c.hasFaucet shouldEqual Some(false)
    c.bootstrap shouldEqual Some(
      PeerNode(
        NodeIdentifier("de6eed5d00cf080fc587eeb412cb31a75fd10358"),
        Endpoint("52.119.8.109", 40400, 40404)
      )
    )
    c.standalone shouldEqual Some(true)
    c.requiredSignatures shouldEqual Some(10)
    c.deployTimestamp shouldEqual Some(10)
    c.duration shouldEqual Some(10.minutes)
    c.interval shouldEqual Some(30.minutes)
    c.genesisValidator shouldEqual Some(false)
    c.host shouldEqual Some("localhost")
    c.dataDir shouldEqual Some(Paths.get("/tmp"))
    c.mapSize shouldEqual Some(10)
    c.storeType shouldEqual Some(StoreType.LMDB)
    c.maxNumOfConnections shouldEqual Some(10)
    c.maxMessageSize shouldEqual Some(10)
    c.threadPoolSize shouldEqual Some(10)
    c.casperBlockStoreSize shouldEqual Some(10)
    c.validatorPublicKey shouldEqual Some("test")
    c.validatorPrivateKey shouldEqual Some("test")
    c.validatorPrivateKeyPath shouldEqual Some(Paths.get("/tls/certificate.pem"))
    c.validatorSigAlgorithm shouldEqual Some("test")
    c.shardId shouldEqual Some("test")
  }

  test("TomlReader should parse Configuration.Diagnostics") {
    import TomlReader.Implicits.diagnosticsToml
    val Right(c) = TomlReader[Configuration.Diagnostics].from(config)
    c.grpcHost shouldEqual Some("localhost")
    c.grpcPort shouldEqual Some(10)
    c.grpcPortInternal shouldEqual Some(10)
  }
}
