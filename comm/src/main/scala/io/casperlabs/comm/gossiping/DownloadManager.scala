package io.casperlabs.comm.gossiping

import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node

/** Manage the download, validation, storing and gossiping of blocks. */
trait DownloadManager[F[_]] {

  /** Schedule the download of a full block from the `source` node.
	  * If `relay` is `true` then gossip it afterwards, if it's valid. */
  def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean): F[Unit]
}
