package io.casperlabs.comm.gossiping.synchronization

import cats.Parallel
import cats.effect._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.discovery.{Node, NodeDiscovery}
import io.casperlabs.comm.gossiping.Utils.hex
import io.casperlabs.comm.gossiping.synchronization.InitialSynchronization.SynchronizationError
import io.casperlabs.comm.gossiping._
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.shared.Log
import io.casperlabs.shared.IterantOps.RichIterant

import scala.concurrent.duration.FiniteDuration

trait InitialSynchronization[F[_]] {

  /**
    * Synchronizes the node with the last tips.
    *
    * @return Handle which will be resolved when node is considered to be fully synced
    */
  def sync(): F[WaitHandle[F]]
}

object InitialSynchronization {
  final case class SynchronizationError() extends Exception
}
