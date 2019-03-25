package io.casperlabs.casper.util.comm

import io.casperlabs.casper.HashSetCasperTest
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.helper.HashSetCasperTestNode
import io.casperlabs.casper.helper.HashSetCasperTestNode.Effect
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.comm.protocol.routing.Packet
import io.casperlabs.comm.transport
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.storage.BlockMsgWithTransform
import monix.execution.Scheduler
import org.scalatest.{FlatSpec, Matchers}

class BlockApproverProtocolTest extends FlatSpec with Matchers {
  import BlockApproverProtocolTest._

  private implicit val scheduler: Scheduler = Scheduler.fixedPool("block-approval-protocol-test", 4)

  "BlockApproverProtocol" should "respond to valid ApprovedBlockCandidates" in {
    val n                          = 8
    val (validatorSk, validatorPk) = Ed25519.newKeyPair
    val bonds                      = Map(validatorPk -> 10L)
    createProtocol(n, Seq.empty, validatorSk, bonds).flatMap {
      case (approver, node) =>
        val unapproved = createUnapproved(n, node.genesis)
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
        val differentUnapproved1 = createUnapproved(n / 2, node.genesis)             //wrong number of signatures
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

object BlockApproverProtocolTest {
  def createUnapproved(requiredSigs: Int, block: BlockMessage): UnapprovedBlock =
    UnapprovedBlock(Some(ApprovedBlockCandidate(Some(block), requiredSigs)), 0L, 0L)

  def unapprovedToPacket(u: UnapprovedBlock): Packet =
    Packet(transport.UnapprovedBlock.id, u.toByteString)

  def createProtocol(
      requiredSigs: Int,
      wallets: Seq[PreWallet],
      sk: Array[Byte],
      bonds: Map[Array[Byte], Long]
  ): Effect[(BlockApproverProtocol, HashSetCasperTestNode[Effect])] = {

    val deployTimestamp = 1L
    val validators      = bonds.map(b => ProofOfStakeValidator(b._1, b._2)).toSeq

    val BlockMsgWithTransform(Some(genesis), transforms) = HashSetCasperTest.buildGenesis(
      wallets,
      bonds,
      1L,
      Long.MaxValue,
      Faucet.noopFaucet,
      deployTimestamp
    )
    for {
      nodes <- HashSetCasperTestNode.networkEff(Vector(sk), genesis, transforms)
      node  = nodes.head
    } yield
      new BlockApproverProtocol(
        node.validatorId,
        deployTimestamp,
        bonds,
        wallets,
        1L,
        Long.MaxValue,
        false,
        requiredSigs
      ) -> node
  }

}
