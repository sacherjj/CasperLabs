package io.casperlabsnode.casper

import cats.effect._
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.blockstorage.{BlockDagStorage, BlockStore}
import io.casperlabs.casper.{SafetyOracle}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.execution.Scheduler

/** Create the Casper stack using the GossipService. */
package object gossiping {
  def apply[F[_]: Log: Metrics: Time: SafetyOracle: BlockStore: BlockDagStorage: NodeDiscovery: MultiParentCasperRef: ExecutionEngineService](
      port: Int,
      conf: Configuration,
      grpcScheduler: Scheduler
  )(implicit scheduler: Scheduler): Resource[F, Unit] =
    ???
}
