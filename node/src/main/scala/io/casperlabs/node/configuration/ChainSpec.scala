package io.casperlabs.node.configuration

import cats._
import cats.syntax._
import cats.implicits._
import cats.data.{Validated, ValidatedNel}
import com.google.protobuf.ByteString
import com.google.common.io.Resources
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.state
import io.casperlabs.configuration.SubConfig
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base64
import io.casperlabs.ipc
import java.io.{ByteArrayOutputStream, File}
import java.nio.file.{Files, Path, Paths}
import java.util.stream.Collectors
import java.util.jar.JarFile
import org.apache.commons.io.IOUtils
import scala.io.Source
import scala.util.Try
import scala.collection.JavaConverters._
import simulacrum.typeclass

/**
  * ChainSpec is the definition of the chain which we use for the Genesis process.
  * https://casperlabs.atlassian.net/wiki/spaces/EN/pages/133529693/Genesis+process+design+doc
  */
object ChainSpec extends ParserImplicits {

  class ConfCompanion[T](confParser: ConfParser[T]) {
    def parseManifest(manifest: => Source): ValidatedNel[String, T] =
      Utils.readFile(manifest).toValidatedNel[String].andThen { raw =>
        confParser
          .parse(
            cliByName = _ => None,
            envVars = Map.empty,
            configFile = None,
            defaultConfigFile = Utils.parseToml(raw),
            pathToField = Nil
          )
      }
  }

  final case class ProtocolVersion(
      major: Int,
      minor: Int,
      patch: Int
  )

  final case class Deploy(
      maxTtlMillis: Int Refined NonNegative,
      maxDependencies: Int Refined NonNegative
  ) extends SubConfig

  /** The first set of changes should define the Genesis section and the costs. */
  final case class GenesisConf(
      genesis: Genesis,
      wasmCosts: WasmCosts,
      deploys: Deploy
  )
  object GenesisConf extends ConfCompanion[GenesisConf](ConfParser.gen[GenesisConf])

  /** Subsequent changes describe upgrades. */
  final case class UpgradeConf(
      upgrade: Upgrade,
      wasmCosts: Option[WasmCosts],
      deploys: Option[Deploy]
  )
  object UpgradeConf extends ConfCompanion[UpgradeConf](ConfParser.gen[UpgradeConf])

  final case class Genesis(
      name: String,
      timestamp: Long,
      mintCodePath: Path,
      posCodePath: Path,
      initialAccountsPath: Path,
      protocolVersion: ProtocolVersion
  ) extends SubConfig

