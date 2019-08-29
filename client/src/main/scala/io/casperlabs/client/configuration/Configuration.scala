package io.casperlabs.client.configuration
import com.google.protobuf.ByteString
import java.io.{ByteArrayInputStream, ByteArrayOutputStream, File, InputStream}
import java.nio.file.Files
import io.casperlabs.client.configuration.Options.ContractArgs
import io.casperlabs.casper.consensus.Deploy.Code.Contract
import io.casperlabs.crypto.codec.Base16
import org.apache.commons.io._

final case class ConnectOptions(
    host: String,
    portExternal: Int,
    portInternal: Int,
    nodeId: Option[String]
)

/** Options to capture all the possible ways of passing one of the session or payment contracts. */
final case class ContractOptions(
    // Point at a file on disk.
    file: Option[File],
    // Name of a pre-packaged contract in the client JAR.
    resource: Option[String] = None,
    // Hash of a stored contract.
    hash: Option[String] = None,
    // Name of a stored contract.
    name: Option[String] = None,
    // URef of a stored contract.
    uref: Option[String] = None
)

/** Encapsulate reading session and payment contracts from disk or resources
  * before putting them into the the format expected by the API.
  */
final case class Contracts(
    sessionOptions: ContractOptions,
    paymentOptions: ContractOptions
) {
  lazy val session = Contracts.toContract(sessionOptions)
  lazy val payment = Contracts.toContract(paymentOptions)

  def withSessionResource(resource: String) =
    copy(sessionOptions = sessionOptions.copy(resource = Some(resource)))
}

object Contracts {
  def apply(args: ContractArgs): Contracts =
    Contracts(
      ContractOptions(args.session.toOption),
      ContractOptions(args.payment.toOption)
    )

  val empty = Contracts(ContractOptions(None), ContractOptions(None))

  /** Produce a Deploy.Code.Contract DTO from the options. */
  private def toContract(opts: ContractOptions): Contract =
    opts.file.map { f =>
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
    nonce: Long,
    contracts: Contracts,
    gasPrice: Long,
    deployPath: Option[File]
) extends Configuration

final case class SendDeploy(
    deploy: Array[Byte]
) extends Configuration

final case class Deploy(
    from: Option[String],
    nonce: Long,
    contracts: Contracts,
    publicKey: Option[File],
    privateKey: Option[File],
    gasPrice: Long
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
    nonce: Long,
    contracts: Contracts,
    privateKey: File
) extends Configuration
final case class Transfer(
    amount: Long,
    recipientPublicKeyBase64: String,
    nonce: Long,
    contracts: Contracts,
    privateKey: File
) extends Configuration
final case class Unbond(
    amount: Option[Long],
    nonce: Long,
    contracts: Contracts,
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
          options.deploy.nonce(),
          Contracts(options.deploy),
          options.deploy.publicKey.toOption,
          options.deploy.privateKey.toOption,
          options.deploy.gasPrice()
        )
      case options.makeDeploy =>
        MakeDeploy(
          options.makeDeploy.from.toOption,
          options.makeDeploy.publicKey.toOption,
          options.makeDeploy.nonce(),
          Contracts(options.makeDeploy),
          options.makeDeploy.gasPrice(),
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
          options.unbond.nonce(),
          Contracts(options.unbond),
          options.unbond.privateKey()
        )
      case options.bond =>
        Bond(
          options.bond.amount(),
          options.bond.nonce(),
          Contracts(options.bond),
          options.bond.privateKey()
        )
      case options.transfer =>
        Transfer(
          options.transfer.amount(),
          options.transfer.targetAccount(),
          options.transfer.nonce(),
          Contracts(options.transfer),
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
