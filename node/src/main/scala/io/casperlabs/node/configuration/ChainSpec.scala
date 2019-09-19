package io.casperlabs.node.configuration

import cats._
import cats.syntax._
import cats.implicits._
import cats.data.{Validated, ValidatedNel}
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.state
import io.casperlabs.configuration.SubConfig
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base64
import io.casperlabs.ipc
import java.io.File
import java.nio.file.{Path, Paths}
import scala.io.Source
import scala.util.Try
import io.casperlabs.node.configuration.ChainSpec.WasmCosts

/**
  * ChainSpec is the definition of the chain which we use for the Genesis process.
  * https://casperlabs.atlassian.net/wiki/spaces/EN/pages/133529693/Genesis+process+design+doc
  */
object ChainSpec extends ParserImplicits {

  /** The first set of changes should define the Genesis section and the costs. */
  final case class InitialConf(
      genesis: GenesisConf,
      wasmCosts: WasmCosts
  )

  object InitialConf {

    /** Parse the manifest. */
    def parse(manifest: => Source): ValidatedNel[String, InitialConf] =
      Utils.readFile(manifest).toValidatedNel[String].andThen { raw =>
        ConfParser
          .gen[InitialConf]
          .parse(
            cliByName = _ => None,
            envVars = Map.empty,
            configFile = None,
            defaultConfigFile = Utils.parseToml(raw),
            pathToField = Nil
          )
      }
  }

  final case class GenesisConf(
      name: String,
      timestamp: Long,
      mintCodePath: Path,
      posCodePath: Path,
      initialAccountsPath: Path,
      // TODO: Change this later to semver.
      protocolVersion: Long
  ) extends SubConfig

  final case class WasmCosts(
      regular: Int Refined NonNegative,
      divMultiplier: Int Refined NonNegative,
      mulMultiplier: Int Refined NonNegative,
      memMultiplier: Int Refined NonNegative,
      memInitialPages: Int Refined NonNegative,
      memGrowPerPage: Int Refined NonNegative,
      memCopyPerByte: Int Refined NonNegative,
      maxStackHeight: Int Refined NonNegative,
      opcodesMultiplier: Int Refined NonNegative,
      opcodesDivisor: Int Refined NonNegative
  ) extends SubConfig

  final case class Account(
      publicKey: PublicKey,
      initialBalance: BigInt,
      initialBondedAmount: BigInt
  )

  object Accounts {
    def parseCsv(raw: String, skipHeader: Boolean = false): Either[String, List[Account]] =
      raw
        .split('\n')
        .drop(if (skipHeader) 1 else 0)
        .filterNot(_.isEmpty)
        .map { line =>
          line.split(',') match {
            case Array(publicKeyBase64, balanceStr, bondedAmountStr) =>
              for {
                publicKey    <- parsePublicKey(publicKeyBase64)
                balance      <- parseBigInt(balanceStr)
                bondedAmount <- parseBigInt(bondedAmountStr)
              } yield Account(publicKey, balance, bondedAmount)

            case _ =>
              s"Could not parse line into an Account: $line".asLeft[Account]
          }
        }
        .toList
        .sequence

    private def parsePublicKey(publicKeyBase64: String) =
      Base64.tryDecode(publicKeyBase64) match {
        case None        => s"Could not decode public key as Base64: $publicKeyBase64".asLeft[PublicKey]
        case Some(bytes) => PublicKey(bytes).asRight[String]
      }

    private def parseBigInt(amount: String) =
      Try(BigInt(amount)).fold(
        _ => s"Could not parse amount: $amount".asLeft[BigInt],
        i => i.asRight[String]
      )
  }
}

trait ChainSpecReader {
  import ChainSpec.{Accounts, InitialConf}

