package io.casperlabs.casper.util.comm

import cats.Monad
import cats.effect.concurrent.Ref
import cats.implicits._
import io.casperlabs.comm.CommError.CommErr
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.rp.ProtocolHelper.protocol
import io.casperlabs.comm.transport._

import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration

class TransportLayerTestImpl[F[_]: Monad](
    identity: Node,
    val msgQueues: Map[Node, Ref[F, mutable.Queue[Protocol]]]
) extends TransportLayer[F] {

  def roundTrip(peer: Node, msg: Protocol, timeout: FiniteDuration): F[CommErr[Protocol]] = ???

  def send(peer: Node, msg: Protocol): F[CommErr[Unit]] =
    msgQueues.get(peer) match {
      case Some(qRef) =>
        qRef
          .update { q =>
            q.enqueue(msg); q
          }
          .map(Right(_))
      case None => ().pure[F].map(Right(_))
    }

  def broadcast(peers: Seq[Node], msg: Protocol): F[Seq[CommErr[Unit]]] =
    peers.toList.traverse(send(_, msg)).map(_.toSeq)

  def receive(
      dispatch: Protocol => F[CommunicationResponse],
      handleStreamed: Blob => F[Unit]
  ): F[Unit] =
    TransportLayerTestImpl.handleQueue(dispatch, msgQueues(identity))

  def stream(peers: Seq[Node], blob: Blob): F[Unit] =
    broadcast(peers, protocol(blob.sender).withPacket(blob.packet)).void

  def disconnect(peer: Node): F[Unit] = ???

  def shutdown(msg: Protocol): F[Unit] = ???

  def clear(peer: Node): F[Unit] =
    msgQueues.get(peer) match {
      case Some(qRef) =>
        qRef.update { q =>
          q.clear(); q
        }
      case None => ().pure[F]
    }
}

object TransportLayerTestImpl {
  def handleQueue[F[_]: Monad](
      dispatch: Protocol => F[CommunicationResponse],
      qRef: Ref[F, mutable.Queue[Protocol]]
  ): F[Unit] =
    for {
      maybeProto <- qRef.modify { q =>
                     if (q.nonEmpty) {
                       val proto = q.dequeue()
                       (q, proto.some)
                     } else (q, None)
                   }
      _ <- maybeProto match {
            case Some(proto) => dispatch(proto) *> handleQueue(dispatch, qRef)
            case None        => ().pure[F]
          }
    } yield ()
}
