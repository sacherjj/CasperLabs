package io.casperlabs.comm

import cats._, cats.data._, cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.catscontrib._, Catscontrib._, ski._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.discovery.Node
import io.grpc.{Status, StatusRuntimeException}
import java.nio.file._
import scala.util.control.NoStackTrace

// TODO we need lower level errors and general error, for now all in one place
// TODO cleanup unused errors (UDP trash)
sealed trait CommError
final case class InitializationError(msg: String)                   extends CommError
final case class UnknownCommError(msg: String)                      extends CommError
final case class DatagramSizeError(size: Int)                       extends CommError
final case class DatagramFramingError(ex: Exception)                extends CommError
final case class DatagramException(ex: Exception)                   extends CommError
final case object HeaderNotAvailable                                extends CommError
final case class ProtocolException(th: Throwable)                   extends CommError
final case class UnknownProtocolError(msg: String)                  extends CommError
final case class PublicKeyNotAvailable(node: PeerNode)              extends CommError
final case class ParseError(msg: String)                            extends CommError
final case object EncryptionHandshakeIncorrectlySigned              extends CommError
final case object BootstrapNotProvided                              extends CommError
final case class PeerNodeNotFound(peer: PeerNode)                   extends CommError
final case class PeerUnavailable(peer: PeerNode)                    extends CommError
final case class MalformedMessage(pm: Protocol)                     extends CommError
final case object CouldNotConnectToBootstrap                        extends CommError
final case class InternalCommunicationError(msg: String)            extends CommError
final case object TimeOut                                           extends CommError
final case object NoResponseForRequest                              extends CommError
final case object UpstreamNotAvailable                              extends CommError
final case class UnexpectedMessage(msgStr: String)                  extends CommError
final case object SenderNotAvailable                                extends CommError
final case class PongNotReceivedForPing(peer: PeerNode)             extends CommError
final case class UnableToStorePacket(packet: Packet, th: Throwable) extends CommError
final case class UnabletoRestorePacket(path: Path, th: Throwable)   extends CommError
// TODO add Show instance

object CommError {

  type ErrorHandler[F[_]] = ApplicativeError_[F, CommError]

  object ErrorHandler {
    def apply[F[_]](implicit ev: ApplicativeError_[F, CommError]): ApplicativeError_[F, CommError] =
      ev
  }

  type CommErrT[F[_], A] = EitherT[F, CommError, A]
  type CommErr[A]        = Either[CommError, A]

  def unknownCommError(msg: String): CommError           = UnknownCommError(msg)
  def unknownProtocol(msg: String): CommError            = UnknownProtocolError(msg)
  def parseError(msg: String): CommError                 = ParseError(msg)
  def protocolException(th: Throwable): CommError        = ProtocolException(th)
  def headerNotAvailable: CommError                      = HeaderNotAvailable
  def peerNodeNotFound(peer: PeerNode): CommError        = PeerNodeNotFound(peer)
  def peerUnavailable(peer: PeerNode): CommError         = PeerUnavailable(peer)
  def publicKeyNotAvailable(peer: PeerNode): CommError   = PublicKeyNotAvailable(peer)
  def couldNotConnectToBootstrap: CommError              = CouldNotConnectToBootstrap
  def internalCommunicationError(msg: String): CommError = InternalCommunicationError(msg)
  def malformedMessage(pm: Protocol): CommError          = MalformedMessage(pm)
  def noResponseForRequest: CommError                    = NoResponseForRequest
  def upstreamNotAvailable: CommError                    = UpstreamNotAvailable
  def unexpectedMessage(msgStr: String): CommError       = UnexpectedMessage(msgStr)
  def senderNotAvailable: CommError                      = SenderNotAvailable
  def pongNotReceivedForPing(peer: PeerNode): CommError  = PongNotReceivedForPing(peer)
  def timeout: CommError                                 = TimeOut
  def unableToStorePacket(packet: Packet, th: Throwable): CommError =
    UnableToStorePacket(packet, th)
  def unabletoRestorePacket(path: Path, th: Throwable) = UnabletoRestorePacket(path, th)

  def errorMessage(ce: CommError): String =
    ce match {
      case PeerUnavailable(_) => "Peer is currently unavailable"
      case PongNotReceivedForPing(_) =>
        "Peer is behind a firewall and can't be accessed from outside"
      case CouldNotConnectToBootstrap      => "Node could not connect to bootstrap node"
      case TimeOut                         => "Timeout"
      case InternalCommunicationError(msg) => s"Internal communication error. $msg"
      case UnknownProtocolError(msg)       => s"Unknown protocol error. $msg"
      case ProtocolException(t) =>
        val msg = Option(t.getMessage).getOrElse("")
        s"Protocol error. $msg"
      case _ => ce.toString
    }

  implicit class CommErrorToMessage(commError: CommError) {
    val message: String = CommError.errorMessage(commError)
  }
}

/** Errors that we want to propagate over gRPC interfaces,
  * matching one of https://grpc.io/grpc-java/javadoc/io/grpc/Status.html */
sealed trait ServiceError extends NoStackTrace
object ServiceError {

  type ServiceException = StatusRuntimeException with ServiceError

  /** Factory to create and match gRPC errors. */
  abstract class StatusError(status: Status) {
    def apply(msg: String): ServiceException =
      new StatusRuntimeException(status.withDescription(msg)) with ServiceError

    def unapply(ex: Throwable): Option[String] = ex match {
      case ex: StatusRuntimeException if ex.getStatus.getCode == status.getCode =>
        Some(ex.getStatus.getDescription)
      case _ =>
        None
    }
  }

  object NotFound extends StatusError(Status.NOT_FOUND) {
    def block(blockHash: ByteString): ServiceException =
      apply(s"Block ${Base16.encode(blockHash.toByteArray)} could not be found.")
  }

  object InvalidArgument extends StatusError(Status.INVALID_ARGUMENT)
  object Unauthenticated extends StatusError(Status.UNAUTHENTICATED)
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
