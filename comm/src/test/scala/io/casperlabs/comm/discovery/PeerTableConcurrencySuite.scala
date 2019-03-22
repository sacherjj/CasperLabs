package io.casperlabs.comm.discovery
import java.util.concurrent.atomic.AtomicLong

import io.casperlabs.comm.{Endpoint, NodeIdentifier, PeerNode}
import monix.eval.Task
import monix.execution.CancelablePromise
import monix.execution.Scheduler.Implicits.global
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Matchers, PropSpec}

import scala.concurrent.duration._
import scala.util.Random

class PeerTableConcurrencySuite extends PropSpec with GeneratorDrivenPropertyChecks with Matchers {
  private trait KademliaMock extends KademliaRPC[Task] {
    override def lookup(id: NodeIdentifier, peer: PeerNode): Task[Seq[PeerNode]] =
      Task.now(Seq.empty)
    override def receive(
        pingHandler: PeerNode => Task[Unit],
        lookupHandler: (PeerNode, NodeIdentifier) => Task[Seq[PeerNode]]): Task[Unit] = Task.unit
    override def shutdown(): Task[Unit]                                               = Task.unit
  }

  //1 byte width
  private val id = NodeIdentifier("00")
  //First bit must be equal to 1
  private val distance   = 0
  private val bucketSize = 20
  // 1000 0000
  private val min: Byte = 128.toByte
  // 1111 1111
  private val max: Byte = 255.toByte
  private val maxPeersN = max - min + 1
  private val allPotentialPeers =
    Seq
      .iterate(min, maxPeersN)(b => (b + 1).toByte)
      .map(b => PeerNode(NodeIdentifier(Seq(b)), Endpoint("", 0, 0)))
  private implicit val propCheckConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(
    minSuccessful = 500)

  property("""
      |updateLastSeen
      |atomically adds new unique peer if bucket is not full
      |and moves it if has seen previously""".stripMargin) {
    forAll(
      Gen
        .choose(1, bucketSize)) { uniquePeersN: Int =>
      implicit val K: KademliaMock = (_: PeerNode) => Task.now(true)

      val unique     = Random.shuffle(allPotentialPeers).take(uniquePeersN)
      val replicated = Seq.fill(10)(unique).flatten
      val addNodesParallel = for {
        peerTable <- PeerTable[Task](id, bucketSize)
        _         <- Task.gatherUnordered(replicated.map(peerTable.updateLastSeen(_)))
        bucket    <- peerTable.tableRef.get.map(_(distance).map(_.node))
      } yield bucket

      addNodesParallel.runSyncUnsafe() should contain theSameElementsAs unique
    }
  }

  property("""
             |updateLastSeen
             |doesn't block read operations
           """.stripMargin) {
    forAll { _: Int =>
      val never                    = CancelablePromise[Boolean]()
      implicit val K: KademliaMock = (_: PeerNode) => Task.fromCancelablePromise(never)

      val initial    = Random.shuffle(allPotentialPeers).take(bucketSize)
      val rest       = Random.shuffle(allPotentialPeers.diff(initial))
      val peerTable  = PeerTable[Task](id, bucketSize).runSyncUnsafe()
      val fillBucket = Task.sequence(initial.map(peerTable.updateLastSeen(_)))
      val hangUp     = Task.gatherUnordered(rest.map(peerTable.updateLastSeen(_)))

      fillBucket.runSyncUnsafe()
      hangUp.runAsyncAndForget

      Task
        .race(Task.sleep(1.second), peerTable.peers)
        .runSyncUnsafe()
        .right
        .get should contain theSameElementsAs initial
    }
  }

  property("""
             |updateLastSeen
             |atomically pings peers
           """.stripMargin) {
    forAll { _: Int =>
      val pingsCounter = new AtomicLong(0)
      implicit val K: KademliaMock = (_: PeerNode) => {
        pingsCounter.incrementAndGet()
        Task.now(true)
      }

      val initial        = Random.shuffle(allPotentialPeers).take(bucketSize)
      val restReplicated = Random.shuffle(Seq.fill(10)(allPotentialPeers.diff(initial))).flatten

      val addNodesParallel = for {
        peerTable <- PeerTable[Task](id, bucketSize)
        _         <- Task.gatherUnordered(initial.map(peerTable.updateLastSeen(_)))
        _         <- Task.gatherUnordered(restReplicated.map(peerTable.updateLastSeen(_)))
        peers     <- peerTable.peers
      } yield peers

      addNodesParallel.runSyncUnsafe() should contain theSameElementsAs initial
      pingsCounter.get() shouldBe restReplicated.size
    }
  }
}
