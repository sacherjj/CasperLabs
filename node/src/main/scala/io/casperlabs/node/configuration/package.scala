package io.casperlabs.node

package object configuration {
  case class Kamon(
      prometheus: Boolean,
      influx: Option[Influx],
      zipkin: Boolean,
      sigar: Boolean
  )

  case class Influx(
      hostname: String,
      port: Int,
      database: String,
      protocol: String,
      authentication: Option[InfluxDbAuthentication]
  )

  case class InfluxDbAuthentication(
      user: String,
      password: String
  )
}
