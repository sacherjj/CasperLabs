package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.Synchronizer.SyncError

trait Synchronizer[F[_]] {

  /** Synchronize the DAG between this node and the source so that we know
    * how to get from where we are to the blocks we were told about.
    * Return the missing part of the DAG in topological order. */
  def syncDag(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[Either[SyncError, Vector[BlockSummary]]]
}

object Synchronizer {
  //TODO: Reconsider according to the consensus
  // We can't just stop importing at a certain depth threshold,
  // because then an attacker could probably create blocks in such a way that
  // half the honest nodes would import them and half of them would reject them?
  // Logic may be changed depending on the consensus algorithm.
  sealed trait SyncError extends Product with Serializable
  object SyncError {
    final case class TooWide(
        maxBranchingFactor: Double Refined GreaterEqual[W.`1.0`.T],
        maxTotal: Int,
        total: Int
    ) extends SyncError
    final case class Unreachable(summary: BlockSummary, requestedDepth: Int Refined Positive)
        extends SyncError
    final case class TooDeep(hashes: Set[ByteString], limit: Int Refined Positive) extends SyncError
    final case class ValidationError(summary: BlockSummary, reason: Throwable)     extends SyncError
    final case class MissingDependencies(missing: Set[ByteString])                 extends SyncError
    final case class Cycle(summary: BlockSummary)                                  extends SyncError
  }
}
