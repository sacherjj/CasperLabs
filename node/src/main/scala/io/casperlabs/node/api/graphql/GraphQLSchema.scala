package io.casperlabs.node.api.graphql

import cats.effect.Effect
import cats.effect.implicits._
import cats.implicits._
import fs2.Stream
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.info.BlockStatus
import io.casperlabs.comm.CommError
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.node.CommErrT
import io.casperlabs.shared.Log
import sangria.ast.StringValue
import sangria.schema._
import sangria.validation.Violation

import scala.util.Random

private[graphql] object GraphQLSchema {
  final case object HashTypeViolation extends Violation {
    override def errorMessage: String = "Error during parsing Hash - must be a valid base16 string"
  }

  implicit val HashType: ScalarType[Array[Byte]] = ScalarType[Array[Byte]](
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

  val BlockType = ObjectType(
    "Block",
    fields[Unit, (Block, BlockStatus)](
      Field("id", HashType, resolve = c => c.value._1.blockHash.toByteArray),
      Field(
        "parentHashes",
        ListType(HashType),
        resolve = c => c.value._1.getHeader.parentHashes.map(_.toByteArray)
      ),
      Field(
        "justificationHashes",
        ListType(HashType),
        resolve = c => c.value._1.getHeader.justifications.map(_.latestBlockHash.toByteArray)
      ),
      Field("faultTolerance", FloatType, resolve = c => c.value._2.faultTolerance.toDouble),
      Field(
        "blockSizeBytes",
        OptionType(IntType),
        resolve = c => c.value._2.stats.map(_.blockSizeBytes)
      ),
      Field(
        "deployErrorCount",
        OptionType(IntType),
        resolve = c => c.value._2.stats.map(_.deployErrorCount)
      )
    )
  )

  val HashPrefix =
    Argument("blockHashBase16", HashType, description = "Prefix or full base-16 hash")

  val randomBytes: List[Array[Byte]] = (for {
    _ <- 0 until 5
  } yield {
    val arr = Array.ofDim[Byte](32)
    Random.nextBytes(arr)
    arr
  }).toList

  /* TODO: Temporary mock implementation  */
  def createSchema[F[_]](
      implicit fs2SubscriptionStream: Fs2SubscriptionStream[F],
      L: Log[F],
      E: Effect[F],
      M: MultiParentCasperRef[CommErrT[F, ?]],
      S: SafetyOracle[CommErrT[F, ?]],
      B: BlockStore[CommErrT[F, ?]]
  ): Schema[Unit, Unit] = {
    import Log.eitherTLog

    Schema(
      query = ObjectType(
        "Query",
        fields[Unit, Unit](
          Field("testField", HashType, resolve = _ => Action(randomBytes.head)),
          Field(
            "block",
            OptionType(BlockType),
            arguments = HashPrefix :: Nil,
            resolve = c =>
              BlockAPI
                .getBlockInfo[CommErrT[F, ?], Option[(Block, BlockStatus)]](
                  blockHashBase16 = Base16.encode(c.arg(HashPrefix)),
                  full = true,
                  combine = (_, block, status) => (block, status).some,
                  ifNotFound = none[(Block, BlockStatus)].pure[CommErrT[F, ?]]
                )
                .value
                .flatMap(
                  _.fold(
                    commError =>
                      E.raiseError[Option[(Block, BlockStatus)]](
                        new RuntimeException(CommError.errorMessage(commError))
                      ),
                    _.pure[F]
                  )
                )
                .toIO
                .unsafeToFuture()
          )
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
}
