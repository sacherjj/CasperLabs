package io.casperlabs.benchmarks

import java.io.File
import java.nio.file.Paths

import cats.syntax.option._
import io.casperlabs.client.configuration.ConnectOptions
import org.rogach.scallop._

final case class Options(arguments: Seq[String]) extends ScallopConf(arguments) {
  version(s"CasperLabs Benchmarking CLI Client ${BuildInfo.version}")
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

  val fileCheck: File => Boolean = file =>
    file.exists() && file.canRead && !file.isDirectory && file.isFile

  val benchmark = new Subcommand("benchmark") {
    descr(
      "Runs benchmarking by sending many token transfer deploys from many different account to single recipient"
    )

    val outputStats = opt[File](
      name = "output",
      short = 'o',
      descr = "Output for statistics CSV file",
      default = Paths.get(sys.props("user.dir"), "benchmarking_stats.csv.txt").toFile.some
    )

    val initialFundsPrivateKey = opt[File](
      name = "initial-funds-private-key",
      required = true,
      descr = "Private key of account to send funds to initialize other accounts",
      validate = fileCheck
    )

    val initialFundsPublicKey = opt[File](
      name = "initial-funds-public-key",
      required = true,
      descr = "Public key of account to send funds to initialize other accounts",
      validate = fileCheck
    )
  }
  addSubcommand(benchmark)

  verify()
}

object Options {
  sealed trait Configuration extends Product with Serializable
  object Configuration {
    final case class Benchmark(
        outputStats: File,
        initialFundsPrivateKey: File,
        initialFundsPublicKey: File
    ) extends Configuration

    def parse(args: Array[String]): Option[(ConnectOptions, Configuration)] = {
      val options = Options(args)
      val connect = ConnectOptions(
        options.host(),
        options.port(),
        options.portInternal(),
        options.nodeId.toOption
      )
      val conf: Option[Configuration] = options.subcommand.map {
        case options.benchmark =>
          Benchmark(
            options.benchmark.outputStats(),
            options.benchmark.initialFundsPrivateKey(),
            options.benchmark.initialFundsPublicKey()
          )
      }
      conf map (connect -> _)
    }
  }
}
