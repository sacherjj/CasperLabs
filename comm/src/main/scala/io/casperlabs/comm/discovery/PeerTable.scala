package io.casperlabs.comm.discovery

import cats.data._
import cats.effect.Sync
import cats.implicits._
import io.casperlabs.comm.{NodeIdentifier, PeerNode}
import io.casperlabs.comm.discovery.PeerTable.Entry

import scala.annotation.tailrec
import scala.collection.mutable

object PeerTable {
  final case class Entry(node: PeerNode) {
    var pinging           = false
    override def toString = s"#{PeerTableEntry $node}"
  }

  def apply(
      local: NodeIdentifier,
      k: Int = PeerTable.Redundancy,
      alpha: Int = PeerTable.Alpha
  ): PeerTable =
    new PeerTable(local, k, alpha)

  // Number of bits considered in the distance function. Taken from the
  // passed-in "home" value to the table.
  //
  // val Width = 256

  // Maximum length of each row of the routing table.
  val Redundancy = 20

  // Concurrency factor: system allows up to alpha outstanding network
  // requests at a time.
  val Alpha = 3

  // UNIMPLEMENTED: this parameter controls an optimization that can
  // reduce the number hops required to find an address in the network
  // by grouping keys in buckets of a size larger than one.
  //
  // val BucketWidth = 1

  // Lookup table for log 2 (highest bit set) of an 8-bit unsigned
  // integer. Entry 0 is unused.
  private val dlut = (Seq(0, 7, 6, 6) ++
    Seq.fill(4)(5) ++
    Seq.fill(8)(4) ++
    Seq.fill(16)(3) ++
    Seq.fill(32)(2) ++
    Seq.fill(64)(1) ++
    Seq.fill(128)(0)).toArray
}

/** `PeerTable` implements the routing table used in the Kademlia
  * network discovery and routing protocol.
  *
  */
final class PeerTable(
    local: NodeIdentifier,
    private[discovery] val k: Int,
    alpha: Int
) {

  private[discovery] val width = local.key.size // in bytes
  private[discovery] val table = Array.fill(8 * width) {
    new mutable.ListBuffer[Entry]
  }

  /** Computes Kademlia XOR distance.
    *
    * Returns the length of the longest common prefix in bits between
    * the two sequences `a` and `b`. As in Ethereum's implementation,
    * "closer" nodes have higher distance values.
    *
    * @return `Some(Int)` if `a` and `b` are comparable in this table,
    * `None` otherwise.
    */
  private[discovery] def distance(a: NodeIdentifier, b: NodeIdentifier): Option[Int] = {
    @tailrec
    def highBit(idx: Int): Int =
      if (idx == width) 8 * width
      else
        a.key(idx) ^ b.key(idx) match {
          case 0 => highBit(idx + 1)
          case n => 8 * idx + PeerTable.dlut(n & 0xff)
        }

    if (a.key.size != width || b.key.size != width) None
    else Some(highBit(0))
  }

  private[discovery] def distance(other: PeerNode): Option[Int]       = distance(local, other.id)
  private[discovery] def distance(other: NodeIdentifier): Option[Int] = distance(local, other)

  def updateLastSeen[F[_]: Sync: KademliaRPC](peer: PeerNode): F[Unit] = {

    def bucket: F[Option[mutable.ListBuffer[Entry]]] =
      Sync[F].delay(distance(local, peer.id).filter(_ < 8 * width).map(table.apply))

    def addUpdateOrPickOldPeer(ps: mutable.ListBuffer[Entry]): F[Option[Entry]] =
      Sync[F].delay {
        ps synchronized {
          ps.find(_.node.key == peer.key) match {
            case Some(entry) =>
              ps -= entry
              ps += Entry(peer)
              None
            case None =>
              if (ps.size < k) {
                ps += Entry(peer)
                None
              } else {
                // ping first (oldest) element that isn't already being
                // pinged. If it responds, move it to back (newest
                // position); if it doesn't respond, remove it and place
                // a in back instead
                ps.find(!_.pinging).map { candidate =>
                  candidate.pinging = true
                  candidate
                }
              }
          }
        }
      }

    def pingAndUpdate(ps: mutable.ListBuffer[Entry], older: Entry): F[Unit] =
      KademliaRPC[F].ping(older.node).map { response =>
        val winner = if (response) older else Entry(peer)
        ps synchronized {
          ps -= older
          ps += winner
          winner.pinging = false
        }
      }

    def upsert: OptionT[F, Unit] =
      for {
        ps    <- OptionT(bucket)
        older <- OptionT(addUpdateOrPickOldPeer(ps))
        _     <- OptionT.liftF(pingAndUpdate(ps, older))
      } yield ()

    upsert.value.void
  }

  /**
    * Remove a peer with the given key.
    */
  def remove(toRemove: NodeIdentifier): Unit =
    distance(local, toRemove) match {
      case Some(index) =>
        if (index < 8 * width) {
          val ps = table(index)
          ps synchronized {
            ps.find(_.node.key == toRemove.key) match {
              case Some(entry) =>
                ps -= entry
                ()
              case _ => ()
            }
          }
        }
      case None => ()
    }

  /**
    * Return the `k` nodes closest to `key` that this table knows
    * about, sorted ascending by distance to `key`.
    */
  def lookup(toLookup: NodeIdentifier): Seq[PeerNode] = {

    def sorter(a: PeerNode, b: PeerNode) =
      (distance(toLookup, a.id), distance(toLookup, b.id)) match {
        case (Some(d0), Some(d1)) => d0 > d1
        case _                    => false
      }

    distance(local, toLookup) match {
      case Some(index) =>
        val entries = new mutable.ListBuffer[Entry]

        for (i <- index until 8 * width; if entries.size < k) {
          table(i) synchronized {
            entries ++= table(i).filter(_.node.id != toLookup)
          }
        }

        for (i <- index - 1 to 0 by -1; if entries.size < k) {
          table(i) synchronized {
            entries ++= table(i)
          }
        }

        entries.map(_.node).sortWith(sorter).take(k).toVector
      case None => Vector.empty
    }
  }

  /**
    * Return `Some[A]` if `key` names an entry in the table.
    */
  def find(toFind: NodeIdentifier): Option[PeerNode] =
    for {
      d <- distance(toFind)
      e <- table(d) synchronized { table(d).find(_.node.id == toFind) }
    } yield e.node

  /**
    * Return a sequence of all the `A`s in the table.
    */
  def peers: Seq[PeerNode] =
    table.flatMap(l => l synchronized { l.map(_.node) })

  /**
    * Return all distances in order from least to most filled.
    *
    * Optionally, ignore any distance closer than [[limit]].
    */
  def sparseness(limit: Int = 255): Seq[Int] =
    table
      .take(limit + 1)
      .zipWithIndex
      .map { case (l, i) => (l.size, i) }
      .sortWith(_._1 < _._1)
      .map(_._2)
}
