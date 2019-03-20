package io.casperlabs.comm.gossiping

import org.scalatest._

class DownloadManagerSpec extends WordSpecLike {

  "DownloadManager" when {
    "scheduled to download a section of the DAG" should {
      "download blocks in topoligical order" in (pending)
      "download siblings in parallel" in (pending)
      "eventually download the full DAG" in (pending)
      "not exceed the limit on parallelism" in (pending)
    }
    "scheduled to download a block with missing dependencies" should {
      "raise an error" in (pending)
    }
    "scheduled to download a block which already exists" should {
      "skip the download" in (pending)
    }
    "scheduled to download a block which is already downloading" should {
      "skip the download" in (pending)
    }
    "released as a resource" should {
      "cancel outstanding downloads" in (pending)
      "reject further schedules" in (pending)
    }
    "fails to download a block for any reason" should {
      "carry on downloading other blocks from other nodes" in (pending)
      "try to download the block from a different source" in (pending)
    }
    "downloaded a valid block" should {
      "validate the block" in (pending)
      "store the block" in (pending)
      "store the block summary" in (pending)
      "gossip to other nodes" in (pending)
    }
    "cannot validate a block" should {
      "not download the dependant blocks" in (pending)
      "not store the block" in (pending)
    }
    "cannot connect to a node" should {
      "try again if the same block from the same source is scheduled again" in (pending)
      "try again later with exponential backoff" in (pending)
    }
    "receiving chunks" should {
      "check that they start with the header" in (pending)
      "check that the total size doesn't exceed promises" in (pending)
      "check that the excpected compression algorithm is used" in (pending)
    }
  }
}
