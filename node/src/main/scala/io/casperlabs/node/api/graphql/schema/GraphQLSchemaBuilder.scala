package io.casperlabs.node.api.graphql.schema

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.info.{BlockInfo, DeployInfo}
import io.casperlabs.casper.consensus.state
import io.casperlabs.catscontrib.{Fs2Compiler, MonadThrowable}
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.node.api.{DeployInfoPagination, Utils}
import io.casperlabs.node.api.Utils.{
  validateAccountPublicKey,
  validateBlockHashPrefix,
  validateDeployHash
}
import io.casperlabs.node.api.casper.ListDeployInfosRequest
import io.casperlabs.node.api.graphql._
import io.casperlabs.node.api.graphql.RunToFuture.ops._
import io.casperlabs.node.api.graphql.schema.blocks.{DeployInfosWithPageInfo, PageInfo}
import io.casperlabs.node.api.DeployInfoPagination.DeployInfoPageTokenParams
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block._
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.storage.dag.DagStorage
import sangria.schema._

private[graphql] class GraphQLSchemaBuilder[F[_]: Fs2SubscriptionStream: Log: RunToFuture: MultiParentCasperRef: BlockStorage: FinalizedBlocksStream: MonadThrowable: ExecutionEngineService: DeployStorage: DagStorage: Fs2Compiler] {

  // GraphQL projections don't expose the body.
  val deployView = DeployInfo.View.BASIC

  private def projectionTerms(projections: Vector[ProjectedName]): Set[String] = {
    def flatToSet(ps: Vector[ProjectedName], acc: Set[String]): Set[String] =
      if (ps.isEmpty) {
        acc
      } else {
        val h = ps.head
        flatToSet(ps.tail, acc + h.name) ++ flatToSet(h.children, acc)
      }

    flatToSet(projections, Set.empty)
  }

  private def blockView(projections: Vector[ProjectedName]): BlockInfo.View =
    blockView(projectionTerms(projections))

  private def blockView(terms: Set[String]): BlockInfo.View =
    if (terms contains "childHashes")
      BlockInfo.View.FULL
    else
      BlockInfo.View.BASIC

  private def deployView(projections: Vector[ProjectedName]): Option[DeployInfo.View] =
    deployView(projectionTerms(projections))

  private def deployView(terms: Set[String]): Option[DeployInfo.View] =
    if (terms contains "deploys") Some(deployView) else None

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
                blockHashPrefix <- validateBlockHashPrefix[F](
                                    context.arg(blocks.arguments.BlockHashPrefix),
                                    ByteString.EMPTY
                                  )
                res <- BlockAPI
                        .getBlockInfoWithDeploysOpt[F](
                          blockHashBase16 = blockHashPrefix,
                          maybeDeployView = deployView(projections),
                          blockView = blockView(projections)
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
                .getBlockInfosWithDeploys[F](
                  depth = context.arg(blocks.arguments.Depth),
                  maxRank = context.arg(blocks.arguments.MaxRank),
                  maybeDeployView = deployView(projections),
                  blockView = blockView(projections)
                )
                .unsafeToFuture
            }
          ),
          Field(
            "deploy",
            OptionType(blocks.types.DeployInfoType),
            arguments = blocks.arguments.DeployHash :: Nil,
            resolve = { c =>
              (validateDeployHash[F](c.arg(blocks.arguments.DeployHash), ByteString.EMPTY) >>= (
                  deployHash => BlockAPI.getDeployInfoOpt[F](deployHash, deployView)
              )).unsafeToFuture
            }
          ),
          Field(
            "deploys",
            blocks.types.DeployInfosWithPageInfoType,
            arguments = blocks.arguments.AccountPublicKeyBase16 :: blocks.arguments.First :: blocks.arguments.After :: Nil,
            resolve = { c =>
              val program =
                for {
                  first <- Utils
                            .check[F, Int](
                              c.arg(blocks.arguments.First),
                              "First must be greater than 0",
                              _ > 0
                            )
                  accountPublicKeyBase16 = c.arg(blocks.arguments.AccountPublicKeyBase16)
                  after                  = c.arg(blocks.arguments.After)
                  accountPublicKeyBase16 <- validateAccountPublicKey[F](
                                             accountPublicKeyBase16,
                                             ByteString.EMPTY
                                           )
                  (pageSize, pageTokenParams) <- MonadThrowable[F]
                                                  .fromTry(
                                                    DeployInfoPagination
                                                      .parsePageToken(
                                                        ListDeployInfosRequest(
                                                          pageSize = first,
                                                          pageToken = after
                                                        )
                                                      )
                                                  )
                  accountPublicKeyBs = PublicKey(
                    ByteString.copyFrom(
                      Base16.decode(accountPublicKeyBase16)
                    )
                  )
                  deploysWithOneMoreElem <- DeployStorage[F]
                                             .reader(deployView)
                                             .getDeploysByAccount(
                                               accountPublicKeyBs,
                                               pageSize + 1,
                                               pageTokenParams.lastTimeStamp,
                                               pageTokenParams.lastDeployHash,
                                               true
                                             )
                  (deploys, hasNextPage) = if (deploysWithOneMoreElem.length == pageSize + 1) {
                    (deploysWithOneMoreElem.take(pageSize), true)
                  } else {
                    (deploysWithOneMoreElem, false)
                  }
                  deployInfos <- DeployStorage[F]
                                  .reader(deployView)
                                  .getDeployInfos(deploys)
                  endCursor = DeployInfoPagination.createPageToken(
                    deploys.lastOption.map(
                      d =>
                        DeployInfoPageTokenParams(
                          d.getHeader.timestamp,
                          d.deployHash,
                          isNext = true
                        )
                    )
                  )
                  pageInfo = PageInfo(endCursor, hasNextPage)
                  result   = DeployInfosWithPageInfo(deployInfos, pageInfo)
                } yield result
              program.unsafeToFuture
            }
          ),
          Field(
            "globalState",
            ListType(OptionType(globalstate.types.Value)),
            arguments = globalstate.arguments.StateQueryArgument :: blocks.arguments.BlockHashPrefix :: Nil,
            resolve = { c =>
              val queries = c.arg(globalstate.arguments.StateQueryArgument).toList

              val program = for {
                blockHashBase16Prefix <- validateBlockHashPrefix[F](
                                          c.arg(blocks.arguments.BlockHashPrefix),
                                          ByteString.EMPTY
                                        )
                maybeBlockProps <- BlockAPI
                                    .getBlockInfoWithDeploysOpt[F](
                                      blockHashBase16Prefix,
                                      maybeDeployView = None,
                                      blockView = BlockInfo.View.BASIC
                                    )
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
              val terms = c.query.renderCompact
                .split("[^a-zA-Z0-9]")
                .collect {
                  case s if s.trim.nonEmpty => s.trim
                }
                .toSet

              FinalizedBlocksStream[F].subscribe.evalMap { blockHash =>
                BlockAPI
                  .getBlockInfoWithDeploys[F](
                    blockHash = blockHash,
                    maybeDeployView = deployView(terms),
                    blockView = blockView(terms)
                  )
                  .map(Action(_))
              }
            }
          )
        )
      ).some
    )
}
