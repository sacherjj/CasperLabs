package io.casperlabs.casper

import com.google.protobuf.ByteString
import scala.util.{Failure, Success, Try}

/** Convert between the message in CasperMessage.proto and consensus.proto while we have both.
  * This is assuming that the storage and validation are still using the protocol.* types,
  * and that the consensus.* ones are just used for communication, so hashes don't have to be
  * correct on the new objects, they are purely serving as DTOs. */
object LegacyConversions {
  def toBlockSummary(block: protocol.BlockMessage): consensus.BlockSummary =
    consensus
      .BlockSummary()
      .withBlockHash(block.blockHash)
      .withHeader(
        consensus.Block
          .Header()
          .withParentHashes(block.getHeader.parentsHashList)
          .withJustifications(block.justifications.map { x =>
            consensus.Block
              .Justification()
              .withValidatorPublicKey(x.validator)
              .withLatestBlockHash(x.latestBlockHash)
          })
          .withState(
            consensus.Block
              .GlobalState()
              .withPreStateHash(block.getBody.getState.preStateHash)
              .withPostStateHash(block.getBody.getState.postStateHash)
              .withBonds(block.getBody.getState.bonds.map { x =>
                consensus
                  .Bond()
                  .withValidatorPublicKey(x.validator)
                  .withStake(x.stake)
              })
          )
          .withBodyHash(
            // The new structure has body hash, but the old has two separate fields.
            // In order to be able to restore them the `BodyHashes` message was introduced.
            Option(block.getHeader.extraBytes).filterNot(_.isEmpty).getOrElse {
              ByteString.copyFrom(
                protocol
                  .BodyHashes()
                  .withDeploysHash(block.getHeader.deploysHash)
                  .withStateHash(block.getHeader.postStateHash)
                  .toByteArray
              )
            }
          )
          .withTimestamp(block.getHeader.timestamp)
          .withProtocolVersion(block.getHeader.protocolVersion)
          .withDeployCount(block.getHeader.deployCount)
          .withChainId(block.shardId)
          .withValidatorBlockSeqNum(block.seqNum)
          .withValidatorPublicKey(block.sender)
          .withRank(block.getBody.getState.blockNumber)
      )
      .withSignature(
        consensus
          .Signature()
          .withSigAlgorithm(block.sigAlgorithm)
          .withSig(block.sig)
      )

  def toBlock(block: protocol.BlockMessage): consensus.Block = {
    val summary = toBlockSummary(block)
    consensus
      .Block()
      .withBlockHash(summary.blockHash)
      .withHeader(summary.getHeader)
      .withBody(
        consensus.Block
          .Body()
          .withDeploys(block.getBody.deploys.map { x =>
            consensus.Block
              .ProcessedDeploy()
              .withDeploy(toDeploy(x.getDeploy))
              .withCost(x.cost)
              .withIsError(x.errored)
              //.withErrorMessage() // Legacy doesn't have it.
              .withErrorMessage(
                Option(x.getDeploy.gasLimit).filterNot(_ == 0).map(_.toString).getOrElse("")
              ) // New version doesn't have limit. Preserve it so signatures can be checked.
          })
      )
      .withSignature(summary.getSignature)
  }

