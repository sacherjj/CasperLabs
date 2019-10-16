package io.casperlabs.node.api.graphql.schema

import cats.implicits._
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.state
import io.casperlabs.casper.finality.singlesweep.FinalityDetector
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.node.api.Utils
import io.casperlabs.node.api.graphql.RunToFuture.ops._
import io.casperlabs.node.api.graphql._
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block._
import io.casperlabs.storage.deploy.DeployStorageReader
import sangria.schema._

private[graphql] class GraphQLSchemaBuilder[F[_]: Fs2SubscriptionStream: Log: RunToFuture: MultiParentCasperRef: FinalityDetector: BlockStorage: FinalizedBlocksStream: MonadThrowable: ExecutionEngineService: DeployStorageReader: Fs2Compiler] {

  val requireFullBlockFields: Set[String] = Set("blockSizeBytes", "deployErrorCount", "deploys")

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

  def checkString(s: String, desc: String, b: String => Boolean): F[String] =
    MonadThrowable[F].raiseError[String](new IllegalArgumentException(s"$desc")).whenA(!b(s)) >> s
      .pure[F]

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
              (for {
                blockHashPrefix <- checkString(
                                    context.arg(blocks.arguments.BlockHashPrefix),
                                    "BlockHash prefix must be at least 4 characters (2 bytes) long",
                                    s => Base16.tryDecode(s.take(4)).nonEmpty
                                  )
                res <- BlockAPI
                        .getBlockInfoOpt[F](
                          blockHashBase16 = blockHashPrefix,
                          full = hasAtLeastOne(projections, requireFullBlockFields)
                        )
              } yield res).unsafeToFuture
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
                .unsafeToFuture
            }
          ),
          Field(
            "deploy",
            OptionType(blocks.types.DeployInfoType),
            arguments = blocks.arguments.DeployHash :: Nil,
            resolve = { c =>
              (checkString(
                c.arg(blocks.arguments.DeployHash),
                "DeployHash must be 64 characters (32 bytes) long",
                Base16.tryDecode(_).exists(_.length == 32)
              ) >>= (deployHash => BlockAPI.getDeployInfoOpt[F](deployHash))).unsafeToFuture
            }
          ),
          Field(
            "globalState",
            ListType(OptionType(globalstate.types.Value)),
            arguments = globalstate.arguments.StateQueryArgument :: blocks.arguments.BlockHashPrefix :: Nil,
            resolve = { c =>
              val queries = c.arg(globalstate.arguments.StateQueryArgument).toList

              val program = for {
                blockHashBase16Prefix <- checkString(
                                          c.arg(blocks.arguments.BlockHashPrefix),
                                          "BlockHash prefix must be at least 4 characters (2 bytes) long",
                                          s => Base16.tryDecode(s.take(4)).nonEmpty
                                        )
                maybeBlockProps <- BlockAPI
                                    .getBlockInfoOpt[F](blockHashBase16Prefix)
                                    .map(_.map {
                                      case (info, _) =>
                                        info.getSummary.getHeader.getState.postStateHash ->
                                          info.getSummary.getHeader.getProtocolVersion
                                    })
                values <- maybeBlockProps.fold(List.empty[Option[state.Value]].pure[F]) {
                           case (stateHash, protocolVersion) =>
                             for {

                               values <- queries.traverse {
                                          query =>
                                            for {
                                              key <- Utils.toKey[F](
                                                      query.keyType,
                                                      query.key
                                                    )
                                              possibleResponse <- ExecutionEngineService[F]
                                                                   .query(
                                                                     stateHash,
                                                                     key,
                                                                     query.pathSegments,
                                                                     protocolVersion
                                                                   )
                                              value <- MonadThrowable[F]
                                                        .fromEither(possibleResponse)
                                                        .map(_.some)
                                                        .handleError {
                                                          case SmartContractEngineError(message)
                                                              if message contains "Value not found" =>
                                                            none[state.Value]
                                                        }
                                            } yield value
                                        }
                             } yield values
                         }
              } yield values
              program.unsafeToFuture
            }
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
