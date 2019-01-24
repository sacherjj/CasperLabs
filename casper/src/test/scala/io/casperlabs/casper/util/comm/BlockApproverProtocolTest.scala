package io.casperlabs.casper.util.comm

import java.nio.file.Paths

import cats.Id
import io.casperlabs.casper.HashSetCasperTest
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.helper.{BlockDagStorageTestFixture, HashSetCasperTestNode}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.rholang.RuntimeManager
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.comm.protocol.routing.Packet
import io.casperlabs.comm.transport
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.shared.StoreType
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}
import io.casperlabs.shared.TestOutlaws._

class BlockApproverProtocolTest extends FlatSpec with Matchers {
  import BlockApproverProtocolTest._

  "BlockApproverProtocol" should "respond to valid ApprovedBlockCandidates" ignore {
    val n                          = 8
    val (validatorSk, validatorPk) = Ed25519.newKeyPair
    val bonds                      = Map(validatorPk -> 10L)
    val (approver, node)           = createProtocol(n, Seq.empty, validatorSk, bonds)
    val unapproved                 = createUnapproved(n, node.genesis)
    import node._

    approver.unapprovedBlockPacketHandler[Id](node.local, unapproved)

    node.logEff.infos.exists(_.contains("Approval sent in response")) should be(true)
    node.logEff.warns.isEmpty should be(true)

    node.transportLayerEff.msgQueues(node.local).get.size should be(1)
  }

  // Todo this is block by runtimeManager.replayComputeState
  it should "log a warning for invalid ApprovedBlockCandidates" ignore {
    val n                          = 8
    val (validatorSk, validatorPk) = Ed25519.newKeyPair
    val bonds                      = Map(validatorPk -> 10L)
    val (approver, node)           = createProtocol(n, Seq.empty, validatorSk, bonds)
    val differentUnapproved1       = createUnapproved(n / 2, node.genesis) //wrong number of signatures
    val differentUnapproved2       = createUnapproved(n, BlockMessage.defaultInstance) //wrong block
    import node._

    approver.unapprovedBlockPacketHandler[Id](node.local, differentUnapproved1)
    approver.unapprovedBlockPacketHandler[Id](node.local, differentUnapproved2)

    node.logEff.warns.count(_.contains("Received unexpected candidate")) should be(2)

    node.transportLayerEff.msgQueues(node.local).get.isEmpty should be(true)
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
  ): (BlockApproverProtocol, HashSetCasperTestNode[Id]) = {
    import monix.execution.Scheduler.Implicits.global

    val casperSmartContractsApi = ExecutionEngineService.noOpApi[Task]()
    val runtimeManager          = RuntimeManager.fromExecutionEngineService(casperSmartContractsApi)

    val deployTimestamp = 1L
    val validators      = bonds.map(b => ProofOfStakeValidator(b._1, b._2)).toSeq

    val genesis = HashSetCasperTest.buildGenesis(
      wallets,
      bonds,
      1L,
      Long.MaxValue,
      Faucet.noopFaucet,
      deployTimestamp
    )
    val node = HashSetCasperTestNode.network(Vector(sk), genesis).head

    new BlockApproverProtocol(
      node.validatorId,
      deployTimestamp,
      runtimeManager,
      bonds,
      wallets,
      1L,
      Long.MaxValue,
      false,
      requiredSigs
    ) -> node
  }

}
