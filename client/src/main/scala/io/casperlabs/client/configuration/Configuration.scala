package io.casperlabs.client.configuration
import java.io.File

sealed trait Configuration extends Product with Serializable {
  def port: Int
  def host: String
}

final case class Deploy(
    port: Int,
    host: String,
    from: String,
    gasLimit: Long,
    gasPrice: Long,
    nonce: Long,
    sessionCode: File,
    paymentCode: File
) extends Configuration

final case class Propose(port: Int, host: String) extends Configuration

final case class ShowBlock(port: Int, host: String, hash: String) extends Configuration
final case class ShowBlocks(port: Int, host: String, depth: Int)  extends Configuration
final case class VisualizeDag(
    port: Int,
    host: String,
    depth: Int,
    showJustificationLines: Boolean,
    out: Option[String],
    streaming: Option[Streaming]
) extends Configuration

sealed trait Streaming extends Product with Serializable
object Streaming {
  final case object Single   extends Streaming
  final case object Multiple extends Streaming
}

final case class Query(
    port: Int,
    host: String,
    blockHash: String,
    keyType: String,
    key: String,
    path: String
) extends Configuration

object Configuration {
  def parse(args: Array[String]): Option[Configuration] = {
    val options = Options(args)
    options.subcommand.map {
      case options.deploy =>
        Deploy(
          options.port(),
          options.host(),
          options.deploy.from(),
          options.deploy.gasLimit(),
          options.deploy.gasPrice(),
          options.deploy.nonce(),
          options.deploy.session(),
          options.deploy.payment()
        )
      case options.propose =>
        Propose(
          options.port(),
          options.host()
        )
      case options.showBlock =>
        ShowBlock(options.port(), options.host(), options.showBlock.hash())
      case options.showBlocks =>
        ShowBlocks(options.port(), options.host(), options.showBlocks.depth())
      case options.visualizeBlocks =>
        VisualizeDag(
          options.port(),
          options.host(),
          options.visualizeBlocks.depth(),
          options.visualizeBlocks.showJustificationLines(),
          options.visualizeBlocks.out.toOption,
          options.visualizeBlocks.stream.toOption
        )
      case options.query =>
        Query(
          options.port(),
          options.host(),
          options.query.blockHash(),
          options.query.keyType(),
          options.query.key(),
          options.query.path()
        )
    }
  }
}
