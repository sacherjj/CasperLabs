package io.casperlabs.comm.gossiping

import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node

/** Manage the download, validation, storing and gossiping of blocks. */
trait DownloadManager[F[_]] {

  /** Schedule the download of a full block from the `source` node.
    * If `relay` is `true` then gossip it afterwards, if it's valid.
    * The returned `F[Unit]` finishes as soon as the scheduling has
    * happened successfully, *not* when the download has completed.
    * It is illegal to schedule an item which has dependencies that
    * hasn't been scheduled or downloaded already; first we should
    * sync the DAG with the `source` and add items in topological
    * order*/
  def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean): F[Unit]

  // TODO: We could consider adding some "watch" functionality so that the caller
  // can await for when the download is completed. Maybe `scheduleDownload` should
  // return an `F[Deferred[F, Unit]]`. The outer `F` would indicate the success of
  // the scheduling and the `Deferred` would eventually complete with success or
  // error. It would alleviate the need for some sleeps in the tests to see that
  // nothing else happens, if we knew that the download has indeed finished.
}
