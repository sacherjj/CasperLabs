package io.casperlabs.node.api.graphql

import cats.data.Kleisli
import cats.effect.{ConcurrentEffect, Resource}
import cats.implicits._
import io.casperlabs.shared.Log
import io.casperlabs.shared.Log.NOPLog
import io.circe.parser.parse
import io.circe.syntax._
import io.circe.{Json, JsonObject}
import fs2._
import monix.eval.Task
import monix.eval.instances.CatsConcurrentEffectForTask
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.Atomic
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.java_websocket.extensions.IExtension
import org.java_websocket.protocols.IProtocol
import java.net.URI
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import scala.collection.JavaConverters._
import org.scalatest.concurrent.Eventually
import org.scalatest.{Matchers, WordSpecLike}
import sangria.execution.{ExceptionHandler, Executor, HandledException}
import sangria.schema._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class GraphQLServiceSpec extends WordSpecLike with Matchers with Eventually {

  implicit val patienceConfiguration: PatienceConfig = PatienceConfig(
    timeout = 5.seconds,
    interval = 100.millis
  )

  import GraphQLServiceSpec._

  "GraphQL service" when {
    "receives GraphQL query by POST" should {
      "return a successful response" in TestFixture.query() { service =>
        for {
          response <- service(
                       Request(
                         method = Method.POST,
                         body = body(Query)
                       )
                     )
        } yield check(response, Status.Ok, QuerySuccessfulResponse.some)
      }

      "handle logically invalid GraphQL query" in TestFixture.query() { service =>
        for {
          response <- service(
                       Request(
                         method = Method.POST,
                         body = body(InvalidQuery)
                       )
                     )
        } yield check(response, Status.BadRequest, checkBodyError _)
      }

      "handle invalid HTTP request" in TestFixture.query() { service =>
        for {
          response <- service(
                       Request(
                         method = Method.POST,
                         body = body("invalid non JSON body")
                       )
                     )
        } yield check(response, Status.BadRequest, checkBodyError _)
      }

      "return failure in case of errors in GraphQL schema resolver function" in TestFixture.query(
        Failure(new RuntimeException("Boom!"))
      ) { service =>
        for {
          response <- service(
                       Request(
                         method = Method.POST,
                         body = body(Query)
                       )
                     )
        } yield check(response, Status.Ok, checkBodyError _)
      }
    }

    "receives GraphQL subscription by WebSocket" should {
      "return a successful response stream for multiple requests in parallel" in TestFixture
        .subscription(
          List(
            GraphQLWebSocketMessage.ConnectionInit,
            GraphQLWebSocketMessage.Start("1", GraphQLQuery(SubscriptionQuery)),
            GraphQLWebSocketMessage.Start("2", GraphQLQuery(SubscriptionQuery)),
            GraphQLWebSocketMessage.Start("3", GraphQLQuery(s"query { $QueryFieldName }"))
          ).map(_.asJson.toString)
        ) { responses =>
          val expected = List(
            GraphQLWebSocketMessage.ConnectionAck,
            GraphQLWebSocketMessage.ConnectionKeepAlive,
            GraphQLWebSocketMessage.Complete("1"),
            GraphQLWebSocketMessage.Complete("2"),
            GraphQLWebSocketMessage.Complete("3"),
            GraphQLWebSocketMessage.Data("3", Json.fromJsonObject(QuerySuccessfulResponse))
          ) ::: SubscriptionResponsesEncoded.flatMap(
            json =>
              List(
                GraphQLWebSocketMessage.Data("1", json),
                GraphQLWebSocketMessage.Data("2", json)
              )
          )
          eventually {
            responses.get() should contain allElementsOf expected
          }
        }

      "cancel a stream if receives Stop" in TestFixture.subscription(
        toSend = List(
          GraphQLWebSocketMessage.ConnectionInit,
          GraphQLWebSocketMessage.Start("1", GraphQLQuery(SubscriptionQuery)),
          GraphQLWebSocketMessage.Stop("1")
        ).map(_.asJson.toString),
        subscriptionResponse = SubscriptionResponse.zipLeft(Stream.awakeEvery[Task](1.seconds))
      ) { responses =>
        assertThrows[Exception](eventually {
          responses.get() should contain atLeastOneElementOf (SubscriptionResponsesEncoded
            .map(json => GraphQLWebSocketMessage.Data("1", json)) :+
            GraphQLWebSocketMessage.Complete("1"))
        })
      }

      "ignore unparseable messages" in TestFixture
        .subscription(
          List(
            (GraphQLWebSocketMessage.ConnectionInit: GraphQLWebSocketMessage).asJson.toString,
            "Invalid message"
          )
        ) { responses =>
          val expected = List(
            GraphQLWebSocketMessage.ConnectionAck,
            GraphQLWebSocketMessage.ConnectionKeepAlive,
            GraphQLWebSocketMessage.ConnectionError(
              s"Failed to parse GraphQL WebSocket message: Invalid message, reason: expected json value got 'Invali...' (line 1, column 1)"
            )
          )
          eventually {
            responses.get() should contain allElementsOf expected
          }
        }
      "ignore messages not following protocol" in TestFixture
        .subscription(
          List(
            (GraphQLWebSocketMessage
              .Start("1", GraphQLQuery(SubscriptionQuery)): GraphQLWebSocketMessage).asJson.toString
          )
        ) { responses =>
          val expected = List(
            GraphQLWebSocketMessage.ConnectionError(
              s"Unexpected message: Start(1,GraphQLQuery($SubscriptionQuery)) in state: 'Waiting for init message', ignoring"
            )
          )
          eventually {
            responses.get() should contain allElementsOf expected
          }
        }
      "periodically send keep-alive messages" in TestFixture
        .subscription(
          List(
            (GraphQLWebSocketMessage.ConnectionInit: GraphQLWebSocketMessage).asJson.toString
          )
        ) { responses =>
          eventually {
            responses.get() should contain allElementsOf List(
              GraphQLWebSocketMessage.ConnectionKeepAlive,
              GraphQLWebSocketMessage.ConnectionKeepAlive
            )
          }
        }
      "validate query" in TestFixture
        .subscription(
          List(
            GraphQLWebSocketMessage.ConnectionInit,
            GraphQLWebSocketMessage.Start("1", GraphQLQuery(SubscriptionInvalidQuery))
          ).map(_.asJson.toString)
        ) { responses =>
          eventually {
            val messages = responses.get().iterator
            messages.next() shouldBe GraphQLWebSocketMessage.ConnectionAck
            messages.next() shouldBe GraphQLWebSocketMessage.ConnectionKeepAlive
            val error = messages.next()
            error shouldBe an[GraphQLWebSocketMessage.Error]
            error.asInstanceOf[GraphQLWebSocketMessage.Error].id shouldBe "1"
            error.asInstanceOf[GraphQLWebSocketMessage.Error].payload should startWith(
              "Query does not pass validation."
            )
          }
        }
    }
  }

  def body(query: String): Stream[Task, Byte] =
    Stream.emits[Task, Byte](query.getBytes.toList)

  def check(
      actual: Response[Task],
      expectedStatus: Status,
      expectedBodyOpt: Option[JsonObject] = None
  ): Unit = {
    val actualStatus = actual.status
    actualStatus shouldBe expectedStatus
    expectedBodyOpt.foreach { expectedBody =>
      val actualBody = actual.as[Json].map(_.asObject.get).runSyncUnsafe(5.seconds)
      actualBody shouldBe expectedBody
    }
  }

  def check(actual: Response[Task], expectedStatus: Status, checkBody: JsonObject => Unit): Unit = {
    val actualStatus = actual.status
    actualStatus shouldBe expectedStatus
    val actualBody = actual.as[Json].map(_.asObject.get).runSyncUnsafe(5.seconds)
    checkBody(actualBody)
  }

  def checkBodyError(body: JsonObject): Unit = body("errors") should not be empty
}

