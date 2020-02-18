package io.casperlabs.node.api.graphql.schema.blocks

import cats.implicits._
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.api.BlockAPI.BlockAndMaybeDeploys
import io.casperlabs.casper.consensus.Block._
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.casper.consensus.info._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.node.api.graphql.RunToFuture
import io.casperlabs.node.api.graphql.schema.utils.{DateType, ProtocolVersionType}
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagStorage
import io.casperlabs.storage.deploy.DeployStorage
import sangria.execution.deferred._
import sangria.schema._

case class PageInfo(endCursor: String, hasNextPage: Boolean)

case class DeployInfosWithPageInfo(deployInfos: List[DeployInfo], pageInfo: PageInfo)

// format: off
class GraphQLBlockTypes[F[_]: MonadThrowable
                            : MultiParentCasperRef
                            : BlockStorage
                            : DeployStorage
                            : DagStorage
                            : RunToFuture] {
// format: on

  val SignatureType = ObjectType(
    "Signature",
    "Signature used to sign Block or Deploy",
    fields[Unit, Signature](
      Field(
        "algorithm",
        StringType,
        "Algorithm used to create a signature".some,
        resolve = c => c.value.sigAlgorithm
      ),
      Field(
        "signature",
        StringType,
        "Base-16 encoded signature".some,
        resolve = c => Base16.encode(c.value.sig.toByteArray)
      )
    )
  )

  val ApprovalType = ObjectType(
    "Approval",
    "Public key and signature used to approve Block or Deploy",
    fields[Unit, Approval](
      Field(
        "approverPublicKey",
        StringType,
        "Base-16 encoded public key of approver".some,
        resolve = c => Base16.encode(c.value.approverPublicKey.toByteArray)
      ),
      Field(
        "signature",
        SignatureType,
        "Signature used to sign a Deploy".some,
        resolve = c => c.value.getSignature
      )
    )
  )

  val DeployType = ObjectType(
    "Deploy",
    "Code that is able to be executed on CasperLabs blockchain",
    fields[Unit, Deploy](
      Field(
        "deployHash",
        StringType,
        "Base-16 encoded BLAKE2b256 hash of deploy's header".some,
        resolve = c => Base16.encode(c.value.deployHash.toByteArray)
      ),
      Field(
        "accountId",
        StringType,
        "Base-16 encoded account public key".some,
        resolve = c => Base16.encode(c.value.getHeader.accountPublicKey.toByteArray)
      ),
      Field(
        "timestamp",
        DateType,
        "Timestamp when the deploy was created".some,
        resolve = c => c.value.getHeader.timestamp
      ),
      Field(
        "gasPrice",
        LongType,
        "The price of gas for the deploy in units dust/gas.".some,
        resolve = c => c.value.getHeader.gasPrice
      ),
      Field(
        "approvals",
        ListType(ApprovalType),
        "Approvals used to sign the deploy".some,
        resolve = c => c.value.approvals.toList
      )
    )
  )

  val DeployResultInterface = InterfaceType(
    "DeployProcessingResult",
    "Basic information of results of a deploy processing",
    fields[Unit, Either[ProcessedDeploy, ProcessingResult]](
      Field(
        "cost",
        LongType,
        "Amount of gas spent to execute the deploy".some,
        resolve = c => c.value.fold(_.cost, _.cost)
      ),
      Field(
        "isError",
        BooleanType,
        "True if execution failed, false otherwise".some,
        resolve = c => c.value.fold(_.isError, _.isError)
      ),
      Field(
        "errorMessage",
        OptionType(StringType),
        "Error message if failed, null otherwise".some,
        resolve = c => Option(c.value.fold(_.errorMessage, _.errorMessage)).filter(_.nonEmpty)
      )
    )
  )

  val ProcessedDeployType = ObjectType(
    "ProcessedDeploy",
    "Results of executing a deploy",
    interfaces[Unit, Either[ProcessedDeploy, ProcessingResult]](DeployResultInterface),
    fields[Unit, Either[ProcessedDeploy, ProcessingResult]](
      Field("deploy", DeployType, resolve = c => c.value.left.get.getDeploy)
    )
  )

  val blockFetcher = Fetcher.caching(
    { (_: Unit, hashes: Seq[BlockHash]) =>
      // Fetches only unique blocks without repetitive reading of the same block many times.
      //
      // TODO: However, it still will be slow due to (in decreasing priority):
      // 1) One-by-one reading from database: update underlying API to accept multiple block hashes using 'WHERE block_hash IN ...'
      //
      // 2) Reading a full block: make use of Sangria Projections, although, not clear if it's possible to do without modifying the library's source code
      // UPDATE: It reads full blocks only if a query contains 'children' at any depth.
      // On the other hand, if 'children' presented, then it will read *all* blocks as FULL, even those for which we didn't ask children.
      // UPDATE: Make sure to fetch blocks only when it's really needed.
      // For instance, for 'block { parents { blockHash }' query it shouldn't fetch parent blocks, because all information is already presented in the child itself.
      //
      // 3) Seq->List conversion: least critical, must be ignored until the above 2 issues are solved
      RunToFuture[F].unsafeToFuture(
        hashes.toList
          .traverse { hash =>
            BlockAPI.getBlockInfoWithDeploys[F](
              hash,
              DeployInfo.View.BASIC.some,
              BlockInfo.View.FULL
            )
          }
          .map(list => list: Seq[BlockAndMaybeDeploys])
      )
    }
  )(HasId {
    case (blockInfo, _) =>
      blockInfo.getSummary.blockHash
  })

  lazy val BlockInfoInterface = InterfaceType(
    "BlockInfo",
    "Basic block information which doesn't require reading a full block",
    () =>
      fields[Unit, BlockAndMaybeDeploys](
        Field(
          "blockHash",
          StringType,
          "Base-16 encoded BLAKE2b256 hash of the block header".some,
          resolve = c => Base16.encode(c.value._1.getSummary.blockHash.toByteArray)
        ),
        Field(
          "parents",
          ListType(BlockType),
          "Parent blocks".some,
          resolve = c =>
            c.value match {
              case (blockInfo, _) => blockFetcher.deferSeq(blockInfo.getSummary.parents)
            }
        ),
        Field(
          "justifications",
          ListType(BlockType),
          "Justification blocks".some,
          resolve = c =>
            c.value match {
              case (blockInfo, _) =>
                blockFetcher.deferSeq(blockInfo.getSummary.justifications.map(_.latestBlockHash))
            }
        ),
        Field(
          "children",
          ListType(BlockType),
          "Child blocks".some,
          resolve = c =>
            c.value match {
              case (blockInfo, _) => blockFetcher.deferSeq(blockInfo.getStatus.childHashes)
            }
        ),
        Field(
          "timestamp",
          DateType,
          "Timestamp when the block was created".some,
          resolve = c => c.value._1.getSummary.timestamp
        ),
        Field(
          "protocolVersion",
          ProtocolVersionType,
          "Protocol version of CasperLabs blockchain".some,
          resolve = c => c.value._1.getSummary.getHeader.getProtocolVersion
        ),
        Field(
          "deployCount",
          IntType,
          "Amount of deploys in the block".some,
          resolve = c => c.value._1.getSummary.deployCount
        ),
        Field(
          "rank",
          LongType,
          "Amount of hops needed to reach a genesis from the block. Based on block's justifications".some,
          resolve = c => c.value._1.getSummary.jRank
        ),
        Field(
          "pRank",
          LongType,
          "Block height along the main-tree in the DAGA. Based on the block's main parent.".some,
          resolve = c => c.value._1.getSummary.pRank
        ),
        Field(
          "validatorPublicKey",
          StringType,
          "Base-16 encoded public key of a validator created the block".some,
          resolve = c => Base16.encode(c.value._1.getSummary.validatorPublicKey.toByteArray)
        ),
        Field(
          "validatorBlockSeqNum",
          IntType,
          "The block's number by the validator".some,
          resolve = c => c.value._1.getSummary.validatorBlockSeqNum
        ),
        Field(
          "chainName",
          StringType,
          "Chain name of where the block was created".some,
          resolve = c => c.value._1.getSummary.chainName
        ),
        Field(
          "signature",
          SignatureType,
          "Signature of validator created the block over blockHash".some,
          resolve = c => c.value._1.getSummary.getSignature
        ),
        Field(
          "deploys",
          ListType(ProcessedDeployType),
          "Deploys in the block".some,
          resolve = c => c.value._2.get.map(_.asLeft[ProcessingResult])
        ),
        Field(
          "isFinalized",
          BooleanType,
          resolve = c => c.value._1.getStatus.isFinalized
        ),
        Field(
          "blockSizeBytes",
          OptionType(IntType),
          resolve = c => c.value._1.getStatus.stats.map(_.blockSizeBytes)
        ),
        Field(
          "deployErrorCount",
          OptionType(IntType),
          resolve = c => c.value._1.getStatus.stats.map(_.deployErrorCount)
        ),
        Field(
          "deployCostTotal",
          OptionType(LongType),
          resolve = c => c.value._1.getStatus.stats.map(_.deployCostTotal)
        ),
        Field(
          "deployGasPriceAvg",
          OptionType(LongType),
          resolve = c => c.value._1.getStatus.stats.map(_.deployGasPriceAvg)
        )
      )
  )

  val ProcessingResultType = ObjectType(
    "ProcessingResult",
    "Full deploys processing result information",
    interfaces[Unit, Either[ProcessedDeploy, ProcessingResult]](
      DeployResultInterface
    ),
    fields[Unit, Either[ProcessedDeploy, ProcessingResult]](
      Field(
        "block",
        BlockInfoInterface,
        // Producing the BlockInfo from the one embedded in `DeployInfo.ProcessingResult`; it won't have further deploys with it for the block.
        resolve = c => (c.value.right.get.getBlockInfo, none[List[Block.ProcessedDeploy]])
      )
    )
  )

  val DeployInfoType = ObjectType(
    "DeployInfo",
    "Deploy information",
    fields[Unit, DeployInfo](
      Field(
        "deploy",
        DeployType,
        resolve = c => c.value.getDeploy
      ),
      Field(
        "processingResults",
        ListType(ProcessingResultType),
        resolve = c => c.value.processingResults.map(_.asRight[ProcessedDeploy]).toList
      )
    )
  )

  lazy val BlockType: ObjectType[Unit, BlockAndMaybeDeploys] = ObjectType(
    "Block",
    interfaces[Unit, BlockAndMaybeDeploys](BlockInfoInterface),
    fields[Unit, BlockAndMaybeDeploys](
      Field(
        "deploys",
        ListType(ProcessedDeployType),
        "Deploys in the block".some,
        resolve = c => c.value._2.get.map(_.asLeft[ProcessingResult])
      )
    )
  )

  val PageInfoType = ObjectType(
    "PageInfo",
    "Cursor based pagination information",
    fields[Unit, PageInfo](
      Field(
        "endCursor",
        StringType,
        "The cursor of the last item in result".some,
        resolve = _.value.endCursor
      ),
      Field(
        "hasNextPage",
        BooleanType,
        "Whether there is another page of data available".some,
        resolve = _.value.hasNextPage
      )
    )
  )

  val DeployInfosWithPageInfoType = ObjectType(
    "deploys",
    "A list of deploys for the specified account",
    fields[Unit, DeployInfosWithPageInfo](
      Field("deployInfos", ListType(DeployInfoType), resolve = _.value.deployInfos),
      Field("pageInfo", PageInfoType, resolve = _.value.pageInfo)
    )
  )
}
