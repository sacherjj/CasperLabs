package io.casperlabs.casper.genesis

import java.io.PrintWriter
import java.nio.file.{Files, Path, Paths}
import java.util.Base64

import cats.effect.Sync
import cats.implicits._
import io.casperlabs.blockstorage.BlockStorage
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.helper.{DagStorageFixture, HashSetCasperTestNode}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.crypto.Keys
import io.casperlabs.ipc.GenesisRequest
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.shared.{FilesAPI, Log}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

class GenesisTest extends FlatSpec with Matchers with DagStorageFixture {
  import GenesisTest._

  it should "throw exception when bonds file does not exist" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        _: Path,
        log: LogStub[Task]
    ) =>
      for {
        r <- fromBondsFile(nonExistentPath)(
              executionEngineService,
              log
            ).attempt
      } yield {
        log.errors.count(
          _.contains(s"Specified bonds file ${nonExistentPath} does not exist.")
        ) should be(
          1
        )
        r.isLeft shouldBe true
        r.left.get shouldBe an[IllegalArgumentException]
      }
  }

  it should "throw exception when bonds file cannot be parsed" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task]
    ) =>
      val badBondsFile = genesisPath.resolve("misformatted.txt")

      val pw = new PrintWriter(badBondsFile.toString)
      pw.println("xzy 1\nabc 123 7")
      pw.close()

      for {
        r <- fromBondsFile(badBondsFile)(
              executionEngineService,
              log
            ).attempt
      } yield {
        log.errors.count(
          _.contains(s"Bonds file ${badBondsFile} cannot be parsed.")
        ) should be(
          1
        )
        r.isLeft shouldBe true
        r.left.get shouldBe an[IllegalArgumentException]
      }
  }

  it should "create a genesis block with the right bonds when a proper bonds file is given" in withGenResources {
    (
        executionEngineService: ExecutionEngineService[Task],
        genesisPath: Path,
        log: LogStub[Task]
    ) =>
      val bondsFile = genesisPath.resolve("givenBonds.txt")
      printBonds(bondsFile.toString)

      for {
        genesisWithTransform <- fromBondsFile(bondsFile)(
                                 executionEngineService,
                                 log
                               )
        BlockMsgWithTransform(Some(genesis), _) = genesisWithTransform
        bonds                                   = ProtoUtil.bonds(genesis)
      } yield {
        val fromGenesis =
          bonds.map(
            b => (Base64.getEncoder().encodeToString(b.validatorPublicKey.toByteArray()), b.stake)
          )
        fromGenesis should contain theSameElementsAs validators
      }
  }

  it should "create a valid genesis block" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      Task.delay(
        withGenResources {
          (
              executionEngineService: ExecutionEngineService[Task],
              genesisPath: Path,
              log: LogStub[Task]
          ) =>
            val bondsFile = genesisPath.resolve("bonds.txt")
            printBonds(bondsFile.toString)
            implicit val logEff                    = log
            implicit val executionEngineServiceEff = executionEngineService
            for {
              genesisWithTransform <- fromBondsFile(bondsFile)(
                                       executionEngineService,
                                       log
                                     )
              BlockMsgWithTransform(Some(genesis), transforms) = genesisWithTransform
              _ <- BlockStorage[Task]
                    .put(genesis.blockHash, genesis, transforms)
              dag <- dagStorage.getRepresentation
              maybePostGenesisStateHash <- ExecutionEngineServiceStub
                                            .validateBlockCheckpoint[Task](
                                              genesis,
                                              dag
                                            )
            } yield maybePostGenesisStateHash shouldBe 'right
        }
      )
  }

  it should "prepare a GenesisRequest" in withStorage { _ => _ =>
    Task.delay(
      withGenResources {
        (
            executionEngineService: ExecutionEngineService[Task],
            genesisPath: Path,
            log: LogStub[Task]
        ) =>
          val bondsFile = genesisPath.resolve("bonds.txt")
          val mintFile  = genesisPath.resolve("mint.wasm")
          val posFile   = genesisPath.resolve("pos.wasm")
          val keyFile   = genesisPath.resolve("account.pem")

          printBonds(bondsFile.toString)
          printFile(mintFile, "mint code")
          printFile(posFile, "proof of stake code")
          printFile(
            keyFile,
            """
              |-----BEGIN PUBLIC KEY-----
              |MCowBQYDK2VwAyEAhRAJx+krVtJQ3+jRzE5HMAheSn7YzzPVBDMgyJQdUq0=
              |-----END PUBLIC KEY-----
              """.stripMargin('|').trim
          )

          implicit val logEff                    = log
          implicit val executionEngineServiceEff = executionEngineService
          implicit val filesApi                  = FilesAPI.create[Task]

          for {
            genesisWithTransform <- Genesis[Task](
                                     walletsPath = nonExistentPath,
                                     bondsPath = bondsFile,
                                     minimumBond = 1L,
                                     maximumBond = Long.MaxValue,
                                     chainId = casperlabsChainId,
                                     deployTimestamp = System.currentTimeMillis.some,
                                     accountPublicKeyPath = keyFile.some,
                                     initialTokens = BigInt(123),
                                     mintCodePath = mintFile.some,
                                     posCodePath = posFile.some
                                   )
            BlockMsgWithTransform(Some(genesis), transforms) = genesisWithTransform

            request = GenesisRequest.parseFrom(
              genesis.getBody.deploys.head.getDeploy.getBody.getSession.code.toByteArray
            )
          } yield {
            request.initialMotes.get shouldBe state.BigInt("123", 512)
            request.mintCode.get.code.toByteArray shouldBe ("mint code".getBytes)
            request.proofOfStakeCode.get.code.toByteArray shouldBe ("proof of stake code".getBytes)
            request.protocolVersion.get.value shouldBe 1L
          }
      }
    )
  }
}

