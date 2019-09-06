package io.casperlabs.client.configuration
import com.google.protobuf.ByteString
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File, InputStream}
import java.nio.file.Files
import io.casperlabs.client.configuration.Options.DeployOptions
import io.casperlabs.casper.consensus.Deploy.{Arg, Code}, Code.Contract
import io.casperlabs.crypto.codec.Base16
import org.apache.commons.io._

final case class ConnectOptions(
    host: String,
    portExternal: Int,
    portInternal: Int,
    nodeId: Option[String]
)

/** Options to capture all the possible ways of passing one of the session or payment contracts. */
final case class CodeConfig(
    // Point at a file on disk.
    file: Option[File],
    // Hash of a stored contract.
    hash: Option[String],
    // Name of a stored contract.
    name: Option[String],
    // URef of a stored contract.
    uref: Option[String],
    // Arguments parsed from JSON
    args: Option[Seq[Arg]],
    // Name of a pre-packaged contract in the client JAR.
    resource: Option[String] = None
)
object CodeConfig {
  val empty = CodeConfig(None, None, None, None, None, None)
}

/** Encapsulate reading session and payment contracts from disk or resources
  * before putting them into the the format expected by the API.
  */
final case class DeployConfig(
    sessionOptions: CodeConfig,
    paymentOptions: CodeConfig,
    nonce: Long,
    gasPrice: Long,
    paymentAmount: Option[BigInt]
) {
  def session(defaultArgs: Seq[Arg] = Nil) = DeployConfig.toCode(sessionOptions, defaultArgs)
  def payment(defaultArgs: Seq[Arg] = Nil) = DeployConfig.toCode(paymentOptions, defaultArgs)

  def withSessionResource(resource: String) =
    copy(sessionOptions = sessionOptions.copy(resource = Some(resource)))
  def withPaymentResource(resource: String) =
    copy(paymentOptions = paymentOptions.copy(resource = Some(resource)))
}

object DeployConfig {
  def apply(args: DeployOptions): DeployConfig =
    DeployConfig(
      sessionOptions = CodeConfig(
        file = args.session.toOption,
        hash = args.sessionHash.toOption,
        name = args.sessionName.toOption,
        uref = args.sessionUref.toOption,
        args = args.sessionArgs.toOption.map(_.args)
      ),
      paymentOptions = CodeConfig(
        file = args.payment.toOption,
        hash = args.paymentHash.toOption,
        name = args.paymentName.toOption,
        uref = args.paymentUref.toOption,
        args = args.paymentArgs.toOption.map(_.args)
      ),
      nonce = args.nonce(),
      gasPrice = args.gasPrice(),
      paymentAmount = args.paymentAmount.toOption
    )

  val empty = DeployConfig(CodeConfig.empty, CodeConfig.empty, 0, 0, None)

  /** Produce a Deploy.Code DTO from the options.
    * 'defaultArgs' can be used by specialized commands such as `transfer` and `unbond`
    * to pass arguments they captured via dedicated CLI options, e.g. `--amount`, but
    * if the user sends explicit arguments via `--session-args` or `--payment-args`
    * they take precedence. This allows overriding the built-in contracts with custom ones.
    */
  private def toCode(opts: CodeConfig, defaultArgs: Seq[Arg]): Code = {
    val contract = opts.file.map { f =>
      val wasm = ByteString.copyFrom(Files.readAllBytes(f.toPath))
      Contract.Wasm(wasm)
    } orElse {
      opts.hash.map { x =>
        Contract.Hash(ByteString.copyFrom(Base16.decode(x)))
      }
    } orElse {
      opts.name.map { x =>
        Contract.Name(x)
      }
    } orElse {
      opts.uref.map { x =>
        Contract.Uref(ByteString.copyFrom(Base16.decode(x)))
      }
    } orElse {
      opts.resource.map { x =>
        val wasm =
          ByteString.copyFrom(consumeInputStream(getClass.getClassLoader.getResourceAsStream(x)))
        Contract.Wasm(wasm)
      }
    } getOrElse {
      Contract.Empty
    }

    val args = opts.args getOrElse defaultArgs

    Code(contract = contract, args = args)
  }

  private def consumeInputStream(is: InputStream): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    IOUtils.copy(is, baos)
    baos.toByteArray
  }
}

