package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node

trait Synchronizer[F[_]] {

  /** Synchronize the DAG betwen this node and the source so that we know
    * how to get from where we are to the blocks we were told about.
    * Return the missing part of the DAG in topoligical order. */
  def syncDag(source: Node, targetBlockHashes: Set[ByteString]): F[Vector[BlockSummary]]

  // TODO: Perhaps this should actually return an Iterant, so that we can synchronize
  // arbitrarily large missing pieces, potentially back to the Genesis.
}

// TODO: NODE-305: Implement this properly.
class SynchronizerImpl[F[_]](
    connectToGossip: Node => F[GossipService[F]]
) extends Synchronizer[F] {
  def syncDag(source: Node, targetBlockHashes: Set[ByteString]): F[Vector[BlockSummary]] =
    ???
}
