package io.casperlabs.node.api.graphql.schema

import java.time.{Instant, ZoneId, ZonedDateTime}

import cats.effect.Effect
import cats.effect.implicits._
import cats.implicits._
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.SafetyOracle
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.node.api.graphql.{FinalizedBlocksStream, Fs2SubscriptionStream}
import io.casperlabs.shared.Log
import sangria.schema._

private[graphql] class GraphQLSchemaBuilder[F[_]: Fs2SubscriptionStream: Log: Effect: MultiParentCasperRef: SafetyOracle: BlockStore: FinalizedBlocksStream] {

  // Not defining inputs yet because it's a read-only field
  val DateType: ScalarType[Long] = ScalarType[Long](
    "Date",
    coerceUserInput = _ => ???,
    coerceOutput =
      (l, _) => ZonedDateTime.ofInstant(Instant.ofEpochMilli(l), ZoneId.of("Z")).toString,
    coerceInput = _ => ???
  )

  val requireFullBlockFields = Set("blockSizeBytes", "deployErrorCount", "deploys")

  def hasAtLeastOne(projections: Vector[ProjectedName], fields: Set[String]): Boolean = {
    def flatToSet(ps: Vector[ProjectedName], acc: Set[String]): Set[String] =
      if (ps.isEmpty) {
        acc
      } else {
        val h = ps.head
        flatToSet(ps.tail, acc + h.name) ++ flatToSet(h.children, acc)
      }

    flatToSet(projections, Set.empty).intersect(fields).nonEmpty
  }

  def createSchema: Schema[Unit, Unit] =
    Schema(
      query = ObjectType(
        "Query",
        fields[Unit, Unit](
          Field(
            "block",
            OptionType(blocks.types.BlockType),
            arguments = blocks.arguments.BlockHashPrefix :: Nil,
            resolve = Projector { (context, projections) =>
              BlockAPI
                .getBlockInfoOpt[F](
                  blockHashBase16 = context.arg(blocks.arguments.BlockHashPrefix),
                  full = hasAtLeastOne(projections, requireFullBlockFields)
                )
                .toIO
                .unsafeToFuture()
            }
          ),
          Field(
            "dagSlice",
            ListType(blocks.types.BlockType),
            arguments = blocks.arguments.Depth :: blocks.arguments.MaxRank :: Nil,
            resolve = Projector { (context, projections) =>
              BlockAPI
                .getBlockInfosMaybeWithBlocks[F](
                  depth = context.arg(blocks.arguments.Depth),
                  maxRank = context.arg(blocks.arguments.MaxRank),
                  full = hasAtLeastOne(projections, requireFullBlockFields)
                )
                .toIO
                .unsafeToFuture()
            }
          ),
          Field(
            "deploy",
            OptionType(blocks.types.DeployInfoType),
            arguments = blocks.arguments.DeployHash :: Nil,
            resolve = c =>
              BlockAPI.getDeployInfoOpt[F](c.arg(blocks.arguments.DeployHash)).toIO.unsafeToFuture()
          )
        )
      ),
      subscription = ObjectType(
        "Subscription",
        fields[Unit, Unit](
          Field.subs(
            "finalizedBlocks",
            blocks.types.BlockType,
            "Subscribes to new finalized blocks".some,
            resolve = { c =>
              // Projectors don't work with Subscriptions
              val requireFullBlock = c.query.renderCompact
                .split("[^a-zA-Z0-9]")
                .collect {
                  case s if s.trim.nonEmpty => s.trim
                }
                .toSet
                .intersect(requireFullBlockFields)
                .nonEmpty
              FinalizedBlocksStream[F].subscribe.evalMap { blockHash =>
                BlockAPI
                  .getBlockInfoWithBlock[F](
                    blockHash = blockHash,
                    full = requireFullBlock
                  )
                  .map(Action(_))
              }
            }
          )
        )
      ).some
    )
}
