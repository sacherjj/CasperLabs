package io.casperlabs.node.configuration

import java.nio.charset.Charset
import java.nio.file.{Files, OpenOption, Paths, StandardOpenOption}
import java.util.concurrent.TimeUnit

import cats.syntax.option._
import io.casperlabs.comm.{Endpoint, NodeIdentifier, PeerNode}
import io.casperlabs.shared.StoreType
import org.scalatest._

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class ConfigurationSoftSpec extends FunSuite with Matchers with BeforeAndAfterEach {
  import ConfigurationSoft._
  val configFilename = "test-configuration.toml"

  val expectedFromResources = {
    val server = Server(
      host = "test".some,
      port = 1.some,
      httpPort = 1.some,
      kademliaPort = 1.some,
      dynamicHostAddress = true.some,
      noUpnp = true.some,
      defaultTimeout = 1.some,
      bootstrap = PeerNode(
        NodeIdentifier("de6eed5d00cf080fc587eeb412cb31a75fd10358"),
        Endpoint("52.119.8.109", 1, 1)
      ).some,
      standalone = true.some,
      mapSize = 1L.some,
      storeType = StoreType.LMDB.some,
      dataDir = Paths.get("test").some,
      maxNumOfConnections = 1.some,
      maxMessageSize = 1.some,
      chunkSize = 1.some
    )
    val grpcServer = GrpcServer(
      host = "test".some,
      socket = Paths.get("test").some,
      portExternal = 1.some,
      portInternal = 1.some
    )
    val casper = Casper(
      publicKey = "test".some,
      privateKey = "test".some,
      privateKeyPath = Paths.get("test").some,
      sigAlgorithm = "test".some,
      bondsFile = "test".some,
      knownValidatorsFile = "test".some,
      numValidators = 1.some,
      genesisPath = Paths.get("test").some,
      walletsFile = "test".some,
      minimumBond = 1L.some,
      maximumBond = 1L.some,
      hasFaucet = true.some,
      requiredSigs = 1.some,
      shardId = "test".some,
      approveGenesis = true.some,
      approveGenesisInterval = FiniteDuration(1, TimeUnit.SECONDS).some,
      approveGenesisDuration = FiniteDuration(1, TimeUnit.SECONDS).some,
      deployTimestamp = 1L.some
    )
    val tls = Tls(
      certificate = Paths.get("test").some,
      key = Paths.get("test").some,
      secureRandomNonBlocking = true.some
    )
    val lmdb = LmdbBlockStore(
      path = Paths.get("test").some,
      blockStoreSize = 1L.some,
      maxDbs = 1.some,
      maxReaders = 1.some,
      useTls = true.some
    )
    val blockStorage = BlockDagFileStorage(
      latestMessagesLogPath = Paths.get("test").some,
      latestMessagesCrcPath = Paths.get("test").some,
      blockMetadataLogPath = Paths.get("test").some,
      blockMetadataCrcPath = Paths.get("test").some,
      checkpointsDirPath = Paths.get("test").some,
      latestMessagesLogMaxSizeFactor = 1.some
    )
    ConfigurationSoft(
      server.some,
      grpcServer.some,
      tls.some,
      casper.some,
      lmdb.some,
      blockStorage.some
    )
  }

  val rawConfigFile =
    """
      |[server]
      |host = "test2"
      |port = 2
      |http-port = 2
      |kademlia-port = 2
      |dynamic-host-address = false
      |no-upnp = false
      |default-timeout = 2
      |bootstrap = "casperlabs://de6eed5d00cf080fc587eeb412cb31a75fd10358@52.119.8.254?protocol=2&discovery=2"
      |standalone = false
      |map-size = 2
      |store-type = "inmem"
      |data-dir = "test2"
      |max-num-of-connections = 2
      |max-message-size = 2
      |chunk-size = 2
      |
      |[lmdb]
      |path = "test2"
      |block-store-size = 2
      |max-dbs = 2
      |max-readers = 2
      |use-tls = false
      |
      |[blockstorage]
      |latest-messages-log-path = "test2"
      |latest-messages-crc-path = "test2"
      |block-metadata-log-path = "test2"
      |block-metadata-crc-path = "test2"
      |checkpoints-dir-path = "test2"
      |latest-messages-log-max-size-factor = 2
      |
      |[grpc]
      |host = "test2"
      |socket = "test2"
      |port-external = 2
      |port-internal = 2
      |
      |[tls]
      |certificate = "test2"
      |key = "test2"
      |secure-random-non-blocking = false
      |
      |[casper]
      |public-key = "test2"
      |private-key = "test2"
      |private-key-path = "test2"
      |sig-algorithm = "test2"
      |bonds-file = "test2"
      |known-validators-file = "test2"
      |num-validators = 2
      |genesis-path = "test2"
      |wallets-file = "test2"
      |minimum-bond = 2
      |maximum-bond = 2
      |has-faucet = false
      |required-sigs = 2
      |shard-id = "test2"
      |approve-genesis = false
      |approve-genesis-interval = "2seconds"
      |approve-genesis-duration = "2seconds"
      |deploy-timestamp = 2
    """.stripMargin
  val expectedFromConfigFile = {
    val server = Server(
      host = "test2".some,
      port = 2.some,
      httpPort = 2.some,
      kademliaPort = 2.some,
      dynamicHostAddress = false.some,
      noUpnp = false.some,
      defaultTimeout = 2.some,
      bootstrap = PeerNode(
        NodeIdentifier("de6eed5d00cf080fc587eeb412cb31a75fd10358"),
        Endpoint("52.119.8.254", 2, 2)
      ).some,
      standalone = false.some,
      mapSize = 2L.some,
      storeType = StoreType.InMem.some,
      dataDir = Paths.get("test2").some,
      maxNumOfConnections = 2.some,
      maxMessageSize = 2.some,
      chunkSize = 2.some
    )
    val grpcServer = GrpcServer(
      host = "test2".some,
      socket = Paths.get("test2").some,
      portExternal = 2.some,
      portInternal = 2.some
    )
    val casper = Casper(
      publicKey = "test2".some,
      privateKey = "test2".some,
      privateKeyPath = Paths.get("test2").some,
      sigAlgorithm = "test2".some,
      bondsFile = "test2".some,
      knownValidatorsFile = "test2".some,
      numValidators = 2.some,
      genesisPath = Paths.get("test2").some,
      walletsFile = "test2".some,
      minimumBond = 2L.some,
      maximumBond = 2L.some,
      hasFaucet = false.some,
      requiredSigs = 2.some,
      shardId = "test2".some,
      approveGenesis = false.some,
      approveGenesisInterval = FiniteDuration(2, TimeUnit.SECONDS).some,
      approveGenesisDuration = FiniteDuration(2, TimeUnit.SECONDS).some,
      deployTimestamp = 2L.some
    )
    val tls = Tls(
      certificate = Paths.get("test2").some,
      key = Paths.get("test2").some,
      secureRandomNonBlocking = false.some
    )
    val lmdb = LmdbBlockStore(
      path = Paths.get("test2").some,
      blockStoreSize = 2L.some,
      maxDbs = 2.some,
      maxReaders = 2.some,
      useTls = false.some
    )
    val blockStorage = BlockDagFileStorage(
      latestMessagesLogPath = Paths.get("test2").some,
      latestMessagesCrcPath = Paths.get("test2").some,
      blockMetadataLogPath = Paths.get("test2").some,
      blockMetadataCrcPath = Paths.get("test2").some,
      checkpointsDirPath = Paths.get("test2").some,
      latestMessagesLogMaxSizeFactor = 2.some
    )
    ConfigurationSoft(
      server.some,
      grpcServer.some,
      tls.some,
      casper.some,
      lmdb.some,
      blockStorage.some
    )
  }

  val cliArgs = List(
    List("--grpc-host", "test3"),
    List("--grpc-port", "3"),
    List("--config-file", configFilename),
    List("--server-max-message-size", "3"),
    List("--server-chunk-size", "3"),
    List("run"),
    List("--casper-bonds-file", "test3"),
    List("--casper-deploy-timestamp", "3"),
    List("--casper-duration", "3seconds"),
    List("--casper-genesis-validator"),
    List("--casper-has-faucet"),
    List("--casper-interval", "3seconds"),
    List("--casper-known-validators", "test3"),
    List("--casper-maximum-bond", "3"),
    List("--casper-minimum-bond", "3"),
    List("--casper-num-validators", "3"),
    List("--casper-required-sigs", "3"),
    List("--casper-shard-id", "test3"),
    List("--casper-validator-private-key", "test3"),
    List("--casper-validator-public-key", "test3"),
    List("--casper-validator-private-key-path", "test3"),
    List("--casper-validator-sig-algorithm", "test3"),
    List("--casper-wallets-file", "test3"),
    List("--grpc-port-internal", "3"),
    List("--grpc-socket", "test3"),
    List("--lmdb-block-store-size", "3"),
    List(
      "--server-bootstrap",
      "casperlabs://de6eed5d00cf080fc587eeb412cb31a75fd10358@52.119.8.253?protocol=3&discovery=3"
    ),
    List("--server-data-dir", "test3"),
    List("--server-default-timeout", "3"),
    List("--server-dynamic-host-address"),
    List("--server-host", "test3"),
    List("--server-http-port", "3"),
    List("--server-kademlia-port", "3"),
    List("--server-map-size", "3"),
    List("--server-max-num-of-connections", "3"),
    List("--server-no-upnp"),
    List("--server-port", "3"),
    List("--server-standalone"),
    List("--server-store-type", "mixed"),
    List("--tls-certificate", "test3"),
    List("--tls-key", "test3"),
    List("--tls-secure-random-non-blocking")
  ).flatten.toArray
  val expectedFromCliArgs = {
    val server = Server(
      host = "test3".some,
      port = 3.some,
      httpPort = 3.some,
      kademliaPort = 3.some,
      dynamicHostAddress = true.some,
      noUpnp = true.some,
      defaultTimeout = 3.some,
      bootstrap = PeerNode(
        NodeIdentifier("de6eed5d00cf080fc587eeb412cb31a75fd10358"),
        Endpoint("52.119.8.253", 3, 3)
      ).some,
      standalone = true.some,
      mapSize = 3L.some,
      storeType = StoreType.Mixed.some,
      dataDir = Paths.get("test3").some,
      maxNumOfConnections = 3.some,
      maxMessageSize = 3.some,
      chunkSize = 3.some
    )
    val grpcServer = GrpcServer(
      host = "test3".some,
      socket = Paths.get("test3").some,
      portExternal = 3.some,
      portInternal = 3.some
    )
    val casper = Casper(
      publicKey = "test3".some,
      privateKey = "test3".some,
      privateKeyPath = Paths.get("test3").some,
      sigAlgorithm = "test3".some,
      bondsFile = "test3".some,
      knownValidatorsFile = "test3".some,
      numValidators = 3.some,
      genesisPath = Paths.get("test2").some,
      walletsFile = "test3".some,
      minimumBond = 3L.some,
      maximumBond = 3L.some,
      hasFaucet = true.some,
      requiredSigs = 3.some,
      shardId = "test3".some,
      approveGenesis = true.some,
      approveGenesisInterval = FiniteDuration(3, TimeUnit.SECONDS).some,
      approveGenesisDuration = FiniteDuration(3, TimeUnit.SECONDS).some,
      deployTimestamp = 3L.some
    )
    val tls = Tls(
      certificate = Paths.get("test3").some,
      key = Paths.get("test3").some,
      secureRandomNonBlocking = true.some
    )
    val lmdb = LmdbBlockStore(
      path = Paths.get("test2").some,
      blockStoreSize = 3L.some,
      maxDbs = 2.some,
      maxReaders = 2.some,
      useTls = false.some
    )
    val blockStorage = BlockDagFileStorage(
      latestMessagesLogPath = Paths.get("test2").some,
      latestMessagesCrcPath = Paths.get("test2").some,
      blockMetadataLogPath = Paths.get("test2").some,
      blockMetadataCrcPath = Paths.get("test2").some,
      checkpointsDirPath = Paths.get("test2").some,
      latestMessagesLogMaxSizeFactor = 2.some
    )
    ConfigurationSoft(
      server.some,
      grpcServer.some,
      tls.some,
      casper.some,
      lmdb.some,
      blockStorage.some
    )
  }

  test("ConfigurationSoft.parse should properly parse default config resources") {
    val Right(c) = ConfigurationSoft.parse(Array.empty[String])
    c shouldEqual expectedFromResources
  }

  test("ConfigurationSoft.parse should properly parse TOML --config-file") {
    writeTestConfigFile()
    val Right(c) = ConfigurationSoft.parse(Array("--config-file", configFilename))
    c shouldEqual expectedFromConfigFile
  }

  test("ConfigurationSoft.parse should properly parse CLI args") {
    writeTestConfigFile()
    val Right(c) = ConfigurationSoft.parse(cliArgs)
    c shouldEqual expectedFromCliArgs
  }

  test("ConfigurationSoft.parse should fallback field by field cliConf->confFile->default") {
    writeTestConfigFile("""
        |[grpc]
        |host = "localhost"
      """.stripMargin)
    val Right(c) =
      ConfigurationSoft.parse(Array("--config-file", configFilename, "--grpc-port=100", "run"))
    val expected =
      expectedFromResources.copy(
        grpc = expectedFromResources.grpc
          .map(g => g.copy(host = "localhost".some, portExternal = 100.some))
      )
    c shouldEqual expected
  }

  test("Configuration.adjustPath should adjust path of data dir changed") {
    val Some(updatedPath) = Configuration.adjustPath(
      ConfigurationSoft(
        Server(
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          Paths.get("another_test_dir").some,
          None,
          None,
          None
        ).some,
        None,
        Tls(
          None,
          None,
          None
        ).some,
        None,
        None,
        None
      ),
      Paths.get("default_test_dir/test").some,
      ConfigurationSoft(
        Server(
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          None,
          Paths.get("default_test_dir").some,
          None,
          None,
          None
        ).some,
        None,
        Tls(
          None,
          None,
          None
        ).some,
        None,
        None,
        None
      )
    )
    assert(updatedPath.endsWith("another_test_dir/test"))
  }

  def writeTestConfigFile(conf: String = rawConfigFile): Unit = Files.write(
    Paths.get(configFilename),
    conf.getBytes("UTF-8"),
    StandardOpenOption.CREATE_NEW
  )

  override protected def afterEach(): Unit = Try(Files.delete(Paths.get(configFilename)))
}
