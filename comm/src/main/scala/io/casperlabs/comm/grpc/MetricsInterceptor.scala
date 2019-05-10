package io.casperlabs.comm.grpc

import cats.Id
import io.casperlabs.comm.CommMetricsSource
import io.casperlabs.metrics.Metrics
import io.grpc.{Metadata, ServerCall, ServerCallHandler, ServerInterceptor, Status}
import javax.net.ssl.SSLSession
import scala.util.Try

/** Maintain counters for each gRPC method. */
class MetricsInterceptor()(implicit metrics: Metrics[Id]) extends ServerInterceptor {

  implicit val metricsSource = Metrics.Source(CommMetricsSource, "grpc")

  override def interceptCall[A, B](
      call: ServerCall[A, B],
      headers: Metadata,
      next: ServerCallHandler[A, B]
  ): ServerCall.Listener[A] = {
    val name    = nameOf(call)
    val counter = s"${name}_requests"
    val gauge   = s"${name}_ongoing"

    // Show ongoing calls.
    metrics.incrementGauge(gauge)

    val wrapped = new ForwardingServerCall(call) {
      override def close(status: Status, trailers: Metadata) = {
        metrics.decrementGauge(gauge)
        metrics.incrementCounter(counter)
        if (!status.isOk) {
          // NOTE: If we were using the Prometheus client we could attach a label with the status.
          metrics.incrementCounter(counter + "_failed")
        }
        super.close(status, trailers)
      }
    }

    next.startCall(wrapped, headers)
  }

  def nameOf(call: ServerCall[_, _]) = {
    // Should be something like "io.casperlabs.comm.gossiping.GossipService/NewBlocks".
    // The `ScrapeDataBuilder` will replace every non-character with underscores.
    val name = call.getMethodDescriptor.getFullMethodName
    // Get rid of the package name.
    name.split('.').last
  }
}
