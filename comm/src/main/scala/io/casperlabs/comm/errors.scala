package io.casperlabs.comm

import java.nio.file._

import cats.data._
import com.google.protobuf.ByteString
import io.casperlabs.catscontrib._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.crypto.codec.Base16
import io.grpc.{Status, StatusRuntimeException}

import scala.util.control.NoStackTrace

/** Errors that we want to propagate over gRPC interfaces,
  * matching one of https://grpc.io/grpc-java/javadoc/io/grpc/Status.html */
sealed trait ServiceError extends NoStackTrace
object ServiceError {
  // Created class so that in logs it's a bit more readable.
  // TODO: Provide customer `Serializable` implementation so test can be forked.
  class Exception(status: Status) extends StatusRuntimeException(status) with ServiceError

  /** Factory to create and match gRPC errors. */
  abstract class StatusError(status: Status) {
    def apply(msg: String): Exception =
      new Exception(status.withDescription(msg))

    def unapply(ex: Throwable): Option[String] = ex match {
      case ex: StatusRuntimeException if ex.getStatus.getCode == status.getCode =>
        Some(ex.getStatus.getDescription)
      case _ =>
        None
    }
  }

  object NotFound extends StatusError(Status.NOT_FOUND) {
    def deploy(deployHash: ByteString): ServiceError.Exception =
      deploy(Base16.encode(deployHash.toByteArray))

    def deploy(deployHashBase16: String): ServiceError.Exception =
      apply(s"Deploy ${deployHashBase16} could not be found.")

    def block(blockHash: ByteString): ServiceError.Exception =
      block(Base16.encode(blockHash.toByteArray))

    def block(blockHashBase16: String): ServiceError.Exception =
      apply(s"Block ${blockHashBase16} could not be found.")
  }

  // NOTE: See https://github.com/grpc/grpc/blob/master/doc/statuscodes.md about when to use which one.

  object Aborted            extends StatusError(Status.ABORTED)
  object AlreadyExists      extends StatusError(Status.ALREADY_EXISTS)
  object DeadlineExceeded   extends StatusError(Status.DEADLINE_EXCEEDED)
  object FailedPrecondition extends StatusError(Status.FAILED_PRECONDITION)
  object Internal           extends StatusError(Status.INTERNAL)
  object InvalidArgument    extends StatusError(Status.INVALID_ARGUMENT)
  object OutOfRange         extends StatusError(Status.OUT_OF_RANGE)
  object ResourceExhausted  extends StatusError(Status.RESOURCE_EXHAUSTED)
  object Unauthenticated    extends StatusError(Status.UNAUTHENTICATED)
  object Unavailable        extends StatusError(Status.UNAVAILABLE)
  object Unimplemented      extends StatusError(Status.UNIMPLEMENTED)
}

sealed trait GossipError extends NoStackTrace
object GossipError {

  /** Download from the other party failed due to invalid chunk sizes. */
  final case class InvalidChunks(msg: String, source: Node) extends Exception(msg) with GossipError

  /** Tried to schedule a download for which we don't have the dependencies. */
  final case class MissingDependencies(msg: String) extends Exception(msg) with GossipError
  object MissingDependencies {
    def apply(blockHash: ByteString, missing: Seq[ByteString]): MissingDependencies =
      MissingDependencies(
        s"Block ${Base16.encode(blockHash.toByteArray)} has missing dependencies: [${missing
          .map(x => Base16.encode(x.toByteArray))
          .mkString(", ")}]"
      )
  }
}
