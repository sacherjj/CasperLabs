package io.casperlabs.node

import cats.effect.Sync
import cats.implicits._
import com.typesafe.config.ConfigFactory
import io.casperlabs.comm.NodeIdentifier
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.node.configuration.Configuration.Influx
import io.casperlabs.node.diagnostics.JmxReporter
import io.casperlabs.shared.Log
import kamon.system.SystemMetrics
import kamon.zipkin.ZipkinReporter
import kamon.{Kamon, MetricReporter}

class MetricsRuntime[F[_]: Sync: Log](conf: Configuration, id: NodeIdentifier) {

  def setupMetrics(metricsReporter: MetricReporter): F[Unit] =
    for {
      kamonConf <- buildKamonConf
      _ <- Sync[F].delay {
            Kamon.reconfigure(
              ConfigFactory
                .parseString(kamonConf)
                .withFallback(Kamon.config())
            )

            if (conf.kamon.influx.isDefined)
              Kamon.addReporter(new kamon.influxdb.InfluxDBReporter())
            if (conf.kamon.prometheus) Kamon.addReporter(metricsReporter)
            if (conf.kamon.zipkin) Kamon.addReporter(new ZipkinReporter())

            Kamon.addReporter(new JmxReporter())
            SystemMetrics.startCollecting()
          }
    } yield ()

  private def buildInfluxConf(influx: Influx, auth: String) = {
    val props =
      s"""
         |    hostname = "${influx.hostname}"
         |    port     =  ${influx.port}
         |    database = "${influx.database}"
         |    protocol = "${influx.protocol}"
     """.stripMargin

    val conf =
      s"""
          |  influxdb {
          |    $props
          |    $auth
          |  }
      """.stripMargin

    Log[F].info(s"Following Influx configuration used: \n $props") *> conf.pure[F]
  }

  private def buildInfluxAuth(influx: Influx) = {
    val maybeAuth =
      influx.authentication
        .map { auth =>
          s"""
             |    authentication {
             |      user = "${auth.user}"
             |      password = "${auth.password}"
             |    }
             |""".stripMargin
        }

    Log[F]
      .info("No Influx credentials specified")
      .whenA(maybeAuth.isEmpty) *> maybeAuth.getOrElse("").pure[F]
  }

  private def buildCommonConfiguration(influxConf: String): String =
    s"""
       |kamon {
       |  environment {
       |    service = "rnode"
       |    instance = "${id.toString}"
       |  }
       |  metric {
       |    tick-interval = 10 seconds
       |  }
       |  system-metrics {
       |    host {
       |      enabled = ${conf.kamon.sigar}
       |      sigar-native-folder = ${conf.server.dataDir.resolve("native")}
       |    }
       |  }
       |  $influxConf
       |}
       |""".stripMargin

  private def buildKamonConf: F[String] = {
    val maybeInfluxConf: Option[F[String]] =
      conf.kamon.influx.map { influx =>
        for {
          influxAuth <- buildInfluxAuth(influx)
          influxConf <- buildInfluxConf(influx, influxAuth)
        } yield influxConf
      }

    val influxConf =
      maybeInfluxConf.getOrElse(Log[F].info("No Influx configuration found") *> "".pure[F])

    influxConf.map(buildCommonConfiguration)
  }
}
