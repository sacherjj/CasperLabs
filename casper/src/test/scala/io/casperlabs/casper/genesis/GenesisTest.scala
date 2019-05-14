package io.casperlabs.casper.genesis

import java.io.PrintWriter
import java.nio.file.{Files, Path, Paths}

import cats.Id
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.casper.helper.{
  BlockDagStorageFixture,
  BlockGenerator,
  BlockUtil,
  HashSetCasperTestNode
}
import io.casperlabs.casper.protocol.{BlockMessage, Bond}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.p2p.EffectsTestInstances.{LogStub, LogicalTime}
import io.casperlabs.shared.PathOps.RichPath
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}
import io.casperlabs.shared.StoreType
import io.casperlabs.smartcontracts.{ExecutionEngineService, GrpcExecutionEngineService}
import cats.effect.Sync
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.models.BlockMetadata
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task

class GenesisTest extends FlatSpec with Matchers with BlockDagStorageFixture {
  import GenesisTest._

  val validators = Seq(
    "299670c52849f1aa82e8dfe5be872c16b600bf09cc8983e04b903411358f2de6",
    "6bf1b2753501d02d386789506a6d93681d2299c6edfd4455f596b97bc5725968"
  ).zipWithIndex

  val walletAddresses = Seq(
    "0x20356b6fae3a94db5f01bdd45347faFad3dd18ef",
    "0x041e1eec23d118f0c4ffc814d4f415ac3ef3dcff"
  ).zipWithIndex

  def printBonds(bondsFile: String): Unit = {
    val pw = new PrintWriter(bondsFile)
    pw.println(
      validators
        .map {
          case (v, i) => s"$v $i"
        }
        .mkString("\n")
    )
    pw.close()
  }

  def printWallets(walletsFile: String): Unit = {
    val pw = new PrintWriter(walletsFile)
    pw.println(
      walletAddresses
        .map {
          case (v, i) => s"$v,$i,0"
        }
        .mkString("\n")
    )
    pw.close()
  }

  "Genesis.getBonds" should "generate random validators when no bonds file is given" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      for {
        _      <- fromBondsFile(genesisPath)(executionEngineService, log, time)
        _      = log.warns.find(_.contains("bonds")) should be(None)
        result = log.infos.count(_.contains("Created validator")) should be(numValidators)
      } yield result
  }

  it should "generate random validators, with a warning, when bonds file does not exist" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      for {
        _ <- fromBondsFile(genesisPath)(
              executionEngineService,
              log,
              time
            )
        _ = log.warns.count(
          _.contains("does not exist. Falling back on generating random validators.")
        ) should be(
          1
        )
        result = log.infos.count(_.contains("Created validator")) should be(numValidators)
      } yield result
  }

  it should "generate random validators, with a warning, when bonds file cannot be parsed" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      val badBondsFile = genesisPath.resolve("misformatted.txt")

      val pw = new PrintWriter(badBondsFile.toString)
      pw.println("xzy 1\nabc 123 7")
      pw.close()

      for {
        _ <- fromBondsFile(genesisPath, badBondsFile)(
              executionEngineService,
              log,
              time
            )
        _ = log.warns.count(
          _.contains("cannot be parsed. Falling back on generating random validators.")
        ) should be(
          1
        )
        result = log.infos.count(_.contains("Created validator")) should be(numValidators)
      } yield result
  }

  it should "create a genesis block with the right bonds when a proper bonds file is given" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      val bondsFile = genesisPath.resolve("givenBonds.txt")
      printBonds(bondsFile.toString)

      for {
        genesisWithTransform <- fromBondsFile(genesisPath, bondsFile)(
                                 executionEngineService,
                                 log,
                                 time
                               )
        BlockMsgWithTransform(Some(genesis), _) = genesisWithTransform
        bonds                                   = ProtoUtil.bonds(genesis)
        _                                       = log.infos.isEmpty should be(true)
        result = validators
          .map {
            case (v, i) => Bond(ByteString.copyFrom(Base16.decode(v)), i.toLong)
          }
          .forall(
            bonds.contains(_)
          ) should be(true)
      } yield result
  }

  it should "create a valid genesis block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      withGenResources {
        (
            executionEngineService: ExecutionEngineService[Task],
            genesisPath: Path,
            log: LogStub[Task],
            time: LogicalTime[Task]
        ) =>
          implicit val logEff                    = log
          implicit val executionEngineServiceEff = executionEngineService
          for {
            genesisWithTransform                             <- fromBondsFile(genesisPath)(executionEngineService, log, time)
            BlockMsgWithTransform(Some(genesis), transforms) = genesisWithTransform
            _ <- BlockStore[Task]
                  .put(genesis.blockHash, genesis, transforms)
            dag <- blockDagStorage.getRepresentation
            maybePostGenesisStateHash <- ExecutionEngineServiceStub
                                          .validateBlockCheckpoint[Task](
                                            genesis,
                                            dag
                                          )
          } yield maybePostGenesisStateHash shouldBe 'right
      }
  }

  it should "detect an existing bonds file in the default location" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task],
        time: LogicalTime[Task]
    ) =>
      val bondsFile = genesisPath.resolve("bonds.txt").toString
      printBonds(bondsFile)

      for {
        genesisWithTransform                    <- fromBondsFile(genesisPath)(executionEngineService, log, time)
        BlockMsgWithTransform(Some(genesis), _) = genesisWithTransform
        bonds                                   = ProtoUtil.bonds(genesis)
        _                                       = log.infos.length should be(1)
        result = validators
          .map {
            case (v, i) => Bond(ByteString.copyFrom(Base16.decode(v)), i.toLong)
          }
          .forall(
            bonds.contains(_)
          ) should be(true)
      } yield result
  }
}

object GenesisTest {
  val nonExistentPath   = Paths.get("/a/b/c/d/e/f/g")
  val storageSize       = 1024L * 1024
  def mkStoragePath     = Files.createTempDirectory(s"casper-genesis-test-runtime")
  def mkGenesisPath     = Files.createTempDirectory(s"casper-genesis-test")
  val numValidators     = 5
  val casperlabsShardId = "casperlabs"

  def fromBondsFile(
      genesisPath: Path,
      bondsPath: Path = nonExistentPath
  )(
      implicit executionEngineService: ExecutionEngineService[Task],
      log: LogStub[Task],
      time: LogicalTime[Task]
  ): Task[BlockMsgWithTransform] =
    for {
      bonds <- Genesis.getBonds[Task](genesisPath, bondsPath, numValidators)
      _     <- ExecutionEngineService[Task].setBonds(bonds)
      genesis <- Genesis[Task](
                  walletsPath = nonExistentPath,
                  minimumBond = 1L,
                  maximumBond = Long.MaxValue,
                  faucet = false,
                  shardId = casperlabsShardId,
                  deployTimestamp = Some(System.currentTimeMillis)
                )
    } yield genesis

  def withGenResources(
      body: (ExecutionEngineService[Task], Path, LogStub[Task], LogicalTime[Task]) => Task[Unit]
  ): Task[Unit] = {
    val storagePath             = mkStoragePath
    val genesisPath             = mkGenesisPath
    val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
    val log                     = new LogStub[Task]
    val time                    = new LogicalTime[Task]

    for {
      result <- body(casperSmartContractsApi, genesisPath, log, time)
      _      <- Sync[Task].delay { storagePath.recursivelyDelete() }
      _      <- Sync[Task].delay { genesisPath.recursivelyDelete() }
    } yield result
  }
}