  def fromBlock(block: consensus.Block): protocol.BlockMessage = {
    val (deploysHash, stateHash, headerExtraBytes) =
      Try(protocol.BodyHashes.parseFrom(block.getHeader.bodyHash.toByteArray)) match {
        case Success(bodyHashes) =>
          (bodyHashes.deploysHash, bodyHashes.stateHash, ByteString.EMPTY)
        case Failure(_) =>
          (ByteString.EMPTY, ByteString.EMPTY, block.getHeader.bodyHash)
      }
    protocol
      .BlockMessage()
      .withBlockHash(block.blockHash)
      .withHeader(
        protocol
          .Header()
          .withParentsHashList(block.getHeader.parentHashes)
          .withPostStateHash(stateHash)
          .withDeploysHash(deploysHash)
          .withTimestamp(block.getHeader.timestamp)
          .withProtocolVersion(block.getHeader.protocolVersion)
          .withDeployCount(block.getHeader.deployCount)
          .withExtraBytes(headerExtraBytes)
      )
      .withBody(
        protocol
          .Body()
          .withState(
            protocol
              .RChainState()
              .withPreStateHash(block.getHeader.getState.preStateHash)
              .withPostStateHash(block.getHeader.getState.postStateHash)
              .withBonds(block.getHeader.getState.bonds.map { x =>
                protocol
                  .Bond()
                  .withValidator(x.validatorPublicKey)
                  .withStake(x.stake)
              })
              .withBlockNumber(block.getHeader.rank.toLong)
          )
          .withDeploys(block.getBody.deploys.map { x =>
            protocol
              .ProcessedDeploy()
              .withDeploy(
                fromDeploy(
                  x.getDeploy,
                  gasLimit = Try(x.errorMessage.toLong).toOption.getOrElse(0L)
                )
              )
              .withCost(x.cost)
              .withErrored(x.isError)
          })
      )
      .withJustifications(block.getHeader.justifications.map { x =>
        protocol
          .Justification()
          .withValidator(x.validatorPublicKey)
          .withLatestBlockHash(x.latestBlockHash)
      })
      .withSender(block.getHeader.validatorPublicKey)
      .withSeqNum(block.getHeader.validatorBlockSeqNum)
      .withSig(block.getSignature.sig)
      .withSigAlgorithm(block.getSignature.sigAlgorithm)
      .withShardId(block.getHeader.chainId)
  }

  def fromDeploy(deploy: consensus.Deploy, gasLimit: Long = 0L): protocol.DeployData =
    protocol
      .DeployData()
      .withAddress(deploy.getHeader.accountPublicKey) // TODO: Once we sign deploys, this needs to be derived.
      .withTimestamp(deploy.getHeader.timestamp)
      .withSession(
        protocol
          .DeployCode()
          .withCode(deploy.getBody.getSession.code)
          .withArgs(deploy.getBody.getSession.args)
      )
      .withPayment(
        protocol
          .DeployCode()
          .withCode(deploy.getBody.getPayment.code)
          .withArgs(deploy.getBody.getPayment.args)
      )
      .withGasLimit(gasLimit) // New version doesn't have it.
      .withGasPrice(deploy.getHeader.gasPrice)
      .withNonce(deploy.getHeader.nonce)
      .withSigAlgorithm(deploy.getSignature.sigAlgorithm)
      .withSignature(deploy.getSignature.sig)
  //.withUser() // We aren't signing deploys yet.

  def toDeploy(deploy: protocol.DeployData): consensus.Deploy =
    consensus
      .Deploy()
      //.withDeployHash() // Legacy doesn't have it.
      .withHeader(
        consensus.Deploy
          .Header()
          // TODO: The client isn't signing the deploy yet, but it's sending an account address.
          // Once we sign the deploy, we can derive the account address from it on the way back.
          //.withAccountPublicKey(x.getDeploy.user)
          .withAccountPublicKey(deploy.address)
          .withNonce(deploy.nonce)
          .withTimestamp(deploy.timestamp)
          //.withBodyHash() // Legacy doesn't have it.
          .withGasPrice(deploy.gasPrice)
      )
      .withBody(
        consensus.Deploy
          .Body()
          .withSession(
            consensus.Deploy
              .Code()
              .withCode(deploy.getSession.code)
              .withArgs(deploy.getSession.args)
          )
          .withPayment(
            consensus.Deploy
              .Code()
              .withCode(deploy.getPayment.code)
              .withArgs(deploy.getPayment.args)
          )
      )
      .withSignature(
        consensus
          .Signature()
          .withSigAlgorithm(deploy.sigAlgorithm)
          .withSig(deploy.signature)
      )
}
