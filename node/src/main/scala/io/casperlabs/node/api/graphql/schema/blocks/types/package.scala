package io.casperlabs.node.api.graphql.schema.blocks

import cats.syntax.either._
import cats.syntax.option._
import io.casperlabs.casper.api.BlockAPI.BlockAndMaybeDeploys
import io.casperlabs.casper.consensus.Block._
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.casper.consensus.info._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.node.api.graphql.schema.utils.{DateType, ProtocolVersionType}
import io.casperlabs.models.BlockImplicits._
import sangria.schema._
import sangria.macros.derive._

case class PageInfo(endCursor: String, hasNextPage: Boolean)

case class DeployInfosWithPageInfo(deployInfos: List[DeployInfo], pageInfo: PageInfo)

package object types {

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

  val BlockInfoInterface = InterfaceType(
    "BlockInfo",
    "Basic block information which doesn't require reading a full block",
    fields[Unit, BlockAndMaybeDeploys](
      Field(
        "blockHash",
        StringType,
        "Base-16 encoded BLAKE2b256 hash of the block header".some,
        resolve = c => Base16.encode(c.value._1.getSummary.blockHash.toByteArray)
      ),
      Field(
        "parentHashes",
        ListType(StringType),
        "Hashes of parent blocks".some,
        resolve = c => c.value._1.getSummary.parents.map(p => Base16.encode(p.toByteArray))
      ),
      Field(
        "justificationHashes",
        ListType(StringType),
        "Hashes of justification blocks".some,
        resolve = c =>
          c.value._1.getSummary.justifications
            .map(j => Base16.encode(j.latestBlockHash.toByteArray))
      ),
      Field(
        "childHashes",
        ListType(StringType),
        "Hashes of child blocks".some,
        resolve = c => c.value._1.getStatus.childHashes.map(p => Base16.encode(p.toByteArray))
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
        "Amount of hops needed to reach a genesis from the block".some,
        resolve = c => c.value._1.getSummary.rank
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
        "faultTolerance",
        FloatType,
        resolve = c => c.value._1.getStatus.faultTolerance.toDouble
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

  val BlockType = ObjectType(
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