  final case class Upgrade(
      activationPointRank: Long,
      installerCodePath: Option[Path],
      protocolVersion: ProtocolVersion
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
      opcodesDivisor: Int Refined Positive
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

/** Resolve a path to its contents. */
trait Resolver {
  def asBytes(path: Path): Either[String, Array[Byte]]
  def asSource(path: Path): Either[String, Source]
  def asString(path: Path): Either[String, String]
  def listFiles(path: Path): List[Path]
}

/** Resolve to normal files. */
object FileResolver extends Resolver {
  override def asBytes(path: Path) =
    Utils.readBytes(path)

  override def asSource(path: Path) =
    if (!path.toFile.exists)
      s"File '$path' is missing!".asLeft[Source]
    else
      Source.fromFile(path.toFile).asRight[String]

  override def asString(path: Path) =
    Utils.readFile(path)

  override def listFiles(path: Path) =
    path.toFile.listFiles.sortBy(_.getName).map(_.toPath).toList
}

/** Resolve paths in resources, unless an override in the data directory exists. */
class ResourceResolver(dataDir: Path) extends Resolver {
  override def asBytes(path: Path) =
    read(path, readResourceBytes, FileResolver.asBytes)

  override def asSource(path: Path) =
    read(path, x => Source.fromResource(x.toString), FileResolver.asSource)

  override def asString(path: Path) =
    asSource(path).flatMap(src => Utils.readFile(src))

  override def listFiles(path: Path) =
    ResourceResolver.listFilesInResources(path)

  private def read[T](
      path: Path,
      fromResource: Path => T,
      fromFile: Path => Either[String, T]
  ): Either[String, T] =
    if (path.isAbsolute) {
      fromFile(path)
    } else {
      val over = dataDir.resolve(path).toFile
      if (over.exists) fromFile(over.toPath)
      else
        Try(fromResource(path)).fold(
          ex => s"Cannot read resource $path: ${ex.getMessage}".asLeft[T],
          x => x.asRight[String]
        )
    }

  private def readResourceBytes(path: Path) = {
    val in  = getClass.getClassLoader.getResourceAsStream(path.toString)
    val out = new ByteArrayOutputStream()
    try {
      IOUtils.copy(in, out)
      out.toByteArray
    } finally {
      in.close()
      out.close()
    }
  }
}
object ResourceResolver {

  /** List files in a directory which is packaged in the JAR, or is in the resources directory. */
  def listFilesInResources(path: Path): List[Path] = {
    val root = Paths.get(Resources.getResource(path.toString).getPath)
    if (root.startsWith("file:") && root.toString.contains(".jar!")) {
      // This happens when we packaged the app.
      val jarFile = new File(getClass.getProtectionDomain.getCodeSource.getLocation.getPath)
      val jar     = new JarFile(jarFile)

      try {
        jar.entries.asScala
          .map(entry => Paths.get(entry.getName))
          .filter(_.getParent == path)
          .toList
      } finally {
        jar.close()
      }
    } else {
      // This works in tests.
      Files
        .list(root)
        .map[Path](root.getParent.relativize(_))
        .collect(Collectors.toList[Path]())
        .asScala
        .sorted
        .toList
    }
  }
}

@typeclass
trait ChainSpecReader[T] {
  def fromDirectory(path: Path)(implicit resolver: Resolver): ValidatedNel[String, T]
}

object ChainSpecReader {
  import ChainSpec._

  /** Normally we expect files to be relative to the directory where the update is,
    * but it's possible someone would locally want to re-point it to an absolute path.
    */
  def resolvePath(dir: Path, file: Path): Path =
    if (file.startsWith(Paths.get("~/")))
      Paths.get(sys.props("user.home")).resolve(file.toString.drop(2))
    else dir.resolve(file)

  implicit val `ChainSpecReader[GenesisConfig]` = new ChainSpecReader[ipc.ChainSpec.GenesisConfig] {
    override def fromDirectory(path: Path)(implicit resolver: Resolver) =
      withManifest[GenesisConf, ipc.ChainSpec.GenesisConfig](path, GenesisConf.parseManifest) {
        case GenesisConf(genesis, wasmCosts, deployConfig) =>
          for {
            mintCodeBytes <- resolver.asBytes(resolvePath(path, genesis.mintCodePath))
            posCodeBytes  <- resolver.asBytes(resolvePath(path, genesis.posCodePath))
            accountsCsv   <- resolver.asString(resolvePath(path, genesis.initialAccountsPath))
            accounts      <- Accounts.parseCsv(accountsCsv, skipHeader = false)
          } yield {
            ipc.ChainSpec
              .GenesisConfig()
              .withName(genesis.name)
              .withTimestamp(genesis.timestamp)
              .withProtocolVersion(
                (state.ProtocolVersion.apply _).tupled(
                  ProtocolVersion.unapply(genesis.protocolVersion).get
                )
              )
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
              .withCosts(toCostTable(wasmCosts))
              .withDeployConfig(toDeployConfig(deployConfig))
          }
      }
  }

  implicit val `ChainSpecReader[UpgradePoint]` = new ChainSpecReader[ipc.ChainSpec.UpgradePoint] {
    override def fromDirectory(path: Path)(implicit resolver: Resolver) =
      withManifest[UpgradeConf, ipc.ChainSpec.UpgradePoint](path, UpgradeConf.parseManifest) {
        case UpgradeConf(upgrade, maybeWasmCosts, maybeDeployConfig) =>
          upgrade.installerCodePath.fold(
            none[Array[Byte]].asRight[String]
          ) { file =>
            resolver.asBytes(resolvePath(path, file)).map(_.some)
          } map { maybeInstallerCodeBytes =>
            ipc.ChainSpec
              .UpgradePoint(
                upgradeInstaller = maybeInstallerCodeBytes.map { bytes =>
                  ipc.DeployCode(
                    code = ByteString.copyFrom(bytes)
                  )
                },
                newCosts = maybeWasmCosts.map(toCostTable),
                newDeployConfig = maybeDeployConfig.map(toDeployConfig)
              )
              .withActivationPoint(
                ipc.ChainSpec.ActivationPoint(upgrade.activationPointRank)
              )
              .withProtocolVersion(
                (state.ProtocolVersion.apply _).tupled(
                  ProtocolVersion.unapply(upgrade.protocolVersion).get
                )
              )
          }
      }
  }

  implicit val `ChainSpecReader[ChainSpec]` = new ChainSpecReader[ipc.ChainSpec] {
    override def fromDirectory(path: Path)(implicit resolver: Resolver) = {
      val changesets = resolver.listFiles(path)

      changesets match {
        case Nil =>
          Validated.invalidNel(s"Chain spec directory '$path' is empty!")

        case genesisDir :: upgradeDirs =>
          val genesis = ChainSpecReader[ipc.ChainSpec.GenesisConfig].fromDirectory(genesisDir)
          val upgrades = upgradeDirs.map { dir =>
            ChainSpecReader[ipc.ChainSpec.UpgradePoint].fromDirectory(dir)
          }

          genesis andThen { g =>
            upgrades.sequence map { us =>
              ipc.ChainSpec().withGenesis(g).withUpgrades(us)
            }
          }
      }
    }
  }

  private def toCostTable(wasmCosts: WasmCosts): ipc.ChainSpec.CostTable =
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

  private def toDeployConfig(deployConfig: Deploy): ipc.ChainSpec.DeployConfig =
    ipc.ChainSpec.DeployConfig(
      deployConfig.maxTtlMillis.value,
      deployConfig.maxDependencies.value
    )

  private def withManifest[A, B](dir: Path, parseManifest: (=> Source) => ValidatedNel[String, A])(
      read: A => Either[String, B]
  )(implicit resolver: Resolver): ValidatedNel[String, B] = {
    val path = resolvePath(dir, Paths.get("manifest.toml"))
    resolver.asSource(path).toValidatedNel andThen { src =>
      parseManifest(src) andThen { conf =>
        read(conf)
          .leftMap(err => s"Could not read chainspec sub-directory $dir: $err")
          .toValidatedNel
      }
    }
  }

  /** If the user installed the software under Unix then they'll have standard
    * libraries created and the chainspec copied to /etc/casperlabs; if present,
    * use it, unless an explicit setting is pointing the node somewhere else.
    * If there's no explicit ChainSpec location defined we can use the default one
    * packaged with the node. Every file can be overridden by placing one with the
    * same path under the ~/.casperlabs data directory.
    */
  def fromConf(
      conf: Configuration
  ): ValidatedNel[String, ipc.ChainSpec] = {
    val maybeEtcPath =
      Option(Paths.get("/", "etc", "casperlabs", "chainspec")).filter(_.toFile.exists)

    // The node comes default settings for devnet packaged in the JAR. If it's installed,
    // these get unpacked by the installer to /etc/casperlabs/chainspec.
    // If the user sets the `--casper-chain-spec-path` to a directory, that means they
    // are providing a full ChainSpec, for example to connect to testnet or mainnet,
    // instead of devnet; in this case ignore everything else, this takes priority.
    conf.casper.chainSpecPath orElse maybeEtcPath match {
      case Some(path) =>
        val dir = path.toFile
        if (!dir.exists)
          Validated.invalidNel(s"Chain spec directory '$path' does not exist!")
        else if (!dir.isDirectory)
          Validated.invalidNel(s"Chain spec path '$path' is not a directory!")
        else {
          implicit val resolver = FileResolver
          ChainSpecReader[ipc.ChainSpec].fromDirectory(path)
        }

      case None =>
        // No dedicated ChainSpec directory given, so use the `chainspec` directory
        // as it exists under `resources`, packaged in the JAR.
        implicit val resolver = new ResourceResolver(conf.server.dataDir)
        ChainSpecReader[ipc.ChainSpec].fromDirectory(Paths.get("chainspec"))
    }
  }
}
