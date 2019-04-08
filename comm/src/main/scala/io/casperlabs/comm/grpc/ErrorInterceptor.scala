package io.casperlabs.comm.grpc

import cats.Id
import io.casperlabs.shared.Log
import io.grpc.{Grpc, Metadata, ServerCall, ServerCallHandler, ServerInterceptor, Status}

/** Turn exceptions into one of the standard gRPC status codes:
  * https://github.com/grpc/grpc/blob/master/doc/statuscodes.md */
class ErrorInterceptor(f: PartialFunction[Throwable, Status.Code])(implicit log: Log[Id])
    extends ServerInterceptor {

  // See https://github.com/saturnism/grpc-java-by-example/tree/master/error-handling-example

  class Forwarder[A, B](call: ServerCall[A, B]) extends ForwardingServerCall(call) {
    override def close(status: Status, trailers: Metadata) = {
      // Turn unknown errors with exceptions into Statuses.
      val s =
        if (status.getCode == Status.Code.UNKNOWN &&
            status.getDescription == null &&
            status.getCause != null) {
          val e = status.getCause
          f.lift(e)
            .map(Status.fromCode(_))
            .getOrElse(Status.INTERNAL)
            .withDescription(e.getMessage)
        } else status

      logAndClose(s, trailers)
    }

    def logAndClose(status: Status, trailers: Metadata) = {
      val desc   = Option(status.getDescription).getOrElse("")
      val cause  = Option(status.getCause).map(_.getMessage).getOrElse("")
      val method = call.getMethodDescriptor.getFullMethodName
      val source = Option(call.getAttributes.get(Grpc.TRANSPORT_ATTR_REMOTE_ADDR))
        .fold("unknown address")(_.toString)

      // Log internal errors with stack trace. Otherwise just warn, treat them as domain errors.
      if (status.getCode == Status.Code.INTERNAL) {
        Log[Id].error(
          s"Closing gRPC call from $source to $method with ${status.getCode}: $desc",
          status.getCause
        )
      } else if (!status.isOk)
        Log[Id].warn(
          s"Closing gRPC call from $source to $method with ${status.getCode}: $desc $cause"
        )

      super.close(status, trailers)
    }
  }

  override def interceptCall[A, B](
      call: ServerCall[A, B],
      headers: Metadata,
      next: ServerCallHandler[A, B]
  ): ServerCall.Listener[A] =
    next.startCall(new Forwarder(call), headers)
}

object ErrorInterceptor {

  /** Provide a mapping from various domain errors to gRPC status codes. */
  def apply(f: PartialFunction[Throwable, Status.Code])(implicit log: Log[Id]) =
    new ErrorInterceptor(f)

  def default(implicit log: Log[Id]) = apply {
    // Don't match anything, let them be turned into internal errors,
    // unless they are already StatusRuntimeExceptions.
    case _ if false => Status.Code.UNKNOWN
  }
}