sealed trait Configuration

final case class MakeDeploy(
    from: Option[String],
    publicKey: Option[File],
    deployConfig: DeployConfig,
    deployPath: Option[File]
) extends Configuration

final case class SendDeploy(
    deploy: Array[Byte]
) extends Configuration

final case class Deploy(
    from: Option[String],
    deployConfig: DeployConfig,
    publicKey: Option[File],
    privateKey: Option[File]
) extends Configuration

/** Client command to sign a deploy.
  */
final case class Sign(
    deploy: Array[Byte],
    signedDeployPath: Option[File],
    publicKey: File,
    privateKey: File
) extends Configuration

final case object Propose extends Configuration

final case class ShowBlock(blockHash: String)   extends Configuration
final case class ShowDeploys(blockHash: String) extends Configuration
final case class ShowDeploy(deployHash: String) extends Configuration
final case class ShowBlocks(depth: Int)         extends Configuration
final case class Bond(
    amount: Long,
    deployConfig: DeployConfig,
    privateKey: File
) extends Configuration
final case class Transfer(
    amount: Long,
    recipientPublicKeyBase64: String,
    deployConfig: DeployConfig,
    privateKey: File
) extends Configuration
final case class Unbond(
    amount: Option[Long],
    deployConfig: DeployConfig,
    privateKey: File
) extends Configuration
final case class VisualizeDag(
    depth: Int,
    showJustificationLines: Boolean,
    out: Option[String],
    streaming: Option[Streaming]
) extends Configuration
final case class Balance(address: String, blockhash: String) extends Configuration

sealed trait Streaming extends Product with Serializable
object Streaming {
  final case object Single   extends Streaming
  final case object Multiple extends Streaming
}

final case class Query(
    blockHash: String,
    keyType: String,
    key: String,
    path: String
) extends Configuration

object Configuration {

  def parse(args: Array[String]): Option[(ConnectOptions, Configuration)] = {
    val options = Options(args)
    val connect = ConnectOptions(
      options.host(),
      options.port(),
      options.portInternal(),
      options.nodeId.toOption
    )
    val conf = options.subcommand.map {
      case options.deploy =>
        Deploy(
          options.deploy.from.toOption,
          DeployConfig(options.deploy),
          options.deploy.publicKey.toOption,
          options.deploy.privateKey.toOption
        )
      case options.makeDeploy =>
        MakeDeploy(
          options.makeDeploy.from.toOption,
          options.makeDeploy.publicKey.toOption,
          DeployConfig(options.makeDeploy),
          options.makeDeploy.deployPath.toOption
        )
      case options.sendDeploy =>
        SendDeploy(options.sendDeploy.deployPath())
      case options.signDeploy =>
        Sign(
          options.signDeploy.deployPath(),
          options.signDeploy.signedDeployPath.toOption,
          options.signDeploy.publicKey(),
          options.signDeploy.privateKey()
        )
      case options.propose =>
        Propose
      case options.showBlock =>
        ShowBlock(options.showBlock.hash())
      case options.showDeploys =>
        ShowDeploys(options.showDeploys.hash())
      case options.showDeploy =>
        ShowDeploy(options.showDeploy.hash())
      case options.showBlocks =>
        ShowBlocks(options.showBlocks.depth())
      case options.unbond =>
        Unbond(
          options.unbond.amount.toOption,
          DeployConfig(options.unbond),
          options.unbond.privateKey()
        )
      case options.bond =>
        Bond(
          options.bond.amount(),
          DeployConfig(options.bond),
          options.bond.privateKey()
        )
      case options.transfer =>
        Transfer(
          options.transfer.amount(),
          options.transfer.targetAccount(),
          DeployConfig(options.transfer),
          options.transfer.privateKey()
        )
      case options.visualizeBlocks =>
        VisualizeDag(
          options.visualizeBlocks.depth(),
          options.visualizeBlocks.showJustificationLines(),
          options.visualizeBlocks.out.toOption,
          options.visualizeBlocks.stream.toOption
        )
      case options.query =>
        Query(
          options.query.blockHash(),
          options.query.keyType(),
          options.query.key(),
          options.query.path()
        )
      case options.balance =>
        Balance(
          options.balance.address(),
          options.balance.blockHash()
        )
    }
    conf map (connect -> _)
  }
}
