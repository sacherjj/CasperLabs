package io.casperlabs.node.api.graphql

import cats.effect.Effect
import cats.effect.implicits._
import cats.implicits._
import fs2.Stream
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.comm.CommError
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.node.CommErrT
import io.casperlabs.shared.Log
import sangria.schema._
import sangria.validation.Violation

import scala.util.Random

private[graphql] object GraphQLSchema {

  final case object HashTypeViolation extends Violation {
    override def errorMessage: String = "Error during parsing Hash - must be a valid base16 string"
  }

  val BlockType = ObjectType(
    "Block",
    fields[Unit, BlockInfo](
      Field(
        "blockHash",
        StringType,
        resolve = c => Base16.encode(c.value.getSummary.blockHash.toByteArray)
      ),
      Field(
        "parentHashes",
        ListType(StringType),
        resolve =
          c => c.value.getSummary.getHeader.parentHashes.map(p => Base16.encode(p.toByteArray))
      ),
      Field(
        "justificationHashes",
        ListType(StringType),
        resolve = c =>
          c.value.getSummary.getHeader.justifications
            .map(j => Base16.encode(j.latestBlockHash.toByteArray))
      ),
      Field("faultTolerance", FloatType, resolve = c => c.value.getStatus.faultTolerance.toDouble),
      Field(
        "blockSizeBytes",
        OptionType(IntType),
        resolve = c => c.value.getStatus.stats.map(_.blockSizeBytes)
      ),
      Field(
        "deployErrorCount",
        OptionType(IntType),
        resolve = c => c.value.getStatus.stats.map(_.deployErrorCount)
      )
    )
  )

  val HashPrefix =
    Argument("blockHashBase16", StringType, description = "Prefix or full base-16 hash")

  val randomBytes: List[Array[Byte]] = (for {
    _ <- 0 until 5
  } yield {
    val arr = Array.ofDim[Byte](32)
    Random.nextBytes(arr)
    arr
  }).toList

  def hasAtLeastOne(c: Context[Unit, Unit], selections: Set[String]): Boolean =
    c.astFields
      .flatMap(_.selections.collect {
        case field: sangria.ast.Field => field.name
      })
      .toSet
      .intersect(selections)
      .nonEmpty

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
          Field(
            "block",
            OptionType(BlockType),
            arguments = HashPrefix :: Nil,
            resolve = c =>
              BlockAPI
                .getBlockInfoOpt[CommErrT[F, ?]](
                  blockHashBase16 = c.arg(HashPrefix),
                  full = hasAtLeastOne(c, Set("blockSizeBytes", "deployErrorCount"))
                )
                .value
                .flatMap(
                  _.fold(
                    e =>
                      E.raiseError[Option[BlockInfo]](
                        new RuntimeException(CommError.errorMessage(e))
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
            fieldType = StringType,
            description = "Subscribes to new blocks".some,
            resolve = _ =>
              Stream.emits[F, Action[Unit, String]](
                randomBytes.map(bs => Action(Base16.encode(bs)))
              )
          )
        )
      ).some
    )
  }
}
