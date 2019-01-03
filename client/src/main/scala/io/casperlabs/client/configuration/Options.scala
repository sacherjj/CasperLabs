package io.casperlabs.client.configuration

import java.io.File

import io.casperlabs.client.BuildInfo
import org.rogach.scallop._

final case class Options(arguments: Seq[String]) extends ScallopConf(arguments) {

  version(s"Casper Labs Client ${BuildInfo.version}")
  printedName = "casperlabs"

  val port =
    opt[Int](descr = "Port used for external gRPC API.", default = Option(40401))

  val host =
    opt[String](
      descr = "Hostname or IP of node on which gRPC service is running.",
      required = true
    )

  val hexCheck: String => Boolean     = _.matches("[0-9a-fA-F]+")
  val addressCheck: String => Boolean = addr => addr.startsWith("0x") && hexCheck(addr.drop(2))
  val fileCheck: File => Boolean = file =>
    file.exists() && file.canRead && !file.isDirectory && file.isFile

  val deploy = new Subcommand("deploy") {
    descr(
      "Deploy a smart contract source file to Casper on an existing running node. " +
        "The deploy will be packaged and sent as a block to the network depending " +
        "on the configuration of the Casper instance."
    )

    val from = opt[String](
      descr = "Purse address that will be used to pay for the deployment.",
      validate = addressCheck,
      default = Option("0x")
    )

    val gasLimit =
      opt[Long](
        descr =
          "The amount of gas to use for the transaction (unused gas is refunded). Must be positive integer.",
        validate = _ > 0,
        required = true
      )

    val gasPrice = opt[Long](
      descr = "The price of gas for this transaction in units dust/gas. Must be positive integer.",
      validate = _ > 0,
      required = true
    )

    val nonce = opt[Long](
      descr = "This allows you to overwrite your own pending transactions that use the same nonce.",
      default = Option(0L)
    )

    val session =
      opt[File](required = true, descr = "Path to the file with session code", validate = fileCheck)
    val payment =
      opt[File](required = true, descr = "Path to the file with payment code", validate = fileCheck)

  }
  addSubcommand(deploy)

  val propose = new Subcommand("propose") {
    descr(
      "Force a node to propose a block based on its accumulated deploys."
    )
  }
  addSubcommand(propose)

  val showBlock = new Subcommand("show-block") {
    descr(
      "View properties of a block known by Casper on an existing running node." +
        "Output includes: parent hashes, storage contents of the tuplespace."
    )

    val hash =
      trailArg[String](name = "hash", required = true, descr = "the hash value of the block")
  }
  addSubcommand(showBlock)

  val showBlocks = new Subcommand("show-blocks") {
    descr(
      "View list of blocks in the current Casper view on an existing running node."
    )
    val depth =
      opt[Int](
        name = "depth",
        validate = _ > 0,
        descr = "lists blocks to the given depth in terms of block height",
        default = Option(1)
      )

  }
  addSubcommand(showBlocks)

  verify()
}
