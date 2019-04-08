package io.casperlabs.comm.rp

import com.google.protobuf.ByteString
import com.google.protobuf.any.{Any => AnyProto}
import io.casperlabs.comm.CommError._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.protocol.routing._
import com.google.protobuf.ByteString
import io.casperlabs.comm.transport.{Blob, PacketType}
import com.google.protobuf.ByteString

object ProtocolHelper {

  def toProtocolBytes(x: String): ByteString      = ByteString.copyFromUtf8(x)
  def toProtocolBytes(x: Array[Byte]): ByteString = ByteString.copyFrom(x)
  def toProtocolBytes(x: Seq[Byte]): ByteString   = ByteString.copyFrom(x.toArray)

  def header(src: Node): Header =
    Header()
      .withSender(src)

  def sender(proto: Protocol): Option[Node] =
    for {
      h <- proto.header
      s <- h.sender
    } yield s

  def protocol(src: Node): Protocol =
    Protocol().withHeader(header(src))

  def protocolHandshake(src: Node): Protocol =
    protocol(src).withProtocolHandshake(ProtocolHandshake())

  def toProtocolHandshake(proto: Protocol): CommErr[ProtocolHandshake] =
    proto.message.protocolHandshake.fold[CommErr[ProtocolHandshake]](
      Left(UnknownProtocolError(s"Was expecting ProtocolHandshake, got ${proto.message}"))
    )(Right(_))

  def protocolHandshakeResponse(src: Node): Protocol =
    protocol(src).withProtocolHandshakeResponse(ProtocolHandshakeResponse())

  def heartbeat(src: Node): Protocol =
    protocol(src).withHeartbeat(Heartbeat())

  def toHeartbeat(proto: Protocol): CommErr[Heartbeat] =
    proto.message.heartbeat.fold[CommErr[Heartbeat]](
      Left(UnknownProtocolError(s"Was expecting Heartbeat, got ${proto.message}"))
    )(Right(_))

  def heartbeatResponse(src: Node): Protocol =
    protocol(src).withHeartbeatResponse(HeartbeatResponse())

  def packet(src: Node, pType: PacketType, content: Array[Byte]): Protocol =
    packet(src, pType, ByteString.copyFrom(content))

  def packet(src: Node, pType: PacketType, content: ByteString): Protocol =
    protocol(src).withPacket(Packet(pType.id, content))

  def toPacket(proto: Protocol): CommErr[Packet] =
    proto.message.packet.fold[CommErr[Packet]](
      Left(UnknownProtocolError(s"Was expecting Packet, got ${proto.message}"))
    )(Right(_))

  def disconnect(src: Node): Protocol =
    protocol(src).withDisconnect(Disconnect())

  def toDisconnect(proto: Protocol): CommErr[Disconnect] =
    proto.message.disconnect.fold[CommErr[Disconnect]](
      Left(UnknownProtocolError(s"Was expecting Disconnect, got ${proto.message}"))
    )(Right(_))

  def blob(sender: Node, typeId: String, content: Array[Byte]): Blob =
    Blob(
      sender,
      Packet()
        .withTypeId(typeId)
        .withContent(ProtocolHelper.toProtocolBytes(content))
    )

}
