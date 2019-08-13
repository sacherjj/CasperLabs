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

  val portInternal =
    opt[Int](descr = "Port used for internal gRPC API.", default = Option(40402))

  val host =
    opt[String](
      descr = "Hostname or IP of node on which the gRPC service is running.",
      required = true
    )

  val nodeId =
    opt[String](
      descr =
        "Node ID (i.e. the Keccak256 hash of the public key the node uses for TLS) in case secure communication is needed.",
      required = false
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
      descr =
        "The public key of the account which is the context of this deployment, base16 encoded.",
      required = false
    )

    val gasLimit =
      opt[Long](
        descr =
          "[Deprecated] The amount of gas to use for the transaction (unused gas is refunded). Must be positive integer.",
        validate = _ > 0,
        required = false // Leaving it here for now so old examples don't complain about its presence.
      )

    val gasPrice = opt[Long](
      descr = "The price of gas for this transaction in units dust/gas. Must be positive integer.",
      validate = _ > 0,
      required = false,
      default = 10L.some
    )

    val nonce = opt[Long](
      descr = "This allows you to overwrite your own pending transactions that use the same nonce.",
      validate = _ > 0,
      required = true
    )

    val session =
      opt[File](required = true, descr = "Path to the file with session code", validate = fileCheck)

    val payment =
      opt[File](
        required = false,
        descr = "Path to the file with payment code, by default fallbacks to the --session code",
        validate = fileCheck
      )

    val publicKey =
      opt[File](
        required = false,
        descr = "Path to the file with account public key (Ed25519)",
        validate = fileCheck
      )

    val privateKey =
      opt[File](
        required = false,
        descr = "Path to the file with account private key (Ed25519)",
        validate = fileCheck
      )
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
      "View properties of a block known by Casper on an existing running node."
    )

    val hash =
      trailArg[String](
        name = "hash",
        required = true,
        descr = "Value of the block hash, base16 encoded."
      )
  }
  addSubcommand(showBlock)

  val showDeploys = new Subcommand("show-deploys") {
    descr(
      "View deploys included in a block."
    )

    val hash =
      trailArg[String](
        name = "hash",
        required = true,
        descr = "Value of the block hash, base16 encoded."
      )
  }
  addSubcommand(showDeploys)

  val showDeploy = new Subcommand("show-deploy") {
    descr(
      "View properties of a deploy known by Casper on an existing running node."
    )

    val hash =
      trailArg[String](
        name = "hash",
        required = true,
        descr = "Value of the deploy hash, base16 encoded."
      )
  }
  addSubcommand(showDeploy)

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

  val unbond = new Subcommand("unbond") {
    descr("Issues unbonding request")

    val amount = opt[Long](
      name = "amount",
      validate = _ > 0,
      descr =
        "Amount of motes to unbond. If not provided then a request to unbond with full staked amount is made."
    )

    val session =
      opt[File](
        descr = "Path to the file with unbonding contract.",
        validate = fileCheck
      )

    val nonce = opt[Long](
      descr =
        "Nonce of the account. Sequences deploys from that account. Every new deploy has to use nonce one higher than current account's nonce.",
      validate = _ > 0,
      required = true
    )

    val privateKey =
      opt[File](
        descr = "Path to the file with account private key (Ed25519)",
        validate = fileCheck,
        required = true
      )

  }
  addSubcommand(unbond)

  val bond = new Subcommand("bond") {
    descr("Issues bonding request")

    val amount = opt[Long](
      name = "amount",
      validate = _ > 0,
      descr = "amount of motes to bond",
      required = true
    )

    val session =
      opt[File](
        descr = "Path to the file with bonding contract.",
        validate = fileCheck
      )

    val nonce = opt[Long](
      descr =
        "Nonce of the account. Sequences deploys from that account. Every new deploy has to use nonce one higher than current account's nonce.",
      validate = _ > 0,
      required = true
    )

    val privateKey =
      opt[File](
        descr = "Path to the file with account private key (Ed25519)",
        validate = fileCheck,
        required = true
      )
  }
  addSubcommand(bond)

  val transfer = new Subcommand("transfer") {
    descr("Transfers funds between accounts")

    val amount = opt[Long](
      name = "amount",
      validate = _ > 0,
      descr =
        "Amount of motes to transfer. Note: a mote is the smallest, indivisible unit of a token.",
      required = true
    )

    val session =
      opt[File](
        descr = "Path to the file with transfer contract.",
        validate = fileCheck
      )

    val nonce = opt[Long](
      descr =
        "Nonce of the account. Sequences deploys from that account. Every new deploy has to use nonce one higher than current account's nonce.",
      validate = _ > 0,
      required = true
    )

    val privateKey =
      opt[File](
        descr = "Path to the file with (from) account private key (Ed25519)",
        validate = fileCheck,
        required = true
      )

    val targetAccount =
      opt[String](
        descr = "base64 representation of target account's public key",
        required = true
      )
  }
  addSubcommand(transfer)

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

    val out = opt[File](
      descr =
        s"output image filename, outputs to stdout if not specified, must ends with one of the ${Format
          .values()
          .map(_.name().toLowerCase())
          .mkString(", ")}",
      validate = file =>
        fileCheck(file) && Format.values().map(_.name().toLowerCase).exists(file.getName.endsWith)
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
        descr =
          "Type of base key. Must be one of 'hash', 'uref', 'address' or 'local'. For 'local' key type, 'key' value format is {seed}:{rest}, where both parts are hex encoded.",
        validate = s => Set("hash", "uref", "address", "local").contains(s.toLowerCase),
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

  val balance = new Subcommand("balance") {
    descr("Returns the balance of the account at the specified block.")

    val blockHash =
      opt[String](
        name = "block-hash",
        descr = "Hash of the block to query the state of",
        required = true,
        validate = hexCheck
      )

    val address =
      opt[String](
        name = "address",
        descr = "Account's public key in hex.",
        required = true,
        validate = hexCheck
      )
  }
  addSubcommand(balance)

  verify()
}
