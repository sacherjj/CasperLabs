package io.casperlabs.client
import java.io.Closeable
import java.util.concurrent.TimeUnit

import com.google.protobuf.empty.Empty
import io.casperlabs.casper.protocol._
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import monix.eval.Task

import scala.util.Either

class GrpcDeployService(host: String, port: Int) extends DeployService[Task] with Closeable {
  private val DefaultMaxMessageSize = 256 * 1024 * 1024

  private val channel: ManagedChannel =
    ManagedChannelBuilder
      .forAddress(host, port)
      .usePlaintext()
      .maxInboundMessageSize(DefaultMaxMessageSize)
      .build()

  private val stub = CasperMessageGrpcMonix.stub(channel)

  def deploy(d: DeployData): Task[Either[Throwable, String]] =
    stub.doDeploy(d).map { response =>
      if (response.success) Right(response.message)
      else Left(new RuntimeException(response.message))
    }

  def createBlock(): Task[Either[Throwable, String]] =
    stub.createBlock(Empty()).map { response =>
      if (response.success) Right(response.message)
      else Left(new RuntimeException(response.message))
    }

  def showBlock(q: BlockQuery): Task[Either[Throwable, String]] =
    stub.showBlock(q).map { response =>
      if (response.status == "Success") Right(response.toProtoString)
      else Left(new RuntimeException(response.status))
    }

  def queryState(q: QueryStateRequest): Task[Either[Throwable, String]] =
    stub
      .queryState(q)
      .attempt
      .map(_.map {
        case QueryStateResponse(msg) => msg
      })

  def visualizeDag(q: VisualizeDagQuery): Task[Either[Throwable, String]] =
    stub.visualizeDag(q).attempt.map(_.map(_.content))

  def showBlocks(q: BlocksQuery): Task[Either[Throwable, String]] =
    stub
      .showBlocks(q)
      .map { bi =>
        s"""
         |------------- block ${bi.blockNumber} ---------------
         |${bi.toProtoString}
         |-----------------------------------------------------
         |""".stripMargin
      }
      .toListL
      .map { bs =>
        val showLength =
          s"""
           |count: ${bs.length}
           |""".stripMargin

        Right(bs.mkString("\n") + "\n" + showLength)
      }

  override def close(): Unit = {
    val terminated = channel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
    if (!terminated) {
      println(
        "warn: did not shutdown after 10 seconds, retrying with additional 10 seconds timeout"
      )
      channel.awaitTermination(10, TimeUnit.SECONDS)
    }
  }
}
