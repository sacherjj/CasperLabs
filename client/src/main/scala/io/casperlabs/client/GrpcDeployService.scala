package io.casperlabs.client

import cats.Id
import cats.data.StateT
import cats.implicits._
import cats.mtl.implicits._
import java.io.Closeable
import java.util.concurrent.TimeUnit
import com.google.protobuf.empty.Empty
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.consensus
import io.casperlabs.graphz
import io.casperlabs.node.api.casper.{
  BlockInfoView,
  CasperGrpcMonix,
  DeployRequest,
  GetBlockInfoRequest,
  StreamBlockInfosRequest
}
import io.casperlabs.node.api.control.{ControlGrpcMonix, ProposeRequest}
import io.grpc.{ManagedChannel, ManagedChannelBuilder}
import monix.eval.Task

import scala.util.Either

class GrpcDeployService(host: String, portExternal: Int, portInternal: Int)
    extends DeployService[Task]
    with Closeable {
  private val DefaultMaxMessageSize = 256 * 1024 * 1024

  private var externalConnected = false
  private var internalConnected = false

  private lazy val externalChannel: ManagedChannel = {
    externalConnected = true
    ManagedChannelBuilder
      .forAddress(host, portExternal)
      .usePlaintext()
      .maxInboundMessageSize(DefaultMaxMessageSize)
      .build()
  }

  private lazy val internalChannel: ManagedChannel = {
    internalConnected = true
    ManagedChannelBuilder
      .forAddress(host, portInternal)
      .usePlaintext()
      .maxInboundMessageSize(DefaultMaxMessageSize)
      .build()
  }

  private lazy val deployServiceStub  = CasperMessageGrpcMonix.stub(externalChannel)
  private lazy val casperServiceStub  = CasperGrpcMonix.stub(externalChannel)
  private lazy val controlServiceStub = ControlGrpcMonix.stub(internalChannel)

  def deploy(d: consensus.Deploy): Task[Either[Throwable, String]] =
    casperServiceStub.deploy(DeployRequest().withDeploy(d)).map(_ => "Success!").attempt

  def propose(): Task[Either[Throwable, String]] =
    controlServiceStub
      .propose(ProposeRequest())
      .map { response =>
        val hash = Base16.encode(response.blockHash.toByteArray).take(10)
        s"Success! Block $hash... created and added."
      }
      .attempt

  def showBlock(hash: String): Task[Either[Throwable, String]] =
    casperServiceStub
      .getBlockInfo(GetBlockInfoRequest(hash, BlockInfoView.FULL))
      .map(Printer.printToUnicodeString(_))
      .attempt

  def queryState(q: QueryStateRequest): Task[Either[Throwable, String]] =
    deployServiceStub
      .queryState(q)
      .map(_.result)
      .attempt

  def visualizeDag(depth: Int, showJustificationLines: Boolean): Task[Either[Throwable, String]] =
    casperServiceStub
      .streamBlockInfos(StreamBlockInfosRequest(depth = depth, view = BlockInfoView.BASIC))
      .toListL
      .map { infos =>
        type G[A] = StateT[Id, StringBuffer, A]
        implicit val ser = new graphz.StringSerializer[G]
        val state        = GraphzGenerator.dagAsCluster[G](infos, GraphConfig(showJustificationLines))
        state.runS(new StringBuffer).toString
      }
      .attempt

  def showBlocks(depth: Int): Task[Either[Throwable, String]] =
    casperServiceStub
      .streamBlockInfos(StreamBlockInfosRequest(depth = depth, view = BlockInfoView.BASIC))
      .map { bi =>
        s"""
         |------------- block @ ${bi.getSummary.getHeader.rank} ---------------
         |${Printer.printToUnicodeString(bi)}
         |-----------------------------------------------------
         |""".stripMargin
      }
      .toListL
      .map { bs =>
        val showLength =
          s"""
           |count: ${bs.length}
           |""".stripMargin

        bs.mkString("\n") + "\n" + showLength
      }
      .attempt

  override def close(): Unit = {
    if (internalConnected)
      internalChannel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
    if (externalConnected)
      externalChannel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
  }
}
