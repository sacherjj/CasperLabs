package io.casperlabs.node.api.graphql.schema

import cats._
import cats.syntax._
import cats.implicits._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import java.time.{Instant, ZoneId, ZonedDateTime}

import sangria.schema._

package object utils {
  // Not defining inputs yet because it's a read-only field
  val DateType: ScalarType[Long] = ScalarType[Long](
    "Date",
    coerceUserInput = _ => ???,
    coerceOutput =
      (l, _) => ZonedDateTime.ofInstant(Instant.ofEpochMilli(l), ZoneId.of("Z")).toString,
    coerceInput = _ => ???
  )

  val ProtocolVersionType = ObjectType(
    "ProtocolVersion",
    "ProtocolVersion at the time a Block was created; follows semver semantics",
    fields[Unit, ProtocolVersion](
      Field(
        "major",
        IntType,
        "Major version".some,
        resolve = c => c.value.major
      ),
      Field(
        "minor",
        IntType,
        "Minor version".some,
        resolve = c => c.value.minor
      ),
      Field(
        "path",
        IntType,
        "Patch version".some,
        resolve = c => c.value.patch
      )
    )
  )
}
