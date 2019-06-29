package io.casperlabs.comm.discovery

import scala.concurrent.duration._
import scala.util.Random
import cats.implicits._
import io.casperlabs.catscontrib.MonadThrowable
import org.scalatest._
import io.casperlabs.comm.discovery.NodeUtils._

abstract class KademliaServiceSpec[F[_]: MonadThrowable: cats.effect.Timer, E <: Environment]
    extends KademliaServiceRuntime[F, E]
    with WordSpecLike
    with Matchers {

  val kademliaServiceName: String = this.getClass.getSimpleName.replace("Spec", "")

  kademliaServiceName when {
    "pinging a remote peer" when {
      "everything is fine" should {
        "send and receive a positive response" in
          new TwoNodesRuntime[Boolean]() {
            def execute(
                kademlia: KademliaService[F],
                local: Node,
                remote: Node
            ): F[Boolean] = kademlia.ping(remote)

            val result: TwoNodesResult = run()

            result() shouldEqual true
            pingHandler.received should have length 1
            val (receiver, sender) = pingHandler.received.head
            receiver shouldEqual result.remoteNode
            sender shouldEqual result.localNode
          }

        "send twice and receive positive responses" in
          new TwoNodesRuntime[(Boolean, Boolean)]() {
            def execute(
                kademlia: KademliaService[F],
                local: Node,
                remote: Node
            ): F[(Boolean, Boolean)] =
              for {
                r1 <- kademlia.ping(remote)
                r2 <- kademlia.ping(remote)
              } yield (r1, r2)

            val result: TwoNodesResult = run()

            result() shouldEqual ((true, true))
            pingHandler.received should have length 2
            val (receiver1, sender1) = pingHandler.received.head
            val (receiver2, sender2) = pingHandler.received.tail.head
            receiver1 shouldEqual result.remoteNode
            receiver2 shouldEqual result.remoteNode
            sender1 shouldEqual result.localNode
            sender2 shouldEqual result.localNode
          }
      }

      "response takes to long" should {
        "get a negative result" in
          new TwoNodesRuntime[Boolean](
            pingHandler = Handler.pingHandlerWithDelay(1.second),
            timeout = 500.millis
          ) {
            def execute(
                kademlia: KademliaService[F],
                local: Node,
                remote: Node
            ): F[Boolean] = kademlia.ping(remote)

            val result: TwoNodesResult = run()

            result() shouldEqual false
            pingHandler.received should have length 1
            val (receiver, sender) = pingHandler.received.head
            receiver shouldEqual result.remoteNode
            sender shouldEqual result.localNode
          }
      }

      "peer is not listening" should {
        "get a negative result" in
          new TwoNodesRemoteDeadRuntime[Boolean]() {
            def execute(
                kademlia: KademliaService[F],
                local: Node,
                remote: Node
            ): F[Boolean] = kademlia.ping(remote)

            val result: TwoNodesResult = run()

            result() shouldEqual false
          }
      }
    }

    "doing a lookup to a remote peer" when {
      val id = {
        val key = Array.ofDim[Byte](40)
        Random.nextBytes(key)
        NodeIdentifier(key)
      }
      val otherPeer = Node(id, "1.2.3.4", 0, 0)

      "everything is fine" should {
        "send and receive a list of peers" in
          new TwoNodesRuntime[Option[Seq[Node]]](
            lookupHandler = Handler.lookupHandler(Seq(otherPeer))
          ) {
            def execute(
                kademlia: KademliaService[F],
                local: Node,
                remote: Node
            ): F[Option[Seq[Node]]] = kademlia.lookup(id, remote)

            val result: TwoNodesResult = run()

            result() shouldEqual Some(Seq(otherPeer))
            lookupHandler.received should have length 1
            val (receiver, (sender, k)) = lookupHandler.received.head
            receiver shouldEqual result.remoteNode
            sender shouldEqual result.localNode
            k should be(id)
          }
      }

      "response takes to long" should {
        "get an None" in
          new TwoNodesRuntime[Option[Seq[Node]]](
            lookupHandler = Handler.lookupHandlerWithDelay(1.second),
            timeout = 500.millis
          ) {
            def execute(
                kademlia: KademliaService[F],
                local: Node,
                remote: Node
            ): F[Option[Seq[Node]]] = kademlia.lookup(id, remote)

            val result: TwoNodesResult = run()

            result() shouldEqual None
            lookupHandler.received should have length 1
            val (receiver, (sender, k)) = lookupHandler.received.head
            receiver shouldEqual result.remoteNode
            sender shouldEqual result.localNode
            k should be(id)
          }
      }

      "peer is not listening" should {
        "get an None" in
          new TwoNodesRemoteDeadRuntime[Option[Seq[Node]]]() {
            def execute(
                kademlia: KademliaService[F],
                local: Node,
                remote: Node
            ): F[Option[Seq[Node]]] = kademlia.lookup(id, remote)

            val result: TwoNodesResult = run()

            result() shouldEqual None
          }
      }
    }
  }
}