  implicit class ChainSpecOps(typ: ipc.ChainSpec.type) {

    /** Parse and read the contents of a chainspec directory into the IPC DTOs. */
    def fromDirectory(path: Path): ValidatedNel[String, ipc.ChainSpec] = {
      val dir = path.toFile
      if (!dir.exists)
        Validated.invalidNel(s"Chain spec directory '$path' does not exist!")
      else if (!dir.isDirectory)
        Validated.invalidNel(s"Chain spec path '$path' is not a directory!")
      else {
        // Consider each subdirectory an upgrade, starting with Genesis.
        val changesets = dir.listFiles.sortBy(_.getName).toList

        changesets match {
          case Nil =>
            Validated.invalidNel(s"Chain spec directory '$path' is empty!")

          case genesisDir :: upgradeDirs =>
            val genesis = ipc.ChainSpec.GenesisConfig.fromDirectory(genesisDir.toPath)
            val upgrades = upgradeDirs.map { dir =>
              ipc.ChainSpec.UpgradePoint.fromDirectory(dir.toPath)
            }

            genesis andThen { g =>
              upgrades.sequence map { us =>
                ipc.ChainSpec().withGenesis(g).withUpgrades(us)
              }
            }
        }
      }
    }
  }

  implicit class GenesisConfigOps(typ: ipc.ChainSpec.GenesisConfig.type) {
    def fromDirectory(path: Path): ValidatedNel[String, ipc.ChainSpec.GenesisConfig] = {
      val manifest = new File(path.toFile, "manifest.toml")
      if (!manifest.exists)
        Validated.invalidNel(s"Manifest file '$manifest' is missing!")
      else {
        InitialConf.parse(Source.fromFile(manifest)) andThen {
          case InitialConf(genesis, wasmCosts) =>
            val tryRead = for {
              mintCodeBytes <- Utils.readBytes(path.resolve(genesis.mintCodePath))
              posCodeBytes  <- Utils.readBytes(path.resolve(genesis.posCodePath))
              accountsCsv   <- Utils.readFile(path.resolve(genesis.initialAccountsPath))
              accounts      <- Accounts.parseCsv(accountsCsv, skipHeader = false)
            } yield {
              ipc.ChainSpec
                .GenesisConfig()
                .withName(genesis.name)
                .withTimestamp(genesis.timestamp)
                .withProtocolVersion(state.ProtocolVersion(genesis.protocolVersion))
                .withMintInstaller(ByteString.copyFrom(mintCodeBytes))
                .withPosInstaller(ByteString.copyFrom(posCodeBytes))
                .withAccounts(accounts.map { account =>
                  ipc.ChainSpec
                    .GenesisAccount()
                    .withPublicKey(ByteString.copyFrom(account.publicKey))
                    .withBalance(state.BigInt(account.initialBalance.toString, bitWidth = 512))
                    .withBondedAmount(
                      state.BigInt(account.initialBondedAmount.toString, bitWidth = 512)
                    )
                })
                .withCosts(ipc.ChainSpec.CostTable.fromConfig(wasmCosts))
            }
            tryRead
              .leftMap(err => s"Could not parse Genesis from directory $path: $err")
              .toValidatedNel
        }
      }
    }
  }

  implicit class CostTableOps(typ: ipc.ChainSpec.CostTable.type) {
    def fromConfig(wasmCosts: WasmCosts): ipc.ChainSpec.CostTable =
      ipc.ChainSpec
        .CostTable()
        .withWasm(
          ipc.ChainSpec.CostTable
            .WasmCosts()
            .withRegular(wasmCosts.regular.value)
            .withDiv(wasmCosts.divMultiplier.value)
            .withMul(wasmCosts.mulMultiplier.value)
            .withMem(wasmCosts.memMultiplier.value)
            .withInitialMem(wasmCosts.memInitialPages.value)
            .withGrowMem(wasmCosts.memGrowPerPage.value)
            .withMemcpy(wasmCosts.memCopyPerByte.value)
            .withMaxStackHeight(wasmCosts.maxStackHeight.value)
            .withOpcodesMul(wasmCosts.opcodesMultiplier.value)
            .withOpcodesDiv(wasmCosts.opcodesDivisor.value)
        )
  }

  implicit class UpgradePointOps(typ: ipc.ChainSpec.UpgradePoint.type) {
    def fromDirectory(path: Path): ValidatedNel[String, ipc.ChainSpec.UpgradePoint] =
      sys.error(s"Did not expect upgrades yet: $path")
  }
}
