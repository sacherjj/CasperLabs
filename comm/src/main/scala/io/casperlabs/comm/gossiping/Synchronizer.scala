package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node

trait Synchronizer[F[_]] {

  /** Synchronize the DAG betwen this and the source node so that we know
    * how to get from where we are to the blocks were were told about.
    * Return the missing part of the DAG in topoligical order. */
  def syncDag(source: Node, targetBlockHashes: Set[ByteString]): F[Vector[BlockSummary]]
}

// TODO: NODE-305: Implement this properly.
class SynchronizerImpl[F[_]](
    connectToGossip: Node => F[GossipService[F]]
) {
  def syncDag(source: Node, targetBlockHashes: Set[ByteString]): F[Vector[BlockSummary]] =
    ???
}
