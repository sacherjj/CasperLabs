package io.casperlabs.comm

import io.casperlabs.metrics.Metrics

package object gossiping {
  val GossipingMetricsSource = Metrics.Source(CommMetricsSource, "gossiping")
}
