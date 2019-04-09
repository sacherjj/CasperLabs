package io.casperlabs.comm.discovery

import cats.Monad
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.implicits._
import cats.kernel.Eq
import com.google.protobuf.ByteString
import io.casperlabs.comm.discovery.PeerTable.Entry

import scala.annotation.tailrec
import scala.util.Sorting

object PeerTable {
  final case class Entry(node: Node, pinging: Boolean = false) {
    override def toString = s"#{PeerTableEntry $node}"
  }

  def apply[F[_]: Sync](
      local: NodeIdentifier,
      k: Int = PeerTable.Redundancy
  ): F[PeerTable[F]] =
    for {
      //160 buckets with at most 20 elements in each of them
      buckets <- Ref.of(Vector.fill(8 * local.key.length)(List.empty[Entry]))
    } yield new PeerTable(local, k, buckets)

  // Maximum length of each row of the routing table.
  val Redundancy = 20

  // Lookup table for log 2 (highest bit set) of an 8-bit unsigned
  // integer. Entry 0 is unused.
  private val dlut = (Seq(0, 7, 6, 6) ++
    Seq.fill(4)(5) ++
    Seq.fill(8)(4) ++
    Seq.fill(16)(3) ++
    Seq.fill(32)(2) ++
    Seq.fill(64)(1) ++
    Seq.fill(128)(0)).toArray

  /**
    * Returns the length of the longest common prefix in bits between
    * the two sequences `a` and `b`. As in Ethereum's implementation,
    * "closer" nodes have higher distance values.
    *
    * @return `Some(Int)` if `a` and `b` are comparable in this table,
    * `None` otherwise.
    */
  private[discovery] def longestCommonBitPrefix(a: NodeIdentifier, b: NodeIdentifier): Int = {
    @tailrec
    def highBit(idx: Int): Int =
      if (idx === a.key.length) 8 * a.key.length
      else
        a.key(idx) ^ b.key(idx) match {
          case 0 => highBit(idx + 1)
          case n => 8 * idx + PeerTable.dlut(n & 0xff)
        }
    highBit(0)
  }

  private[discovery] def xorDistance(a: NodeIdentifier, b: NodeIdentifier): BigInt =
    BigInt(a.key.zip(b.key).map { case (l, r) => (l ^ r).toByte }.toArray).abs

  private[discovery] def xorDistance(a: ByteString, b: ByteString): BigInt =
    xorDistance(NodeIdentifier(a), NodeIdentifier(b))
}

/** `PeerTable` implements the routing table used in the Kademlia
  * network discovery and routing protocol.
  *
  */
final class PeerTable[F[_]: Monad](
    local: NodeIdentifier,
    private[discovery] val k: Int,
    private[discovery] val tableRef: Ref[F, Vector[List[Entry]]]
) {
  private implicit def arrayEq[A]: Eq[Array[A]] = Eq.instance[Array[A]]((a, b) => a.sameElements(b))
  private implicit val bytestringEq: Eq[ByteString] =
    Eq.instance[ByteString]((a, b) => a.toByteArray === b.toByteArray)

  private[discovery] val width = local.key.length // in bytes

  private[discovery] def longestCommonBitPrefix(other: Node): Int =
    PeerTable.longestCommonBitPrefix(local, NodeIdentifier(other.id))
  private[discovery] def longestCommonBitPrefix(other: NodeIdentifier): Int =
    PeerTable.longestCommonBitPrefix(local, other)

  def updateLastSeen(peer: Node)(implicit K: KademliaService[F]): F[Unit] = {
    val index = longestCommonBitPrefix(peer)
    for {
      maybeCandidate <- tableRef.modify { table =>
                         val bucket = table(index)
                         val (updatedBucket, maybeCandidate) =
                           bucket.find(_.node.id === peer.id) match {
                             case Some(previous) =>
                               (
                                 Entry(peer) :: bucket.filter(_.node.id != previous.node.id),
                                 none[Entry]
                               )
                             case None if bucket.size < k =>
                               (Entry(peer) :: bucket, none[Entry])
                             // Ping oldest element that isn't already being pinged.
                             // If it responds, move it to newest position;
                             // If it doesn't, remove it and place new in newest
                             case _ =>
                               val maybeCandidate =
                                 bucket.reverse.find(!_.pinging).map(_.copy(pinging = true))
                               val updatedBucket = maybeCandidate.fold(bucket) { candidate =>
                                 bucket.map(
                                   p => if (p.node.id === candidate.node.id) candidate else p
                                 )
                               }
                               (updatedBucket, maybeCandidate)
                           }
                         (table.updated(index, updatedBucket), maybeCandidate)
                       }
      _ <- maybeCandidate.fold(().pure[F])(
            candidate =>
              K.ping(candidate.node).flatMap { responded =>
                tableRef.update { table =>
                  val bucket = table(index)
                  val winner = if (responded) candidate else Entry(peer)
                  val updated = winner.copy(pinging = false) :: bucket
                    .filter(_.node.id != candidate.node.id)
                  table.updated(index, updated)
                }
              }
          )
    } yield ()
  }

  def lookup(toLookup: NodeIdentifier): F[Seq[Node]] =
    tableRef.get.map { table =>
      sort(table.flatten.toList, toLookup).take(k).map(_.node)
    }

  def find(toFind: NodeIdentifier): F[Option[Node]] =
    tableRef.get.map(
      _(longestCommonBitPrefix(toFind))
        .find(_.node.id.toByteArray === toFind.key.toArray)
        .map(_.node)
    )

  def peersAscendingDistance: F[List[Node]] = tableRef.get.map { table =>
    sort(table.flatten.toList, local).map(_.node).toList
  }

  def sparseness: F[Seq[Int]] =
    tableRef.get.map(
      _.zipWithIndex
        .sortWith {
          case ((bucketA, _), (bucketB, _)) => bucketA.size < bucketB.size
        }
        .map(_._2)
    )

  private def sort(entries: Seq[Entry], target: NodeIdentifier): Seq[Entry] =
    entries.sorted(
      (x: Entry, y: Entry) =>
        Ordering[BigInt].compare(
          PeerTable.xorDistance(target.asByteString, x.node.id),
          PeerTable.xorDistance(target.asByteString, y.node.id)
        )
    )
}
