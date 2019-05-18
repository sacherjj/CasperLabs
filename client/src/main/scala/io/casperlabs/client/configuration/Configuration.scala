package io.casperlabs.client.configuration
import java.io.File

final case class ConnectOptions(
    host: String,
    portExternal: Int,
    portInternal: Int
)

sealed trait Configuration

final case class Deploy(
    from: String,
    nonce: Long,
    sessionCode: File,
    paymentCode: File
) extends Configuration

final case object Propose extends Configuration

final case class ShowBlock(hash: String) extends Configuration
final case class ShowBlocks(depth: Int)  extends Configuration
final case class VisualizeDag(
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
    blockHash: String,
    keyType: String,
    key: String,
    path: String
) extends Configuration

object Configuration {
  def parse(args: Array[String]): Option[(ConnectOptions, Configuration)] = {
    val options = Options(args)
    val connect = ConnectOptions(options.host(), options.port(), options.portInternal())
    val conf = options.subcommand.map {
      case options.deploy =>
        Deploy(
          options.deploy.from(),
          options.deploy.nonce(),
          options.deploy.session(),
          options.deploy.payment()
        )
      case options.propose =>
        Propose
      case options.showBlock =>
        ShowBlock(options.showBlock.hash())
      case options.showBlocks =>
        ShowBlocks(options.showBlocks.depth())
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
    }
    conf map (connect -> _)
  }
}
