package io.casperlabs.node.api.graphql

import cats.effect._
import cats.effect.concurrent.Deferred
import cats.effect.implicits._
import cats.implicits._
import io.casperlabs.casper.consensus.Block
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.node.api.graphql.GraphQLQuery._
import io.casperlabs.node.api.graphql.circe._
import io.casperlabs.shared.{Log, LogSource}
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import fs2._
import fs2.concurrent.Queue
import org.http4s.circe._
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebSocketFrame
import org.http4s.{Header, Headers, HttpRoutes, Request, Response, StaticFile}
import sangria.execution._
import sangria.parser.QueryParser

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import scala.util.{Failure, Success}

/**
  * Entry point of the package.
  */
object GraphQL {

  private val requiredHeaders =
    Set(Header("Upgrade", "websocket"), Header("Sec-WebSocket-Protocol", "graphql-ws"))
  private val emptyJsonObject               = Json.fromJsonObject(JsonObject.empty)
  private val keepAlivePeriod               = 10.seconds
  private implicit val logSource: LogSource = LogSource(getClass)

  /* Entry point */
  def service[F[_]: ConcurrentEffect: ContextShift: Timer: Log](
      implicit ec: ExecutionContext
  ): HttpRoutes[F] = {
    implicit val fs2SubscriptionStream = new Fs2SubscriptionStream[F]()
    buildRoute(Executor(GraphQLSchema.createSchema))
  }

  private def buildRoute[F[_]: Concurrent: ContextShift: Timer: Log](
      executor: Executor[Unit, Unit]
  )(
      implicit fs2SubscriptionStream: Fs2SubscriptionStream[F],
      ec: ExecutionContext
  ): HttpRoutes[F] = {
    val dsl = org.http4s.dsl.Http4sDsl[F]
    import dsl._
    HttpRoutes.of[F] {
      case req @ GET -> Root if requiredHeaders.forall(h => req.headers.exists(_ === h)) =>
        handleWebSocket(req, executor)
      case GET -> Root =>
        StaticFile.fromResource[F]("/graphql-playgroud.html", ec).getOrElseF(NotFound())
      case req @ POST -> Root =>
        Ok(for {
          decodingResult <- req.as[Json].map(_.as[GraphQLQuery])
          res            <- decodingResult.fold(e => Sync[F].raiseError[Json](e), processQuery(_, executor))
        } yield res)
    }
  }

  private def handleWebSocket[F[_]: Concurrent: Timer: Log](
      req: Request[F],
      executor: Executor[Unit, Unit]
  )(implicit fs2SubscriptionStream: Fs2SubscriptionStream[F]): F[Response[F]] = {

    def out(
        queue: Queue[F, GraphQLWebSocketMessage],
        stopSignal: F[Unit]
    ): Stream[F, WebSocketFrame] = {
      val keepAlive = Stream
        .awakeEvery[F](keepAlivePeriod)
        .map(
          _ =>
            WebSocketFrame.Text(
              (GraphQLWebSocketMessage.ConnectionKeepAlive: GraphQLWebSocketMessage).asJson
                .toString()
            )
        )
      val output = queue.dequeue
        .map { m =>
          WebSocketFrame.Text(m.asJson.toString())
        }
        .interruptWhen(stopSignal.map(_.asRight[Throwable]))

      output.mergeHaltL(keepAlive)
    }

    for {
      stopSignal <- Deferred[F, Unit]
      queue      <- Queue.bounded[F, GraphQLWebSocketMessage](100)
      response <- WebSocketBuilder[F].build(
                   send = out(queue, stopSignal.get),
                   receive = protocolLogic(queue, stopSignal, executor),
                   headers = Headers.of(Header("Sec-WebSocket-Protocol", "graphql-ws"))
                 )
    } yield response
  }

  private def protocolLogic[F[_]: Concurrent: Log](
      queue: Queue[F, GraphQLWebSocketMessage],
      stopSignal: Deferred[F, Unit],
      executor: Executor[Unit, Unit]
  )(implicit fs2SubscriptionStream: Fs2SubscriptionStream[F]): Pipe[F, WebSocketFrame, Unit] =
    _.interruptWhen(stopSignal.get.map(_.asRight[Throwable]))
      .flatMap {
        case WebSocketFrame.Text(raw, _) =>
          parse(raw)
            .flatMap(_.as[GraphQLWebSocketMessage])
            .fold(e => Stream.raiseError[F](e), m => Stream.emit(m))
      }
      .evalMapAccumulate[F, ProtocolState, Unit]((ProtocolState.WaitForInit: ProtocolState)) {
        case (ProtocolState.WaitForInit, GraphQLWebSocketMessage.ConnectionInit) =>
          for {
            _ <- queue.enqueue1(GraphQLWebSocketMessage.ConnectionAck)
            _ <- queue.enqueue1(GraphQLWebSocketMessage.ConnectionKeepAlive)
          } yield (ProtocolState.WaitForStart, ())

        case (ProtocolState.WaitForStart, GraphQLWebSocketMessage.Start(id, payload)) =>
          for {
            fiber <- processSubscription[F](payload, executor)
                      .map(json => GraphQLWebSocketMessage.Data(id, json))
                      .onFinalizeCase {
                        case ExitCase.Error(e) =>
                          queue.enqueue1(GraphQLWebSocketMessage.Error(id, e.getMessage))
                        case ExitCase.Completed =>
                          queue.enqueue1(GraphQLWebSocketMessage.Complete(id))
                        case ExitCase.Canceled => ().pure[F]
                      }
                      .evalMap(queue.enqueue1)
                      .compile
                      .toList
                      .void
                      .start
          } yield (ProtocolState.SendingData(fiber), ())

        case (ProtocolState.SendingData(fiber), GraphQLWebSocketMessage.Stop) =>
          for {
            _ <- fiber.asInstanceOf[Fiber[F, Unit]].cancel.start
          } yield (ProtocolState.WaitForStart, ())

        case (ProtocolState.SendingData(fiber), GraphQLWebSocketMessage.ConnectionTerminate) =>
          for {
            _ <- fiber.asInstanceOf[Fiber[F, Unit]].cancel.start
            _ <- stopSignal.complete(())
          } yield (ProtocolState.Closed, ())

        case (_, GraphQLWebSocketMessage.ConnectionTerminate) =>
          for {
            _ <- stopSignal.complete(())
          } yield (ProtocolState.Closed, ())

        case (protocolState, message) =>
          Log[F].warn(s"Unexpected message: $message in state: $protocolState, ignoring") >> (
            protocolState,
            ()
          ).pure[F]
      }
      .drain

  private def processSubscription[F[_]: MonadThrowable](
      query: GraphQLQuery,
      executor: Executor[Unit, Unit]
  )(
      implicit fs2SubscriptionStream: Fs2SubscriptionStream[F]
  ): Stream[F, Json] = {
    import sangria.execution.ExecutionScheme.Stream
    fs2.Stream
      .fromEither[F](QueryParser.parse(query.query).toEither)
      .flatMap(queryAst => executor.execute(queryAst, (), ()))
  }

  private def processQuery[F[_]: Async](query: GraphQLQuery, executor: Executor[Unit, Unit])(
      implicit ec: ExecutionContext
  ): F[Json] =
    QueryParser.parse(query.query) match {
      case Success(queryAst) =>
        Async[F].async[Json](
          callback =>
            executor.execute(queryAst, (), (), none[String], emptyJsonObject).onComplete {
              case Success(json) => callback(json.asRight[Throwable])
              case Failure(e)    => callback(e.asLeft[Json])
            }
        )
      case Failure(e) => Async[F].raiseError[Json](e)
    }
}
