package io.casperlabs.casper

import io.casperlabs.casper.consensus
import io.casperlabs.casper.protocol

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
          .withBodyHash(block.getHeader.deploysHash)
          .withTimestamp(block.getHeader.timestamp)
          .withProtocolVersion(block.getHeader.protocolVersion)
          .withDeployCount(block.getHeader.deployCount)
          .withChainId(block.shardId)
          .withValidatorBlockSeqNum(block.seqNum)
          .withValidatorPublicKey(block.sender)
          .withRank(block.getBody.getState.blockNumber.toInt)
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
              .withDeploy(
                consensus
                  .Deploy()
                  //.withDeployHash() // Legacy doesn't have it.
                  .withHeader(
                    consensus.Deploy
                      .Header()
                      // TODO: The client isn't signing the deploy yet, but it's sending an account address.
                      // Once we sign the deploy, we can derive the account address from it on the way back.
                      //.withAccountPublicKey(x.getDeploy.user)
                      .withAccountPublicKey(x.getDeploy.address)
                      .withNonce(x.getDeploy.nonce)
                      .withTimestamp(x.getDeploy.timestamp)
                      //.withBodyHash() // Legacy doesn't have it.
                      .withGasPrice(x.getDeploy.gasPrice)
                  )
                  .withBody(
                    consensus.Deploy
                      .Body()
                      .withSession(
                        consensus.Deploy
                          .Code()
                          .withCode(x.getDeploy.getSession.code)
                          .withArgs(x.getDeploy.getSession.args)
                      )
                      .withPayment(
                        consensus.Deploy
                          .Code()
                          .withCode(x.getDeploy.getPayment.code)
                          .withArgs(x.getDeploy.getPayment.args)
                      )
                  )
                  .withSignature(
                    consensus
                      .Signature()
                      .withSigAlgorithm(x.getDeploy.sigAlgorithm)
                      .withSig(x.getDeploy.signature)
                  )
              )
              .withCost(x.cost)
              .withIsError(x.errored)
          //.withErrorMessage() // Legacy doesn't have it.
          })
      )
      .withSignature(summary.getSignature)
  }

  def fromBlock(block: consensus.Block): protocol.BlockMessage = ???
}
