package io.casperlabs.client.configuration
import java.io.File

import cats.implicits._
import org.rogach.scallop.ScallopOption

final case class ConnectOptions(
    host: String,
    portExternal: Int,
    portInternal: Int,
    nodeId: Option[String]
)

sealed trait Configuration

final case class MakeDeploy(
    from: String,
    nonce: Long,
    sessionCode: File,
    paymentCode: File,
    gasPrice: Long,
    deployPath: Option[File]
) extends Configuration

final case class Deploy(
    deploy: Either[Array[Byte], File]
) extends Configuration

/** Client command to sign a deploy.
  *
  * @param deploy Either an array of bytes (deploy read from the STDIN),
  *               or a file (deploy read from the file).
  * @param signedDeployPath Path where signed deploy will be written.
  *                         If `None` then it's written to STDOUT.
  * @param publicKey Public key corresponding to the private one.
  * @param privateKey Key signing the deploy.
  */
final case class Sign(
    deploy: Either[Array[Byte], File],
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
    sessionCode: Option[File],
    privateKey: File
) extends Configuration
final case class Transfer(
    amount: Long,
    recipientPublicKeyBase64: String,
    nonce: Long,
    sessionCode: Option[File],
    privateKey: File
) extends Configuration
final case class Unbond(
    amount: Option[Long],
    nonce: Long,
    sessionCode: Option[File],
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
  private def readFileOrString(
      file: ScallopOption[File],
      str: ScallopOption[String]
  ): Either[Array[Byte], File] =
    file.toOption
      .map(_.asRight[Array[Byte]])
      .getOrElse(str().getBytes.asLeft[File])

  def parse(args: Array[String]): Option[(ConnectOptions, Configuration)] = {
    val options = Options(args)
    val connect = ConnectOptions(
      options.host(),
      options.port(),
      options.portInternal(),
      options.nodeId.toOption
    )
    val conf = options.subcommand.map {
      case options.makeDeploy =>
        MakeDeploy(
          options.makeDeploy.from(),
          options.makeDeploy.nonce(),
          options.makeDeploy.session(),
          options.makeDeploy.payment(),
          options.makeDeploy.gasPrice(),
          options.makeDeploy.deployPath.toOption
        )
      case options.deploy =>
        Deploy(
          readFileOrString(options.deploy.deployFile, options.deploy.deployStdin)
        )
      case options.sign =>
        Sign(
          readFileOrString(options.sign.deployFile, options.sign.deployStdin),
          options.sign.signedDeployPath.toOption,
          options.sign.publicKey(),
          options.sign.privateKey()
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
          options.unbond.session.toOption,
          options.unbond.privateKey()
        )
      case options.bond =>
        Bond(
          options.bond.amount(),
          options.bond.nonce(),
          options.bond.session.toOption,
          options.bond.privateKey()
        )
      case options.transfer =>
        Transfer(
          options.transfer.amount(),
          options.transfer.targetAccount(),
          options.transfer.nonce(),
          options.transfer.session.toOption,
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
