package io.casperlabs.comm.discovery

import cats.{Eq, Show}
import com.google.protobuf.ByteString
import io.casperlabs.comm.{CommError, ParseError}
import io.casperlabs.crypto.codec.Base16
import io.lemonlabs.uri.Url

import scala.util.Try

final case class NodeIdentifier(key: List[Byte]) extends AnyVal {
  override def toString: String = Base16.encode(key.toArray)

  def asByteString: ByteString = ByteString.copyFrom(key.toArray)
}

object NodeIdentifier {
  def apply(bs: Seq[Byte]): NodeIdentifier = NodeIdentifier(bs.toList)

  def apply(bs: ByteString): NodeIdentifier = NodeIdentifier(bs.toByteArray.toList)

  def apply(name: String): NodeIdentifier =
    NodeIdentifier(name.sliding(2, 2).toList.map(Integer.parseInt(_, 16).toByte))
}

object NodeUtils {
  implicit val showNode: Show[Node] = Show.show(
    node =>
      s"casperlabs://${NodeIdentifier(node.id)}@${node.host}?protocol=${node.protocolPort}&discovery=${node.discoveryPort}"
  )

  implicit class NodeCompanionOps(val nodeCompanion: Node.type) {

    def apply(id: NodeIdentifier, host: String, protocol: Int, discovery: Int): Node =
      Node(ByteString.copyFrom(id.key.toArray), host, protocol, discovery)

    def fromAddress(str: String): Either[CommError, Node] = {
      // TODO toInt, not URL, scheme not casperlabs, renameflag to discovery-port
      val maybeUrl: Option[Url] = Try(Url.parse(str)).toOption

      val maybePeer = maybeUrl flatMap (
          url =>
            for {
              _         <- url.schemeOption
              id        <- url.user
              host      <- url.hostOption
              discovery <- url.query.param("discovery").flatMap(v => Try(v.toInt).toOption)
              protocol  <- url.query.param("protocol").flatMap(v => Try(v.toInt).toOption)
            } yield apply(NodeIdentifier(id), host.value, protocol, discovery)
        )

      maybePeer.fold[Either[CommError, Node]](Left(ParseError(s"bad address: $str")))(Right(_))
    }
  }
}
