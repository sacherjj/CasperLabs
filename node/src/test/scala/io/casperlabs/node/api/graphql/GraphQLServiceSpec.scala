package io.casperlabs.node.api.graphql

import cats.data.Kleisli
import cats.implicits._
import io.casperlabs.shared.Log
import io.casperlabs.shared.Log.NOPLog
import io.circe.{Json, JsonObject}
import fs2._
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.http4s._
import org.http4s.circe._
import org.http4s.implicits._
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.scalatest.{Matchers, WordSpecLike}
import sangria.execution.{ExceptionHandler, Executor, HandledException}
import sangria.schema._

import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class GraphQLServiceSpec extends WordSpecLike with Matchers {

  import GraphQLServiceSpec._

  "GraphQL service" when {
    "receives GraphQL query by POST" should {
      "return a successful response" in TestFixture() { service =>
        for {
          response <- service(
                       Request(
                         method = Method.POST,
                         body = body(Query)
                       )
                     )
        } yield check(response, Status.Ok, QuerySuccessfulResponse.some)
      }

      "handle logically invalid GraphQL query" in TestFixture() { service =>
        for {
          response <- service(
                       Request(
                         method = Method.POST,
                         body = body(InvalidQuery)
                       )
                     )
        } yield check(response, Status.BadRequest, checkBodyError _)
      }

      "handle invalid HTTP request" in TestFixture() { service =>
        for {
          response <- service(
                       Request(
                         method = Method.POST,
                         body = body("invalid non JSON body")
                       )
                     )
        } yield check(response, Status.BadRequest, checkBodyError _)
      }

      "return failure in case of errors in GraphQL schema resolver function" in TestFixture(
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
      "return a successful response stream" in pending
      "ignore messages not following protocol" in pending
      "periodically send keep-alive messages" in pending
      "validate query" in pending
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

  val SubscriptionFieldName = "testField"
  val SubscriptionResponse  = Stream.emits[Task, String]((1 to 10).map(_.toString))

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

  def runServer(service: HttpRoutes[Task])(test: Task[Unit]) =
    BlazeServerBuilder[Task]
      .bindHttp(8080, "localhost")
      .withHttpApp(
        Router(
          "/" -> service
        ).orNotFound
      )
      .resource
      .use(_ => test)

  object TestFixture {
    def apply(
        queryResponse: Try[String] = Success(QueryFieldResponse),
        subscriptionResponse: Stream[Task, String] = SubscriptionResponse,
        keepAlivePeriod: FiniteDuration = 1.second
    )(test: Service => Task[Unit]): Unit = {
      val service =
        GraphQL.buildRoute[Task](createExecutor(queryResponse, subscriptionResponse), 1.second)
      test(service.orNotFound).runSyncUnsafe(5.seconds)
    }
  }
}
