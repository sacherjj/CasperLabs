package io.casperlabs

import io.casperlabs.metrics.Metrics

package object casper {
  val CasperMetricsSource: Metrics.Source = Metrics.Source(Metrics.BaseSource, "casper")
}
