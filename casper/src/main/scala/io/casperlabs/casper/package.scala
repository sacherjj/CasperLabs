package io.casperlabs

import com.google.protobuf.ByteString
import io.casperlabs.metrics.Metrics

package object casper {
  val CasperMetricsSource: Metrics.Source = Metrics.Source(Metrics.BaseSource, "casper")
  type DeployHash = ByteString
}
