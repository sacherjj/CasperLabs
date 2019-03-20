package io.casperlabs.comm.gossiping

import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node

/** Manage the download, validation, storing and gossiping of blocks. */
trait DownloadManager[F[_]] {

  /** Schedule the download of a full block from the `source` node.
    * If `relay` is `true` then gossip it afterwards, if it's valid.
    * It is illegal to schedule an item which has dependencies that
    * hasn't been scheduled or downloaded already; first we should
    * sync the DAG with the `source` and add items in topological
    * order. */
  def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean): F[Unit]
}
