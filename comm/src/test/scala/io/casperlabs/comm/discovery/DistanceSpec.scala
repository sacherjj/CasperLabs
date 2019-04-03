package io.casperlabs.comm.discovery

import cats._
import cats.syntax.either._
import io.casperlabs.comm._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.catscontrib.effect.implicits._
import org.scalatest._

object b {
  private val rand           = new scala.util.Random(System.currentTimeMillis)
  def apply(i: Int): Byte    = i.toByte
  def apply(s: String): Byte = b(Integer.parseInt(s, 2))
  def rand(nbytes: Int): Seq[Byte] = {
    val arr = Array.fill(nbytes)(b(0))
    rand.nextBytes(arr)
    arr
  }
}

class DistanceSpec extends FlatSpec with Matchers {

  val endpoint = Endpoint("", 0, 0)
  implicit val ping: KademliaService[Id] = new KademliaService[Id] {
    def ping(node: PeerNode): Boolean                                     = true
    def lookup(id: NodeIdentifier, peer: PeerNode): Option[Seq[PeerNode]] = None
    def receive(
        pingHandler: PeerNode => Id[Unit],
        lookupHandler: (PeerNode, NodeIdentifier) => Id[Seq[PeerNode]]
    ): Id[Unit]              = ()
    def shutdown(): Id[Unit] = ()
  }

  "A PeerNode of width n bytes" should "have distance to itself equal to 8n" in {
    for (i <- 1 to 64) {
      val home = PeerNode(NodeIdentifier(b.rand(i)), endpoint)
      val nt   = PeerTable(home.id)
      nt.longestCommonBitPrefix(home) should be(8 * nt.width)
    }
  }

  for (exp <- 1 to 8) {

    val width = 1 << exp

    // Make 8*width copies all of which differ in a single, distinct bit
    def oneOffs(id: NodeIdentifier): Seq[NodeIdentifier] =
      for {
        i <- 0 until width
        j <- 7 to 0 by -1
      } yield {
        val k1 = Array.fill(id.key.size)(b(0))
        Array.copy(k1, 0, id.key.toArray, 0, id.key.size)
        k1(i) = b(k1(i) ^ b(1 << j))
        NodeIdentifier(k1)
      }

    def testKey(key: Seq[Byte]): Boolean = {
      val id    = NodeIdentifier(key)
      val table = PeerTable(id)
      oneOffs(id).map(table.longestCommonBitPrefix) == (0 until 8 * width)
    }

    def keyString(key: Seq[Byte]): String =
      Base16.encode(key.toArray)

    val k0 = Array.fill(width)(b(0))
    s"A node with key all zeroes (${keyString(k0)})" should "compute distance correctly" in {
      testKey(k0) should be(true)
    }

    val k1 = Array.fill(width)(b(0xff))
    s"A node with key all ones (${keyString(k1)})" should "compute distance correctly" in {
      testKey(k1) should be(true)
    }

    val kr = NodeIdentifier(b.rand(width))
    s"A node with random key (${kr.toString})" should "compute distance correctly" in {
      testKey(kr.key.toArray) should be(true)
    }

    s"An empty table of width $width" should "have no peers" in {
      val table = PeerTable(kr)
      assert(table.tableRef.get.forall(_.isEmpty))
    }

    it should "return no peers" in {
      val table = PeerTable(kr)
      table.peersAscendingDistance.size should be(0)
    }

    it should "return no values on lookup" in {
      val table = PeerTable(kr)
      table.lookup(NodeIdentifier(b.rand(width))).size should be(0)
    }

    s"A table of width $width" should "add a key at most once" in {
      val table = PeerTable(kr)
      val toAdd = oneOffs(kr).head
      val dist  = table.longestCommonBitPrefix(toAdd)
      for (_ <- 1 to 10) {
        table.updateLastSeen(PeerNode(toAdd, endpoint))
        table.tableRef.get(dist).size should be(1)
      }
    }

    s"A table of width $width with peers at all distances" should "have no empty buckets" in {
      val table = PeerTable(kr)
      for (k <- oneOffs(kr)) {
        table.updateLastSeen(PeerNode(k, endpoint))
      }
      assert(table.tableRef.get.forall(_.nonEmpty))
    }

    it should s"return min(k, ${8 * width}) peers on lookup" in {
      val table = PeerTable(kr)
      for (k <- oneOffs(kr)) {
        table.updateLastSeen(PeerNode(k, endpoint))
      }
      table.lookup(NodeIdentifier(b.rand(width))).size should be(scala.math.min(table.k, 8 * width))
    }

    it should s"return ${8 * width} peers when sequenced" in {
      val table = PeerTable(kr)
      for (k <- oneOffs(kr)) {
        table.updateLastSeen(PeerNode(k, endpoint))
      }
      table.peersAscendingDistance.size should be(8 * width)
    }

    it should "find each added peer" in {
      val table = PeerTable(kr)
      for (k <- oneOffs(kr)) {
        table.updateLastSeen(PeerNode(k, endpoint))
      }
      for (k <- oneOffs(kr)) {
        table.find(k) should be(Some(PeerNode(k, endpoint)))
      }
    }
  }
}
