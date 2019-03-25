package io.casperlabs.comm.gossiping

import cats._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node

/** Manage the download, validation, storing and gossiping of blocks. */
trait DownloadManager[F[_]] {

  /** Schedule the download of a full block from the `source` node.
    * If `relay` is `true` then gossip it afterwards, if it's valid.
    * The returned `F[F[Unit]]` represents the success/failure of
    * the scheduling itself; it fails if there's any error accessing
    * the local backend, or if the scheduling cannot be carried out
    * due to missing dependencies (at this point we should have synced
    * already and the schedule should be called in topological order).
    *
    * The unwrapped `F[Unit]` _inside_ the `F[F[Unit]]` can be used to
    * wait until the actual download finishes, or results in an error. */
  def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean): F[F[Unit]]
}
