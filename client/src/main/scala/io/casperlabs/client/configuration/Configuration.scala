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

final case class ShowBlock(port: Int, host: String, hash: String)     extends Configuration
final case class ShowBlocks(port: Int, host: String, depth: Int)      extends Configuration
final case class VisualizeBlocks(port: Int, host: String, depth: Int) extends Configuration

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
        VisualizeBlocks(options.port(), options.host(), options.visualizeBlocks.depth())
    }
  }
}
