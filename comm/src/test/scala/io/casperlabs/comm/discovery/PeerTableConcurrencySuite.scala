package io.casperlabs.comm.discovery
import java.util.concurrent.atomic.AtomicLong

import com.google.protobuf.ByteString
import monix.eval.Task
import monix.execution.CancelablePromise
import monix.execution.Scheduler.Implicits.global
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{Matchers, PropSpec}

import scala.concurrent.duration._
import scala.util.Random

class PeerTableConcurrencySuite extends PropSpec with GeneratorDrivenPropertyChecks with Matchers {
  private trait KademliaMock extends KademliaService[Task] {
    override def lookup(id: NodeIdentifier, peer: Node): Task[Option[Seq[Node]]] =
      Task.now(None)
    override def receive(
        pingHandler: Node => Task[Unit],
        lookupHandler: (Node, NodeIdentifier) => Task[Seq[Node]]
    ): Task[Unit]                       = Task.unit
    override def shutdown(): Task[Unit] = Task.unit
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
      .map(b => Node(ByteString.copyFrom(Array(b))))
  private implicit val propCheckConfig: PropertyCheckConfiguration = PropertyCheckConfiguration(
    minSuccessful = 500
  )

  property(
    """
      |updateLastSeen
      |atomically adds new unique peer if bucket is not full and moves it if has seen previously;
      |there shouldn't be any ping requests, since 'uniquePeersN' <= bucketSize""".stripMargin
  ) {
    forAll(
      Gen
        .choose(1, bucketSize)
    ) { uniquePeersN: Int =>
      var pingCounter = 0
      implicit val K: KademliaMock = (_: Node) => {
        Task(pingCounter += 1).map(_ => true)
      }

      val unique     = Random.shuffle(allPotentialPeers).take(uniquePeersN)
      val replicated = Seq.fill(10)(unique).flatten
      val addNodesParallel = for {
        peerTable <- PeerTable[Task](id, bucketSize)
        _         <- Task.gatherUnordered(replicated.map(peerTable.updateLastSeen(_)))
        bucket    <- peerTable.tableRef.get.map(_(distance).map(_.node))
      } yield bucket

      addNodesParallel.runSyncUnsafe(5.seconds) should contain theSameElementsAs unique
      pingCounter shouldBe 0
    }
  }

  property("""
             |updateLastSeen doesn't block read operations;
             |'initial' peers added without 'ping' requests,
             |after that, when the bucket is full, each addition cause a hanging ping request,
           """.stripMargin) {
    forAll { _: Int =>
      val never                    = CancelablePromise[Boolean]()
      implicit val K: KademliaMock = (_: Node) => Task.fromCancelablePromise(never)

      val (initial, rest) = Random.shuffle(allPotentialPeers).splitAt(bucketSize)
      val peerTable       = PeerTable[Task](id, bucketSize).runSyncUnsafe()
      val fillBucket      = Task.sequence(initial.map(peerTable.updateLastSeen(_)))
      val hangUp          = Task.gatherUnordered(rest.map(peerTable.updateLastSeen(_)))

      fillBucket.runSyncUnsafe()
      hangUp.runAsyncAndForget

      val peers = peerTable.peersAscendingDistance
        .runSyncUnsafe(5.seconds)

      peers should contain theSameElementsAs initial
    }
  }

  property("""
             |updateLastSeen atomically pings peers;
             |after bucket fulfilling, each addition should produce a single ping request
           """.stripMargin) {
    forAll { _: Int =>
      val pingsCounter = new AtomicLong(0)
      implicit val K: KademliaMock = (_: Node) => {
        pingsCounter.incrementAndGet()
        Task.now(true)
      }

      val (initial, rest) = Random.shuffle(allPotentialPeers).splitAt(bucketSize)
      val restReplicated  = Random.shuffle(Seq.fill(10)(rest).flatten)

      val addNodesParallel = for {
        peerTable <- PeerTable[Task](id, bucketSize)
        _         <- Task.gatherUnordered(initial.map(peerTable.updateLastSeen(_)))
        _         <- Task.gatherUnordered(restReplicated.map(peerTable.updateLastSeen(_)))
        peers     <- peerTable.peersAscendingDistance
      } yield peers

      addNodesParallel.runSyncUnsafe(15.seconds) should contain theSameElementsAs initial
      pingsCounter.get() shouldBe restReplicated.size
    }
  }
}
