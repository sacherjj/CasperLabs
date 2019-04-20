package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node

trait Synchronizer[F[_]] {

  /** Synchronize the DAG between this node and the source so that we know
    * how to get from where we are to the blocks we were told about.
    * Return the missing part of the DAG in topological order. */
  def syncDag(source: Node, targetBlockHashes: Set[ByteString]): F[Vector[BlockSummary]]
}

object Synchronizer {
  trait Backend[F[_]] {
    def tips: F[List[ByteString]]
    def justifications: F[List[ByteString]]
    def validate(blockSummary: BlockSummary): F[Unit]
    def notInDag(blockHash: ByteString): F[Boolean]
  }
}
