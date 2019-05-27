package io.casperlabs.node.api.graphql

import cats.syntax.either._
import io.circe._
import io.circe.generic.semiauto._

//TODO add support of other fields: operationName, variables
private[graphql] final case class GraphQLQuery(query: String)
private[graphql] object GraphQLQuery {
  implicit val decoder: Decoder[GraphQLQuery] = deriveDecoder[GraphQLQuery]
}

/**
  * See: [[https://github.com/apollographql/subscriptions-transport-ws/blob/master/PROTOCOL.md]]
  */
private[graphql] sealed trait GraphQLWebSocketMessage extends Product with Serializable {
  def `type`: String
}
private[graphql] object GraphQLWebSocketMessage {
  /* Client -> Server */
  final case object ConnectionInit extends GraphQLWebSocketMessage {
    override val `type`: String = "connection_init"
  }

  /* Server -> Client */
  final case object ConnectionAck extends GraphQLWebSocketMessage {
    override val `type`: String = "connection_ack"
  }

  /* Server -> Client */
  final case class ConnectionError(payload: String) extends GraphQLWebSocketMessage {
    override val `type`: String = "connection_error"
  }

  /* Server -> Client */
  final case object ConnectionKeepAlive extends GraphQLWebSocketMessage {
    override val `type`: String = "ka"
  }

  /* Client -> Server */
  final case object ConnectionTerminate extends GraphQLWebSocketMessage {
    override val `type`: String = "connection_terminate"
  }

  /* Client -> Server */
  final case class Start(id: String, payload: GraphQLQuery) extends GraphQLWebSocketMessage {
    override val `type`: String = "start"
  }

  /* Server -> Client */
  final case class Data(id: String, payload: Json) extends GraphQLWebSocketMessage {
    override val `type`: String = "data"
  }

  /* Server -> Client */
  final case class Error(id: String, payload: String) extends GraphQLWebSocketMessage {
    override val `type`: String = "error"
  }

  /* Server -> Client */
  final case class Complete(id: String) extends GraphQLWebSocketMessage {
    override val `type`: String = "complete"
  }

  /* Client -> Server */
  final case object Stop extends GraphQLWebSocketMessage {
    override val `type`: String = "stop"
  }

  implicit val encoder: Encoder[GraphQLWebSocketMessage] = (m: GraphQLWebSocketMessage) => {
    val json = JsonObject("type" -> Json.fromString(m.`type`))
    Json.fromJsonObject(m match {
      case ConnectionAck | ConnectionKeepAlive => json
      case ConnectionError(payload)            => json.add("payload", Json.fromString(payload))
      case Data(id, payload)                   => json.add("id", Json.fromString(id)).add("payload", payload)
      case Error(id, payload) =>
        json.add("id", Json.fromString(id)).add("payload", Json.fromString(payload))
      case Complete(id) => json.add("id", Json.fromString(id))
      case _ =>
        throw new IllegalArgumentException(s"Unsupported message type: $m")
    })
  }

  implicit val decoder: Decoder[GraphQLWebSocketMessage] = (c: HCursor) => {
    for {
      t <- c.downField("type").as[String]
      m <- t match {
            case "connection_init"      => ConnectionInit.asRight[DecodingFailure]
            case "connection_terminate" => ConnectionTerminate.asRight[DecodingFailure]
            case "stop"                 => Stop.asRight[DecodingFailure]
            case "start" =>
              for {
                id      <- c.downField("id").as[String]
                payload <- c.downField("payload").as[GraphQLQuery]
              } yield Start(id, payload)
            case _ =>
              DecodingFailure(s"Unsupported message type: $t", Nil).asLeft[GraphQLWebSocketMessage]
          }
    } yield m
  }
}
