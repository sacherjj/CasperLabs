package io.casperlabs

import io.casperlabs.metrics.Metrics

package object blockstorage {
  val BlockStorageMetricsSource: Metrics.Source =
    Metrics.Source(Metrics.BaseSource, "block-storage")

  val BlockDagStorageMetricsSource: Metrics.Source =
    Metrics.Source(Metrics.BaseSource, "block-dag-storage")
}
