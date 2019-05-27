package io.casperlabs.node.api.graphql

import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import fs2.Stream
import io.casperlabs.casper.consensus.Block
import io.casperlabs.crypto.codec.Base16
import sangria.ast.StringValue
import sangria.schema.{fields, Action, Field, ObjectType, ScalarType, Schema}
import sangria.validation.Violation

import scala.util.Random

private[graphql] object GraphQLSchema {
  final case object HashTypeViolation extends Violation {
    override def errorMessage: String = "Error during parsing Hash - must be a valid base16 string"
  }

  implicit val HashType = ScalarType[Array[Byte]](
    name = "Hash",
    coerceUserInput = {
      case s: String => Either.catchNonFatal(Base16.decode(s)).leftMap(_ => HashTypeViolation)
      case _         => HashTypeViolation.asLeft[Array[Byte]]
    },
    coerceOutput = (a, _) => Base16.encode(a),
    coerceInput = {
      case StringValue(s, _, _, _, _) =>
        Either.catchNonFatal(Base16.decode(s)).leftMap(_ => HashTypeViolation)
      case _ => HashTypeViolation.asLeft[Array[Byte]]
    }
  )

  val randomBytes: List[Array[Byte]] = (for {
    _ <- 0 until 5
  } yield {
    val arr = Array.ofDim[Byte](32)
    Random.nextBytes(arr)
    arr
  }).toList

  /* TODO: Temporary mock implementation  */
  def createSchema[F[_]](
      implicit fs2SubscriptionStream: Fs2SubscriptionStream[F]
  ): Schema[Unit, Unit] =
    Schema(
      query = ObjectType(
        "Query",
        fields[Unit, Unit](
          Field("testField", HashType, resolve = _ => Action(randomBytes.head))
        )
      ),
      subscription = ObjectType(
        "Subscription",
        fields[Unit, Unit](
          Field.subs(
            name = "latestBlocks",
            fieldType = HashType,
            description = "Subscribes to new blocks".some,
            resolve = _ => Stream.emits[F, Action[Unit, Array[Byte]]](randomBytes.map(Action(_)))
          )
        )
      ).some
    )
}