object GraphQLServiceSpec {

  type Service = Kleisli[Task, Request[Task], Response[Task]]

  implicit val concurrentEffectMonix: ConcurrentEffect[Task] =
    new CatsConcurrentEffectForTask()(global, Task.defaultOptions)

  implicit val noOpLog: Log[Task] = new NOPLog[Task]

  implicit val fs2SubscriptionStream: Fs2SubscriptionStream[Task] =
    new Fs2SubscriptionStream[Task]()

  val QueryFieldName     = "testField"
  val QueryFieldResponse = "testFieldResponse"
  val Query = Json
    .fromJsonObject(JsonObject("query" -> Json.fromString(s"query { $QueryFieldName }")))
    .toString()
  val QuerySuccessfulResponse = JsonObject(
    "data" -> Json.fromJsonObject(
      JsonObject(
        QueryFieldName -> Json.fromString(QueryFieldResponse)
      )
    )
  )
  val InvalidQuery = Json
    .fromJsonObject(JsonObject("query" -> Json.fromString(s"query { nonExistingField }")))
    .toString()

  val SubscriptionFieldName    = "testField"
  val SubscriptionQuery        = s"subscription { $SubscriptionFieldName }"
  val SubscriptionInvalidQuery = "subscription { nonExistingField }"
  val SubscriptionResponse     = Stream.emits[Task, String]((1 to 10).map(_.toString))
  val SubscriptionResponsesEncoded = SubscriptionResponse.compile.toList
    .map(_.map { s =>
      Json.fromJsonObject(
        JsonObject(
          "data" -> Json.fromJsonObject(
            JsonObject(
              "testField" -> Json.fromString(s)
            )
          )
        )
      )
    })
    .runSyncUnsafe(1.second)

