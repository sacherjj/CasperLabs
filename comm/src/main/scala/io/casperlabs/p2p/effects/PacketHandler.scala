package io.casperlabs.p2p.effects

import io.casperlabs.comm.CommError
import io.casperlabs.comm.protocol.routing.Packet
import cats._
import cats.data._
import cats.implicits._
import io.casperlabs.catscontrib._
import Catscontrib._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.shared._

trait PacketHandler[F[_]] {
  def handlePacket(peer: Node, packet: Packet): F[Option[Packet]]
}

object PacketHandler extends PacketHandlerInstances {
  private implicit val logSource: LogSource = LogSource(this.getClass)

  def apply[F[_]: PacketHandler]: PacketHandler[F] = implicitly[PacketHandler[F]]

  def forTrans[F[_]: Monad, T[_[_], _]: MonadTrans](
      implicit C: PacketHandler[F]
  ): PacketHandler[T[F, ?]] =
    new PacketHandler[T[F, ?]] {
      def handlePacket(peer: Node, packet: Packet): T[F, Option[Packet]] =
        C.handlePacket(peer, packet).liftM[T]
    }

  def pf[F[_]](pfForPeer: Node => PartialFunction[Packet, F[Unit]])(
      implicit ev1: Applicative[F],
      ev2: Log[F],
      errorHandler: ApplicativeError_[F, CommError]
  ): PacketHandler[F] =
    new PacketHandler[F] {
      def handlePacket(peer: Node, packet: Packet): F[Option[Packet]] = {
        val errorMsg = s"Unable to handle packet $packet"
        val pf       = pfForPeer(peer)
        if (pf.isDefinedAt(packet)) pf(packet).as(None)
        else
          Log[F].error(errorMsg) *> errorHandler
            .raiseError(CommError.unknownProtocol(errorMsg))
            .as(none[Packet])
      }
    }

  class NOPPacketHandler[F[_]: Applicative] extends PacketHandler[F] {
    def handlePacket(peer: Node, packet: Packet): F[Option[Packet]] = packet.some.pure[F]
  }

}

sealed abstract class PacketHandlerInstances {
  implicit def eitherTPacketHandler[E, F[_]: Monad: PacketHandler[?[_]]]
    : PacketHandler[EitherT[F, E, ?]] =
    PacketHandler.forTrans[F, EitherT[?[_], E, ?]]
}
