package io.casperlabs.comm.discovery

import cats.Applicative
import cats.mtl.DefaultApplicativeAsk
import com.google.protobuf.ByteString
import io.casperlabs.comm._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration._
import scala.util.Random

class GrpcKademliaServiceSpec extends KademliaServiceSpec[Task, GrpcEnvironment] {

  implicit val log: Log[Task]         = new Log.NOPLog[Task]
  implicit val scheduler: Scheduler   = Scheduler.Implicits.global
  implicit val metrics: Metrics[Task] = new Metrics.MetricsNOP

  def createEnvironment(port: Int): Task[GrpcEnvironment] =
    Task.delay {
      val host  = "127.0.0.1"
      val bytes = Array.ofDim[Byte](40)
      Random.nextBytes(bytes)
      val peer = Node(ByteString.copyFrom(bytes), host, 0, port)
      GrpcEnvironment(host, port, peer)
    }

  def createKademliaService(
      env: GrpcEnvironment,
      timeout: FiniteDuration
  ): Task[KademliaService[Task]] = {
    implicit val ask: NodeAsk[Task] =
      new DefaultApplicativeAsk[Task, Node] {
        val applicative: Applicative[Task] = Applicative[Task]
        def ask: Task[Node]                = Task.pure(env.peer)
      }
    CachedConnections[Task, KademliaConnTag].map { implicit cache =>
      new GrpcKademliaService(env.port, timeout)
    }
  }

  def extract[A](fa: Task[A]): A = fa.runSyncUnsafe(Duration.Inf)
}

case class GrpcEnvironment(
    host: String,
    port: Int,
    peer: Node
) extends Environment