  def createExecutor(
      queryResponse: Try[String] = Success(QueryFieldResponse),
      subscriptionResponse: Stream[Task, String] = SubscriptionResponse
  ): Executor[Unit, Unit] =
    Executor(
      schema = Schema(
        query = ObjectType(
          "Query",
          fields[Unit, Unit](
            Field(QueryFieldName, StringType, resolve = _ => TryValue[Unit, String](queryResponse))
          )
        ),
        subscription = ObjectType(
          "Subscription",
          fields[Unit, Unit](
            Field.subs(
              SubscriptionFieldName,
              StringType,
              resolve = _ => subscriptionResponse.map(Action(_))
            )
          )
        ).some
      ),
      exceptionHandler = ExceptionHandler({
        case (_, e) => HandledException(e.getMessage)
      })
    )

  object TestFixture {
    def query(
        queryResponse: Try[String] = Success(QueryFieldResponse)
    )(test: Service => Task[Unit]): Unit =
      test(GraphQL.buildRoute[Task](createExecutor(queryResponse), 1.second, global).orNotFound)
        .runSyncUnsafe(5.seconds)

    def subscription(
        toSend: List[String],
        subscriptionResponse: Stream[Task, String] = SubscriptionResponse,
        keepAlivePeriod: FiniteDuration = 5.seconds
    )(test: Atomic[Vector[GraphQLWebSocketMessage]] => Unit): Unit =
      (for {
        _         <- createServer(subscriptionResponse, keepAlivePeriod)
        responses <- sendWS(toSend)
      } yield responses).use(responses => Task(test(responses))).runSyncUnsafe(5.seconds)

    def createServer(
        subscriptionResponse: Stream[Task, String] = SubscriptionResponse,
        keepAlivePeriod: FiniteDuration = 5.seconds
    ): Resource[Task, Unit] =
      BlazeServerBuilder[Task]
        .bindHttp(40403, "localhost")
        .withNio2(true)
        .withHttpApp(
          Router(
            "/graphql" -> GraphQL
              .buildRoute[Task](
                createExecutor(subscriptionResponse = subscriptionResponse),
                keepAlivePeriod,
                global
              )
          ).orNotFound
        )
        .resource
        .void

    def sendWS(messages: List[String]): Resource[Task, Atomic[Vector[GraphQLWebSocketMessage]]] =
      Resource
        .make(Task {
          val responses = Atomic(Vector.empty[GraphQLWebSocketMessage])
          val client = new WebSocketClient(
            new URI("ws://localhost:40403/graphql"),
            new Draft_6455(
              List.empty[IExtension].asJava,
              List(new IProtocol { self =>
                override def acceptProvidedProtocol(s: String): Boolean =
                  s equalsIgnoreCase "graphql-ws"

                override def getProvidedProtocol: String = "graphql-ws"

                override def copyInstance(): IProtocol = self
              }).asJava
            )
          ) { self =>
            override def onOpen(serverHandshake: ServerHandshake): Unit =
              messages.foreach(self.send)

            override def onMessage(s: String): Unit =
              responses.transform(_ :+ parse(s).flatMap(_.as[GraphQLWebSocketMessage]).right.get)

            override def onClose(i: Int, s: String, b: Boolean): Unit = ()

            override def onError(e: Exception): Unit = ()
          }
          client.connectBlocking()
          (client, responses)
        })({
          case (client, _) => Task(client.closeBlocking())
        })
        .map(_._2)
  }
}
