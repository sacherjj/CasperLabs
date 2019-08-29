package io.casperlabs.casper

import com.google.protobuf.ByteString
import io.casperlabs.casper.util.ProtoUtil

import scala.util.Try

/** Convert between the message in CasperMessage.proto and consensus.proto while we have both.
  * This is assuming that the storage and validation are still using the protocol.* types,
  * and that the consensus.* ones are just used for communication, so hashes don't have to be
  * correct on the new objects, they are purely serving as DTOs. */
object LegacyConversions {

  def toBlock(block: protocol.BlockMessage): consensus.Block =
    consensus
      .Block()
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
                  .withDummy(true)
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
      .copy(
        signature = Option(
          consensus
            .Signature()
            .withSigAlgorithm(block.sigAlgorithm)
            .withSig(block.sig)
        ).filterNot(s => s.sigAlgorithm.isEmpty && s.sig.isEmpty)
      )

  def fromBlock(block: consensus.Block): protocol.BlockMessage = {
    val (deploysHash, stateHash, headerExtraBytes) =
      Try(protocol.BodyHashes.parseFrom(block.getHeader.bodyHash.toByteArray)).toOption
        .filter(_.dummy) match {
        case Some(bodyHashes) =>
          // `block` was originally created from a BlockMessage by `toBlock`.
          (bodyHashes.deploysHash, bodyHashes.stateHash, ByteString.EMPTY)
        case None =>
          // `block` came first, we want to transfer it as a BlockMessage.
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
      .withShardId(block.getHeader.chainId)
      .withSig(block.getSignature.sig)
      .withSigAlgorithm(block.getSignature.sigAlgorithm)
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
      .withSigAlgorithm(deploy.approvals.headOption.fold("")(_.getSignature.sigAlgorithm))
      .withSignature(deploy.approvals.headOption.fold(ByteString.EMPTY)(_.getSignature.sig))
  //.withUser() // We weren't using signing when this was in use.

  def toDeploy(deploy: protocol.DeployData): consensus.Deploy = {
    val body = consensus.Deploy
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

    val header = consensus.Deploy
      .Header()
      // The client can either send a public key or an account address here.
      // If they signed the deploy, we can derive the account address from the key.
      //.withAccountPublicKey(x.getDeploy.user)
      .withAccountPublicKey(deploy.address)
      .withNonce(deploy.nonce)
      .withTimestamp(deploy.timestamp)
      .withGasPrice(deploy.gasPrice)
      .withBodyHash(ProtoUtil.protoHash(body)) // Legacy doesn't have it.

    consensus
      .Deploy()
      .withDeployHash(ProtoUtil.protoHash(header)) // Legacy doesn't have it.
      .withHeader(header)
      .withBody(body)
      .withApprovals(
        List(
          consensus
            .Approval()
            .withApproverPublicKey(header.accountPublicKey)
            .withSignature(
              consensus
                .Signature()
                .withSigAlgorithm(deploy.sigAlgorithm)
                .withSig(deploy.signature)
            )
        ).filterNot(x => x.getSignature.sigAlgorithm.isEmpty && x.getSignature.sig.isEmpty)
      )
  }
}
