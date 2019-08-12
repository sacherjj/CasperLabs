package io.casperlabs.client.configuration
import java.io.File

import cats.syntax.option._

final case class ConnectOptions(
    host: String,
    portExternal: Int,
    portInternal: Int,
    nodeId: Option[String]
)

sealed trait Configuration

final case class Deploy(
    from: Option[String],
    nonce: Long,
    sessionCode: File,
    paymentCode: File,
    publicKey: Option[File],
    privateKey: Option[File],
    gasPrice: Long
) extends Configuration

final case object Propose extends Configuration

final case class ShowBlock(blockHash: String)   extends Configuration
final case class ShowDeploys(blockHash: String) extends Configuration
final case class ShowDeploy(deployHash: String) extends Configuration
final case class ShowBlocks(depth: Int)         extends Configuration
final case class Bond(
    amount: Long,
    nonce: Long,
    maybeSessionCode: Option[File],
    privateKey: File
) extends Configuration
final case class Transfer(
    amount: Long,
    recipientPublicKeyBase64: String,
    nonce: Long,
    maybeSessionCode: Option[File],
    privateKey: File
) extends Configuration
final case class Unbond(
    amount: Option[Long],
    nonce: Long,
    maybeSessionCode: Option[File],
    privateKey: File
) extends Configuration
final case class VisualizeDag(
    depth: Int,
    showJustificationLines: Boolean,
    out: Option[File],
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

sealed trait KeyManagement extends Configuration
final case class SetThresholds(
    keysManagement: Int,
    deploys: Int,
    nonce: Long,
    privateKey: File,
    maybeSessionCode: Option[File]
) extends KeyManagement
final case class AddKey(
    publicKey: File,
    weight: Int,
    nonce: Long,
    privateKey: File,
    maybeSessionCode: Option[File]
) extends KeyManagement
final case class UpdateKey(
    publicKey: File,
    newWeight: Int,
    nonce: Long,
    privateKey: File,
    maybeSessionCode: Option[File]
) extends KeyManagement
final case class RemoveKey(
    publicKey: File,
    nonce: Long,
    privateKey: File,
    maybeSessionCode: Option[File]
) extends KeyManagement

object Configuration {
  def parse(args: List[String]): Option[(ConnectOptions, Configuration)] = {
    val options = Options(args)
    val connect = ConnectOptions(
      options.host(),
      options.port(),
      options.portInternal(),
      options.nodeId.toOption
    )
    val conf = options.subcommand.flatMap {
      case options.deploy =>
        Deploy(
          options.deploy.from.toOption,
          options.deploy.nonce(),
          options.deploy.session(),
          options.deploy.payment.toOption.getOrElse(options.deploy.session()),
          options.deploy.publicKey.toOption,
          options.deploy.privateKey.toOption,
          options.deploy.gasPrice()
        ).some
      case options.propose =>
        Propose.some
      case options.showBlock =>
        ShowBlock(options.showBlock.hash()).some
      case options.showDeploys =>
        ShowDeploys(options.showDeploys.hash()).some
      case options.showDeploy =>
        ShowDeploy(options.showDeploy.hash()).some
      case options.showBlocks =>
        ShowBlocks(options.showBlocks.depth()).some
      case options.unbond =>
        Unbond(
          options.unbond.amount.toOption,
          options.unbond.nonce(),
          options.unbond.session.toOption,
          options.unbond.privateKey()
        ).some
      case options.bond =>
        Bond(
          options.bond.amount(),
          options.bond.nonce(),
          options.bond.session.toOption,
          options.bond.privateKey()
        ).some
      case options.transfer =>
        Transfer(
          options.transfer.amount(),
          options.transfer.targetAccount(),
          options.transfer.nonce(),
          options.transfer.session.toOption,
          options.transfer.privateKey()
        ).some
      case options.visualizeBlocks =>
        VisualizeDag(
          options.visualizeBlocks.depth(),
          options.visualizeBlocks.showJustificationLines(),
          options.visualizeBlocks.out.toOption,
          options.visualizeBlocks.stream.toOption
        ).some
      case options.query =>
        Query(
          options.query.blockHash(),
          options.query.keyType(),
          options.query.key(),
          options.query.path()
        ).some
      case options.balance =>
        Balance(
          options.balance.address(),
          options.balance.blockHash()
        ).some
      case options.`key` =>
        options.key.subcommand.map {
          case options.key.`thresholds` =>
            SetThresholds(
              options.key.thresholds.key(),
              options.key.thresholds.deploy(),
              options.key.nonce(),
              options.key.privateKey(),
              options.key.thresholds.session.toOption
            )
          case options.key.add =>
            AddKey(
              options.key.add.publicKey(),
              options.key.add.weight(),
              options.key.nonce(),
              options.key.privateKey(),
              options.key.add.session.toOption
            )
          case options.key.remove =>
            RemoveKey(
              options.key.add.publicKey(),
              options.key.nonce(),
              options.key.privateKey(),
              options.key.remove.session.toOption
            )
          case options.key.update =>
            UpdateKey(
              options.key.update.publicKey(),
              options.key.update.weight(),
              options.key.nonce(),
              options.key.privateKey(),
              options.key.update.session.toOption
            )
        }
    }
    conf map (connect -> _)
  }
}
