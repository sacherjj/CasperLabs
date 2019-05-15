package io.casperlabs.client.configuration

import java.io.File

import cats.syntax.option._
import guru.nidi.graphviz.engine.Format
import io.casperlabs.client.BuildInfo
import org.rogach.scallop._

final case class Options(arguments: Seq[String]) extends ScallopConf(arguments) {
  implicit val streamingConverter: ValueConverter[Streaming] = new ValueConverter[Streaming] {
    override def parse(s: List[(String, List[String])]): Either[String, Option[Streaming]] =
      s match {
        case List((_, List(v))) =>
          v match {
            case "single-output"    => Right(Some(Streaming.Single))
            case "multiple-outputs" => Right(Some(Streaming.Multiple))
            case _                  => Left("Failed to parse 'v', must be 'single-output' or 'multiple-outputs'")
          }
        case Nil => Right(None)
        case _   => Left("Provide 'single-output' or 'multiple-outputs'")
      }
    override val argType: ArgType.V = ArgType.SINGLE
  }

  version(s"CasperLabs Client ${BuildInfo.version}")
  printedName = "casperlabs"

  val port =
    opt[Int](descr = "Port used for external gRPC API.", default = Option(40401))

  val host =
    opt[String](
      descr = "Hostname or IP of node on which gRPC service is running.",
      required = true
    )

  val hexCheck: String => Boolean = _.matches("[0-9a-fA-F]+")
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
      default = Option("00")
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

  val visualizeBlocks = new Subcommand("vdag") {
    descr(
      "DAG in DOT format"
    )
    val depth =
      opt[Int](
        descr = "depth in terms of block height",
        required = true
      )
    val showJustificationLines =
      opt[Boolean](
        descr = "if justification lines should be shown",
        default = false.some
      )

    val out = opt[String](
      descr =
        s"output image filename, outputs to stdout if not specified, must ends with one of the ${Format
          .values()
          .map(_.name().toLowerCase())
          .mkString(", ")}",
      validate = s => Format.values().map(_.name().toLowerCase).exists(s.endsWith)
    )

    val stream =
      opt[Streaming](
        descr =
          "subscribe to changes, '--out' has to specified, valid values are 'single-output', 'multiple-outputs'"
      )
  }
  addSubcommand(visualizeBlocks)

  val query = new Subcommand("query-state") {
    descr(
      "Query a value in the global state."
    )

    val blockHash =
      opt[String](
        name = "block-hash",
        descr = "Hash of the block to query the state of",
        required = true
      )

    val keyType =
      opt[String](
        name = "type",
        descr = "Type of base key. Must be one of 'hash', 'uref', 'address'",
        validate = s => Set("hash", "uref", "address").contains(s.toLowerCase),
        default = Option("address")
      )

    val key =
      opt[String](
        name = "key",
        descr = "Base16 encoding of the base key.",
        required = true
      )

    val path =
      opt[String](
        name = "path",
        descr = "Path to the value to query. Must be of the form 'key1/key2/.../keyn'",
        default = Option("")
      )
  }
  addSubcommand(query)

  verify()
}
