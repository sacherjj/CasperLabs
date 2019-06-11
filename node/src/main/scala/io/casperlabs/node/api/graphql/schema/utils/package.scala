package io.casperlabs.node.api.graphql.schema

import java.time.{Instant, ZoneId, ZonedDateTime}

import sangria.schema.ScalarType

package object utils {
  // Not defining inputs yet because it's a read-only field
  val DateType: ScalarType[Long] = ScalarType[Long](
    "Date",
    coerceUserInput = _ => ???,
    coerceOutput =
      (l, _) => ZonedDateTime.ofInstant(Instant.ofEpochMilli(l), ZoneId.of("Z")).toString,
    coerceInput = _ => ???
  )
}
