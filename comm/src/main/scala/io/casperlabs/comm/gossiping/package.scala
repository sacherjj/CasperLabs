package io.casperlabs.comm

package object gossiping {
  val GossipingMetricsSource       = CommMetricsSource / "gossiping"
  val BlockGossipingMetricsSource  = GossipingMetricsSource / "blocks"
  val DeployGossipingMetricsSource = GossipingMetricsSource / "deploys"

  /** Alias for return type of methods that return a handle that can be waited upon. */
  type WaitHandle[F[_]] = F[Unit]
}
