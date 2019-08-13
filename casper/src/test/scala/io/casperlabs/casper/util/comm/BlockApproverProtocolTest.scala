package io.casperlabs.casper.util.comm

import io.casperlabs.casper.LegacyConversions
import io.casperlabs.casper.HashSetCasperTest
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.helper.HashSetCasperTestNode.Effect
import io.casperlabs.casper.helper.{
  TransportLayerCasperTestNode,
  TransportLayerCasperTestNodeFactory
}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.comm.protocol.routing.Packet
import io.casperlabs.comm.transport
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.shared.{FilesAPI, Log}
import monix.execution.Scheduler
import org.scalatest.{FlatSpec, Matchers}

class BlockApproverProtocolTest extends FlatSpec with Matchers {
  import BlockApproverProtocolTest._

  private implicit val scheduler: Scheduler = Scheduler.fixedPool("block-approval-protocol-test", 4)

  implicit def filesApi(implicit log: Log[Effect]) = FilesAPI.create[Effect]

  "BlockApproverProtocol" should "respond to valid ApprovedBlockCandidates" in {
    val n                          = 8
    val (validatorSk, validatorPk) = Ed25519.newKeyPair
    val bonds                      = Map(validatorPk -> 10L)
    createProtocol(n, Seq.empty, validatorSk, bonds).flatMap {
      case (approver, node) =>
        val unapproved = createUnapproved(n, LegacyConversions.fromBlock(node.genesis))
        import node._

        for {
          _ <- approver.unapprovedBlockPacketHandler[Effect](node.local, unapproved)

          _ = node.logEff.infos.exists(_.contains("Approval sent in response")) should be(true)
          _ = node.logEff.warns.isEmpty should be(true)

          queue  <- node.transportLayerEff.msgQueues(node.local).get
          result = queue.size should be(1)
        } yield result
    }
  }

  // Todo this is block by runtimeManager.replayComputeState
  it should "log a warning for invalid ApprovedBlockCandidates" in effectTest {
    val n                          = 8
    val (validatorSk, validatorPk) = Ed25519.newKeyPair
    val bonds                      = Map(validatorPk -> 10L)
    createProtocol(n, Seq.empty, validatorSk, bonds).flatMap {
      case (approver, node) =>
        val differentUnapproved1 = createUnapproved(
          n / 2,
          LegacyConversions.fromBlock(node.genesis)
        ) //wrong number of signatures
        val differentUnapproved2 = createUnapproved(n, BlockMessage.defaultInstance) //wrong block
        import node._

        for {
          _ <- approver.unapprovedBlockPacketHandler[Effect](
                node.local,
                differentUnapproved1
              )
          _ <- approver.unapprovedBlockPacketHandler[Effect](
                node.local,
                differentUnapproved2
              )

          _      = node.logEff.warns.count(_.contains("Received unexpected candidate")) should be(2)
          queue  <- node.transportLayerEff.msgQueues(node.local).get
          result = queue.isEmpty should be(true)
        } yield result
    }
  }
}

object BlockApproverProtocolTest extends TransportLayerCasperTestNodeFactory {
  def createUnapproved(requiredSigs: Int, block: BlockMessage): UnapprovedBlock =
    UnapprovedBlock(Some(ApprovedBlockCandidate(Some(block), requiredSigs)), 0L, 0L)

  def unapprovedToPacket(u: UnapprovedBlock): Packet =
    Packet(transport.UnapprovedBlock.id, u.toByteString)

  def createProtocol(
      requiredSigs: Int,
      wallets: Seq[PreWallet],
      sk: PrivateKey,
      bonds: Map[PublicKey, Long]
  ): Effect[(BlockApproverProtocol, TransportLayerCasperTestNode[Effect])] = {

    val deployTimestamp = 1L

    val BlockMsgWithTransform(Some(genesis), transforms) = HashSetCasperTest.buildGenesis(
      wallets,
      bonds,
      1L,
      Long.MaxValue,
      deployTimestamp
    )
    for {
      nodes <- networkEff(Vector(sk), genesis, transforms)
      node  = nodes.head
    } yield new BlockApproverProtocol(
      node.validatorId,
      bonds,
      wallets,
      BlockApproverProtocol.GenesisConf(
        minimumBond = 1L,
        maximumBond = Long.MaxValue,
        requiredSigs = requiredSigs,
        genesisAccountPublicKeyPath = None,
        initialMotes = 0L,
        mintCodePath = None,
        posCodePath = None,
        bondsPath = None
      )
    ) -> node
  }

}
