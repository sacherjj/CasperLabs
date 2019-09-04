package io.casperlabs.client.configuration

import java.io.File
import java.nio.file.Files

import cats.syntax.option._
import guru.nidi.graphviz.engine.Format
import io.casperlabs.client.BuildInfo
import org.apache.commons.io.IOUtils
import org.rogach.scallop._

object Options {
  val hexCheck: String => Boolean  = _.matches("[0-9a-fA-F]+")
  val hashCheck: String => Boolean = x => hexCheck(x) && x.length == 64

  val fileCheck: File => Boolean = file =>
    file.exists() && file.canRead && !file.isDirectory && file.isFile

  trait ContractArgs { self: Subcommand =>
    def sessionRequired: Boolean = true
    def paymentPathName: String  = "payment"

    // Session code on disk.
    val session =
      opt[File](
        required = false,
        descr = "Path to the file with session code.",
        validate = fileCheck
      )

    val sessionHash =
      opt[String](
        required = false,
        descr = "Hash of the stored contract to be called in the session; base16 encoded.",
        validate = hashCheck
      )

    val sessionName =
      opt[String](
        required = false,
        descr =
          "Name of the stored contract (associated with the executing account) to be called in the session."
      )

    val sessionUref =
      opt[String](
        required = false,
        descr = "URef of the stored contract to be called in the session; base16 encoded.",
        validate = hashCheck
      )

    val payment =
      opt[File](
        name = paymentPathName,
        required = false,
        descr = "Path to the file with payment code.",
        validate = fileCheck
      )

    val paymentHash =
      opt[String](
        required = false,
        descr = "Hash of the stored contract to be called in the payment; base16 encoded.",
        validate = hashCheck
      )

    val paymentName =
      opt[String](
        required = false,
        descr =
          "Name of the stored contract (associated with the executing account) to be called in the payment."
      )

    val paymentUref =
      opt[String](
        required = false,
        descr = "URef of the stored contract to be called in the payment; base16 encoded.",
        validate = hashCheck
      )

    addValidation {
      val sessionsProvided =
        List(session.isDefined, sessionHash.isDefined, sessionName.isDefined, sessionUref.isDefined)
          .count(identity)
      val paymentsProvided =
        List(payment.isDefined, paymentHash.isDefined, paymentName.isDefined, paymentUref.isDefined)
          .count(identity)
      if (sessionRequired && sessionsProvided == 0)
        Left("No session contract options provided; please specify exactly one.")
      else if (sessionsProvided > 1)
        Left("Multiple session contract options provided; please specify exactly one.")
      else if (paymentsProvided > 1)
        Left("Multiple payment contract options provided; please specify exactly one.")
      else Right(())
    }

  }
}

final case class Options(arguments: Seq[String]) extends ScallopConf(arguments) {
  import Options._

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

  val makeDeploy = new Subcommand("make-deploy") with ContractArgs {
    descr("Constructs a deploy that can be signed and sent to a node.")

    val from = opt[String](
      descr =
        "The public key of the account which is the context of this deployment, base16 encoded.",
      required = false
    )

    val publicKey =
      opt[File](
        required = false,
        descr = "Path to the file with account public key (Ed25519)",
        validate = fileCheck
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

    val deployPath =
      opt[File](
        required = false,
        descr = "Path to the file where deploy will be saved. " +
          "Optional, if not provided the deploy will be printed to STDOUT.",
        short = 'o'
      )

    addValidation {
      if (publicKey.isDefined && from.isDefined)
        Left("Both --from  and --public-key were provided. Please provide one of them.")
      else if (publicKey.isEmpty && from.isEmpty)
        Left("Neither --from nor --public-key were provided. Please provide one of them.")
      else Right(())
    }
  }
  addSubcommand(makeDeploy)

  val sendDeploy = new Subcommand("send-deploy") {
    descr(
      "Deploy a smart contract source file to Casper on an existing running node. " +
        "The deploy will be packaged and sent as a block to the network depending " +
        "on the configuration of the Casper instance."
    )

    val deployPath = opt[File](
      required = false,
      descr = "Path to the file with signed Deploy.",
      validate = fileCheck,
      short = 'i'
    ).map(file => Files.readAllBytes(file.toPath))
      .orElse(Some(IOUtils.toByteArray(System.in)))

  }
  addSubcommand(sendDeploy)

  val deploy = new Subcommand("deploy") with ContractArgs {
    descr(
      "Constructs a Deploy and sends it to Casper on an existing running node. " +
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

  val signDeploy = new Subcommand("sign-deploy") {
    descr("Cryptographically signs a deploy. The signature is appended to existing approvals.")

    val publicKey =
      opt[File](
        required = true,
        descr = "Path to the file with account public key (Ed25519)",
        validate = fileCheck,
        noshort = true
      )

    val privateKey =
      opt[File](
        required = true,
        descr = "Path to the file with account private key (Ed25519)",
        validate = fileCheck,
        noshort = true
      )

    val signedDeployPath =
      opt[File](
        required = false,
        descr = "Path to the file where signed deploy will be saved." +
          "If not provided, the signed deploy will be sent to stdout.",
        short = 'o'
      )

    val deployPath =
      opt[File](
        required = false,
        descr = "Path to the deploy file.",
        validate = fileCheck,
        short = 'i'
      ).map(file => Files.readAllBytes(file.toPath))
        .orElse(Some(IOUtils.toByteArray(System.in)))
  }

  addSubcommand(signDeploy)

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
        descr = "Value of the block hash, base16 encoded.",
        validate = hexCheck
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
        descr = "Value of the block hash, base16 encoded.",
        validate = hexCheck
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
        descr = "Value of the deploy hash, base16 encoded.",
        validate = hashCheck
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

  val unbond = new Subcommand("unbond") with ContractArgs {
    descr("Issues unbonding request")

    override def sessionRequired = false
    override def paymentPathName = "payment-path"

    val amount = opt[Long](
      name = "amount",
      validate = _ > 0,
      descr =
        "Amount of motes to unbond. If not provided then a request to unbond with full staked amount is made."
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

  val bond = new Subcommand("bond") with ContractArgs {
    descr("Issues bonding request")

    override def sessionRequired = false
    override def paymentPathName = "payment-path"

    val amount = opt[Long](
      name = "amount",
      validate = _ > 0,
      descr = "amount of motes to bond",
      required = true
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

  val transfer = new Subcommand("transfer") with ContractArgs {
    descr("Transfers funds between accounts")

    override def sessionRequired = false
    override def paymentPathName = "payment-path"

    val amount = opt[Long](
      name = "amount",
      validate = _ > 0,
      descr =
        "Amount of motes to transfer. Note: a mote is the smallest, indivisible unit of a token.",
      required = true
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
        descr =
          "Type of base key. Must be one of 'hash', 'uref', 'address' or 'local'. For 'local' key type, 'key' value format is {seed}:{rest}, where both parts are hex encoded.",
        validate = s => Set("hash", "uref", "address", "local").contains(s.toLowerCase),
        default = Option("address")
      )

    val key =
      opt[String](
        name = "key",
        descr = "Base16 encoding of the base key.",
        required = true,
        validate = hexCheck
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
