package io.casperlabs.comm.discovery

import cats.Id
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.comm._
import org.scalatest._

import scala.collection.mutable

class KademliaSpec extends FunSpec with Matchers with BeforeAndAfterEach {
  val local = createPeer("00000001")
  val peer0 = createPeer("00000010")
  val peer1 = createPeer("00001000")
  val peer2 = createPeer("00001001")
  val peer3 = createPeer("00001010")
  val peer4 = createPeer("00001100")

  val DISTANCE_4 = 4
  val DISTANCE_6 = 6

  var table       = PeerTable[Id](local.id, 3)
  var pingedPeers = mutable.MutableList.empty[PeerNode]

  override def beforeEach(): Unit = {
    table = PeerTable(local.id, 3)
    pingedPeers = mutable.MutableList.empty[PeerNode]
    // peer1-4 distance is 4
    table.distance(peer1) shouldBe DISTANCE_4
    table.distance(peer2) shouldBe DISTANCE_4
    table.distance(peer3) shouldBe DISTANCE_4
    table.distance(peer4) shouldBe DISTANCE_4
  }

  describe("A PeertTable with 1 byte addresses and k = 3") {
    describe("when adding a peer to an empty table") {
      it("should add it to a bucket according to its distance") {
        // given
        implicit val ping: KademliaRPC[Id] = pingOk
        table.distance(peer0) shouldBe DISTANCE_6
        // when
        table.updateLastSeen(peer0)
        // then
        bucketEntriesAt(DISTANCE_6) should contain theSameElementsAs Seq(peer0)
      }

      it("should not ping the peer") {
        // given
        implicit val ping: KademliaRPC[Id] = pingOk
        // when
        table.updateLastSeen(peer0)
        // then
        pingedPeers should contain theSameElementsAs Seq.empty[PeerNode]
      }
    }

    describe("when adding a peer when that peer already exists but with different IP") {
      it("should replace peer with new entry (the one with new IP)") {
        // given
        implicit val ping: KademliaRPC[Id] = pingOk
        table.updateLastSeen(peer1)
        // when
        val newPeer1 = peer1.copy(endpoint = Endpoint("otherIP", 0, 0))
        table.updateLastSeen(newPeer1)
        // then
        bucketEntriesAt(DISTANCE_4) should contain theSameElementsAs Seq(newPeer1)
      }

      it("should move peer to the end of the bucket (meaning it's been seen lately)") {
        // given
        implicit val ping: KademliaRPC[Id] = pingOk
        table.updateLastSeen(peer2)
        table.updateLastSeen(peer1)
        table.updateLastSeen(peer3)
        bucketEntriesAt(DISTANCE_4) should contain theSameElementsAs Seq(peer2, peer1, peer3)
        // when
        val newPeer1 = peer1.copy(endpoint = Endpoint("otherIP", 0, 0))
        table.updateLastSeen(newPeer1)
        // then
        bucketEntriesAt(DISTANCE_4) should contain theSameElementsAs Seq(peer2, peer3, newPeer1)
      }
    }

    describe("when adding a peer to a table, where corresponding bucket is filled but not full") {
      it("should add peer to the end of the bucket (meaning it's been seen lately)") {
        // given
        implicit val ping: KademliaRPC[Id] = pingOk
        table.updateLastSeen(peer2)
        table.updateLastSeen(peer3)
        bucketEntriesAt(DISTANCE_4) should contain theSameElementsAs Seq(peer2, peer3)
        // when
        table.updateLastSeen(peer1)
        // then
        bucketEntriesAt(DISTANCE_4) should contain theSameElementsAs Seq(peer2, peer3, peer1)
      }

      it("no peers should be pinged") {
        // given
        implicit val ping: KademliaRPC[Id] = pingOk
        table.updateLastSeen(peer2)
        table.updateLastSeen(peer3)
        bucketEntriesAt(DISTANCE_4) should contain theSameElementsAs Seq(peer2, peer3)
        // when
        table.updateLastSeen(peer1)
        // then
        pingedPeers should contain theSameElementsAs Seq.empty[PeerNode]
      }
    }

    describe("when adding a peer to a table, where corresponding bucket is full") {
      it("should ping the oldest peer to check if it responds") {
        // given
        implicit val ping: KademliaRPC[Id] = pingOk
        thatBucket4IsFull
        // when
        table.updateLastSeen(peer4)
        // then
        pingedPeers should contain theSameElementsAs Seq(peer1)
      }

      describe("and oldest peer IS responding to ping") {
        it("should drop the new peer") {
          // given
          implicit val ping: KademliaRPC[Id] = pingOk
          thatBucket4IsFull
          // when
          table.updateLastSeen(peer4)
          // then
          bucketEntriesAt(DISTANCE_4) should contain theSameElementsAs Seq(peer2, peer3, peer1)
        }
      }
      describe("and oldest peer is NOT responding to ping") {
        it("should add the new peer and drop the oldest one") {
          // given
          implicit val ping: KademliaRPC[Id] = pingFail
          thatBucket4IsFull
          // when
          table.updateLastSeen(peer4)
          // then
          bucketEntriesAt(DISTANCE_4) should contain theSameElementsAs Seq(peer2, peer3, peer4)
        }
      }
    }
  }

  private def thatBucket4IsFull(implicit ev: KademliaRPC[Id]): Unit = {
    table.updateLastSeen(peer1)
    table.updateLastSeen(peer2)
    table.updateLastSeen(peer3)
  }

  private def bucketEntriesAt(distance: Int): Seq[PeerNode] =
    table.tableRef.get(distance).map(_.node)

  private val pingOk: KademliaRPC[Id]   = new KademliaRPCMock(returns = true)
  private val pingFail: KademliaRPC[Id] = new KademliaRPCMock(returns = false)

  private class KademliaRPCMock(returns: Boolean) extends KademliaRPC[Id] {
    def ping(peer: PeerNode): Boolean = {
      pingedPeers += peer
      returns
    }
    def lookup(id: NodeIdentifier, peer: PeerNode): Seq[PeerNode] = Seq.empty[PeerNode]
    def receive(
        pingHandler: PeerNode => Id[Unit],
        lookupHandler: (PeerNode, NodeIdentifier) => Id[Seq[PeerNode]]
    ): Id[Unit]              = ()
    def shutdown(): Id[Unit] = ()
  }

  private def createPeer(id: String): PeerNode =
    PeerNode(NodeIdentifier(Seq(id.b)), Endpoint(id, 0, 0))
}
