package io.casperlabs.node

import cats.effect.Sync
import cats.implicits._
import com.typesafe.config.ConfigFactory
import io.casperlabs.comm.discovery.NodeIdentifier
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.node.configuration.Configuration.Influx
import io.casperlabs.node.diagnostics.JmxReporter
import io.casperlabs.shared.Log
import kamon.system.SystemMetrics
import kamon.zipkin.ZipkinReporter
import kamon._

class MetricsRuntime[F[_]: Sync: Log](conf: Configuration, id: NodeIdentifier) {

  private def addReporter(enabled: Boolean, destination: String, reporter: => Reporter): F[Unit] =
    if (enabled) {
      Log[F].info(s"Reporting metrics to $destination.") *>
        Sync[F].delay {
          reporter match {
            case reporter: MetricReporter =>
              Kamon.addReporter(reporter)
            case reporter: SpanReporter =>
              Kamon.addReporter(reporter)
          }
        }
    } else {
      Log[F].info(s"Reporting metrics to $destination disabled.")
    }

  def setupMetrics(prometheusReporter: MetricReporter): F[Unit] =
    for {
      kamonConf <- buildKamonConf
      _ <- Sync[F].delay {
            Kamon.reconfigure(
              ConfigFactory
                .parseString(kamonConf)
                .withFallback(Kamon.config())
            )
          }

      _ <- addReporter(conf.metrics.influx, "InfluxDB", new influxdb.InfluxDBReporter())
      _ <- addReporter(conf.metrics.prometheus, "Prometheus", prometheusReporter)
      _ <- addReporter(conf.metrics.zipkin, "Zipkin", new ZipkinReporter())
      _ <- addReporter(enabled = true, "JMX", new JmxReporter())

      _ <- Sync[F].delay(SystemMetrics.startCollecting())
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

  private def buildInfluxAuth(user: String, password: String) =
    s"""
             |    authentication {
             |      user = "$user"
             |      password = "$password"
             |    }
             |""".stripMargin

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
       |      enabled = ${conf.metrics.sigar}
       |      sigar-native-folder = ${conf.server.dataDir.resolve("native")}
       |    }
       |  }
       |  $influxConf
       |}
       |""".stripMargin

  private def buildKamonConf: F[String] = {
    val maybeInfluxConf: Option[F[String]] =
      conf.influx.map { influx =>
        for {
          influxAuth <- (influx.user, influx.password)
                         .mapN(buildInfluxAuth)
                         .fold(
                           Log[F]
                             .info("No Influx credentials specified") *> "".pure[F]
                         )(_.pure[F])
          influxConf <- buildInfluxConf(influx, influxAuth)
        } yield influxConf
      }

    val influxConf =
      maybeInfluxConf.getOrElse(Log[F].info("No Influx configuration found") *> "".pure[F])

    influxConf.map(buildCommonConfiguration)
  }
}
