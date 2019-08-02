package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.Synchronizer.SyncError
import io.casperlabs.comm.gossiping.Utils.hex
import scala.util.control.NoStackTrace

trait Synchronizer[F[_]] {

  /** Synchronize the DAG between this node and the source so that we know
    * how to get from where we are to the blocks we were told about.
    * Return the missing part of the DAG in topological order. */
  def syncDag(
      source: Node,
      targetBlockHashes: Set[ByteString]
  ): F[Either[SyncError, Vector[BlockSummary]]]

  /** Called when the block is finally downloaded to release any caches
    * the synchronizer keeps around. */
  def downloaded(
      blockHash: ByteString
  ): F[Unit]
}

object Synchronizer {
  //TODO: Reconsider according to the consensus
  // We can't just stop importing at a certain depth threshold,
  // because then an attacker could probably create blocks in such a way that
  // half the honest nodes would import them and half of them would reject them?
  // Logic may be changed depending on the consensus algorithm.
  sealed trait SyncError extends NoStackTrace {
    override def getMessage(): String =
      SyncError.getMessage(this)
  }
  object SyncError {
    final case class TooWide(
        maxBranchingFactor: Double Refined GreaterEqual[W.`1.0`.T],
        maxTotal: Int,
        total: Int
    ) extends SyncError
    final case class Unreachable(summary: BlockSummary, requestedDepth: Int Refined Positive)
        extends SyncError
    final case class TooMany(hash: ByteString, limit: Int Refined Positive)        extends SyncError
    final case class TooDeep(hashes: Set[ByteString], limit: Int Refined Positive) extends SyncError
    final case class ValidationError(summary: BlockSummary, reason: Throwable)     extends SyncError
    final case class MissingDependencies(missing: Set[ByteString])                 extends SyncError
    final case class Cycle(summary: BlockSummary)                                  extends SyncError

    def getMessage(error: SyncError): String =
      error match {
        case SyncError.TooMany(summary, limit) =>
          s"Returned DAG is too big, limit: $limit, exceeded hash: ${hex(summary)}"
        case SyncError.TooDeep(summaries, limit) =>
          s"Returned DAG is too deep, limit: $limit, exceeded hashes: ${summaries.map(hex)}"
        case SyncError.TooWide(maxBranchingFactor, maxTotal, total) =>
          s"Returned dag seems to be exponentially wide, max branching factor: $maxBranchingFactor, max total summaries: $maxTotal, total returned: $total"
        case SyncError.Unreachable(summary, requestedDepth) =>
          s"During streaming source returned unreachable block summary: ${hex(summary)}, requested depth: $requestedDepth"
        case SyncError.ValidationError(summary, e) =>
          s"Failed to validated the block summary: ${hex(summary)}, reason: $e"
        case SyncError.MissingDependencies(hashes) =>
          s"Missing dependencies: ${hashes.map(hex)}"
        case SyncError.Cycle(summary) =>
          s"Detected cycle: ${hex(summary)}"
      }
  }
}