object GenesisTest {
  val nonExistentPath   = Paths.get("/a/b/c/d/e/f/g")
  val storageSize       = 1024L * 1024
  def mkStoragePath     = Files.createTempDirectory(s"casper-genesis-test-runtime")
  def mkGenesisPath     = Files.createTempDirectory(s"casper-genesis-test")
  val casperlabsChainId = "casperlabs"

  val validators = Seq(
    "KZZwxShJ8aqC6N/lvocsFrYAvwnMiYPgS5A0ETWPLeY=",
    "a/GydTUB0C04Z4lQam2TaB0imcbt/URV9Za5e8VyWWg="
  ).zipWithIndex.toMap

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

  def printFile(path: Path, content: String): Unit = {
    val pw = new PrintWriter(path.toString)
    pw.print(content)
    pw.close()
  }

  implicit def filesApi(implicit log: Log[Task]) =
    FilesAPI.create[Task]

  def fromBondsFile(bondsPath: Path)(
      implicit executionEngineService: ExecutionEngineService[Task],
      log: LogStub[Task]
  ): Task[BlockMsgWithTransform] =
    for {
      genesis <- Genesis[Task](
                  walletsPath = nonExistentPath,
                  bondsPath = bondsPath,
                  minimumBond = 1L,
                  maximumBond = Long.MaxValue,
                  chainId = casperlabsChainId,
                  deployTimestamp = System.currentTimeMillis.some,
                  accountPublicKeyPath = none[Path],
                  initialTokens = BigInt(0),
                  mintCodePath = none[Path],
                  posCodePath = none[Path]
                )
    } yield genesis

  def withGenResources(
      body: (ExecutionEngineService[Task], Path, LogStub[Task]) => Task[Unit]
  ): Unit = {
    val storagePath = mkStoragePath
    val genesisPath = mkGenesisPath
    val validatorsMap =
      validators.map(b => (Keys.PublicKey(java.util.Base64.getDecoder.decode(b._1)), b._2.toLong));
    val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task](validatorsMap)
    val log                     = new LogStub[Task]

    val task = for {
      result <- body(casperSmartContractsApi, genesisPath, log)
      _      <- Sync[Task].delay { storagePath.recursivelyDelete() }
      _      <- Sync[Task].delay { genesisPath.recursivelyDelete() }
    } yield result
    import monix.execution.Scheduler.Implicits.global
    import monix.execution.schedulers.CanBlock.permit

    import scala.concurrent.duration._
    task.runSyncUnsafe(15.seconds)
  }
}
