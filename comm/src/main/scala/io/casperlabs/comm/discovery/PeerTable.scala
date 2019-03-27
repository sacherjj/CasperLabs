package io.casperlabs.comm.discovery

import cats.Monad
import cats.effect.Sync
import cats.effect.concurrent.Ref
import cats.syntax.all._
import io.casperlabs.comm.discovery.PeerTable.Entry
import io.casperlabs.comm.{NodeIdentifier, PeerNode}

import scala.annotation.tailrec
import scala.util.Sorting

object PeerTable {
  final case class Entry(node: PeerNode, pinging: Boolean = false) {
    override def toString = s"#{PeerTableEntry $node}"
  }

  def apply[F[_]: Sync](
      local: NodeIdentifier,
      k: Int = PeerTable.Redundancy
  ): F[PeerTable[F]] =
    for {
      //160 buckets with at most 20 elements in each of them
      buckets <- Ref.of(Vector.fill(8 * local.key.size)(List.empty[Entry]))
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

  /** Computes Kademlia XOR distance.
    *
    * Returns the length of the longest common prefix in bits between
    * the two sequences `a` and `b`. As in Ethereum's implementation,
    * "closer" nodes have higher distance values.
    *
    * @return `Some(Int)` if `a` and `b` are comparable in this table,
    * `None` otherwise.
    */
  private[discovery] def distance(a: NodeIdentifier, b: NodeIdentifier): Int = {
    @tailrec
    def highBit(idx: Int): Int =
      if (idx == a.key.size) 8 * a.key.size
      else
        a.key(idx) ^ b.key(idx) match {
          case 0 => highBit(idx + 1)
          case n => 8 * idx + PeerTable.dlut(n & 0xff)
        }
    highBit(0)
  }
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
  private[discovery] val width = local.key.size // in bytes

  private[discovery] def distance(other: PeerNode): Int       = PeerTable.distance(local, other.id)
  private[discovery] def distance(other: NodeIdentifier): Int = PeerTable.distance(local, other)

  def updateLastSeen(peer: PeerNode)(implicit K: KademliaRPC[F]): F[Unit] = {
    val index = distance(peer)
    for {
      maybeCandidate <- tableRef.modify { table =>
                         val bucket = table(index)
                         val (updatedBucket, maybeCandidate) =
                           bucket.find(_.node.key == peer.key) match {
                             case Some(previous) =>
                               (
                                 Entry(peer) :: bucket.filter(_.node.key != previous.node.key),
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
                                   p => if (p.node.key == candidate.node.key) candidate else p
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
                    .filter(_.node.key != candidate.node.key)
                  table.updated(index, updated)
                }
              }
          )
    } yield ()
  }

  def lookup(toLookup: NodeIdentifier): F[Seq[PeerNode]] =
    tableRef.get.map { table =>
      val flattenedArray = table.flatten.filterNot(_.node.key == toLookup.key).toArray
      Sorting.quickSort(flattenedArray)(
        (x: Entry, y: Entry) =>
          (PeerTable.distance(toLookup, x.node.id), PeerTable.distance(toLookup, y.node.id)) match {
            case (d0, d1) => Ordering[Int].compare(d0, d1)
          }
      )
      flattenedArray.take(k).toList.map(_.node)
    }

  def find(toFind: NodeIdentifier): F[Option[PeerNode]] =
    tableRef.get.map(_(distance(toFind)).find(_.node.id == toFind).map(_.node))

  def peers: F[Seq[PeerNode]] = tableRef.get.map(_.flatMap(_.map(_.node)))

  def sparseness: F[Seq[Int]] =
    tableRef.get.map(
      _.zipWithIndex
        .sortWith {
          case ((bucketA, _), (bucketB, _)) => bucketA.size < bucketB.size
        }
        .map(_._2)
    )
}
