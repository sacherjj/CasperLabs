package io.casperlabs.client

import java.io.Closeable
import java.security.KeyStore
import java.util.concurrent.{Executor, TimeUnit}

import cats.Id
import cats.data.StateT
import cats.mtl.implicits._
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.info.{BlockInfo, DeployInfo}
import io.casperlabs.casper.consensus.state.Value
import io.casperlabs.client.configuration.ConnectOptions
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.util.HostnameTrustManager
import io.casperlabs.graphz
import io.casperlabs.graphz.StringSerializer
import io.casperlabs.node.api.casper._
import io.casperlabs.node.api.control.{ControlGrpcMonix, ProposeRequest}
import io.grpc.ManagedChannel
import io.grpc.netty.{GrpcSslContexts, NegotiationType, NettyChannelBuilder}
import io.netty.handler.ssl.util.SimpleTrustManagerFactory
import javax.net.ssl._
import monix.eval.Task

class GrpcDeployService(conn: ConnectOptions, executor: Executor)
    extends DeployService[Task]
    with Closeable {
  private val DefaultMaxMessageSize = 256 * 1024 * 1024

  private var externalConnected = false
  private var internalConnected = false

  private def makeChannel(port: Int): ManagedChannel = {
    var builder = NettyChannelBuilder
      .forAddress(conn.host, port)
      .maxInboundMessageSize(DefaultMaxMessageSize)
      .executor(executor)

    builder = conn.nodeId match {
      case Some(hash) =>
        // Server side TLS only. The client will compare the Node ID it's expecting the server
        // to have with the hash of the public key from the TLS certificate.
        val trustManagerFactory = new SimpleTrustManagerFactory {
          private val hostNameTrustManager = new HostnameTrustManager()

          def engineInit(keyStore: KeyStore): Unit                                 = {}
          def engineInit(managerFactoryParameters: ManagerFactoryParameters): Unit = {}
          def engineGetTrustManagers(): Array[TrustManager] =
            Array[TrustManager](hostNameTrustManager)
        }

        val sslContext = GrpcSslContexts.forClient
          .trustManager(trustManagerFactory)
          .build

        builder
          .negotiationType(NegotiationType.TLS)
          .sslContext(sslContext)
          .overrideAuthority(hash) // So it doesn't expect for the host name.

      case None =>
        builder.usePlaintext
    }

    builder.build()
  }

  private lazy val externalChannel: ManagedChannel = {
    externalConnected = true
    makeChannel(conn.portExternal)
  }

  private lazy val internalChannel: ManagedChannel = {
    internalConnected = true
    makeChannel(conn.portInternal)
  }

  private lazy val casperServiceStub  = CasperGrpcMonix.stub(externalChannel)
  private lazy val controlServiceStub = ControlGrpcMonix.stub(internalChannel)

  def deploy(d: consensus.Deploy): Task[String] =
    casperServiceStub
      .deploy(DeployRequest().withDeploy(d))
      .map { _ =>
        val hash = Base16.encode(d.deployHash.toByteArray)
        s"Success! Deploy $hash deployed."
      }

  def propose(): Task[String] =
    controlServiceStub
      .propose(ProposeRequest())
      .map { response =>
        val hash = Base16.encode(response.blockHash.toByteArray)
        s"Success! Block $hash created and added."
      }

  def showBlock(hash: String): Task[String] =
    casperServiceStub
      .getBlockInfo(GetBlockInfoRequest(hash, BlockInfo.View.FULL))
      .map(Printer.printToUnicodeString(_))

  def showDeploy(hash: String): Task[String] =
    casperServiceStub
      .getDeployInfo(GetDeployInfoRequest(hash, DeployInfo.View.BASIC))
      .map(Printer.printToUnicodeString(_))

  def showDeploys(hash: String): Task[String] =
    casperServiceStub
      .streamBlockDeploys(StreamBlockDeploysRequest(hash, DeployInfo.View.BASIC))
      .zipWithIndex
      .map {
        case (d, idx) =>
          s"""
         |------------- deploy # $hash / $idx ---------------
         |${Printer.printToUnicodeString(d)}
         |---------------------------------------------------
         |""".stripMargin
      }
      .toListL
      .map { xs =>
        val showLength =
          s"""
           |count: ${xs.length}
           |""".stripMargin

        xs.mkString("\n") + "\n" + showLength
      }

  def queryState(
      blockHash: String,
      keyVariant: String,
      keyValue: String,
      path: String
  ): Task[Value] =
    StateQuery.KeyVariant.values
      .find(_.name == keyVariant.toUpperCase)
      .fold(
        Task.raiseError[Value](
          new java.lang.IllegalArgumentException(s"Unknown key variant: $keyVariant")
        )
      ) { kv =>
        val req = GetBlockStateRequest(blockHash)
          .withQuery(
            StateQuery(
              kv,
              keyValue,
              path.split('/').filterNot(_.isEmpty)
            )
          )

        casperServiceStub.getBlockState(req)
      }

  def visualizeDag(depth: Int, showJustificationLines: Boolean): Task[String] =
    casperServiceStub
      .streamBlockInfos(StreamBlockInfosRequest(depth = depth, view = BlockInfo.View.BASIC))
      .toListL
      .map { infos =>
        type G[A] = StateT[Id, StringBuffer, A]
        implicit val ser: StringSerializer[G] = new graphz.StringSerializer[G]
        val state                             = GraphzGenerator.dagAsCluster[G](infos, GraphConfig(showJustificationLines))
        state.runS(new StringBuffer).toString
      }

  def showBlocks(depth: Int): Task[String] =
    casperServiceStub
      .streamBlockInfos(StreamBlockInfosRequest(depth = depth, view = BlockInfo.View.BASIC))
      .map { bi =>
        s"""
         |------------- block @ ${bi.getSummary.getHeader.rank} ---------------
         |${Printer.printToUnicodeString(bi)}
         |-----------------------------------------------------
         |""".stripMargin
      }
      .toListL
      .map { xs =>
        val showLength =
          s"""
           |count: ${xs.length}
           |""".stripMargin

        xs.mkString("\n") + "\n" + showLength
      }

  override def close(): Unit = {
    if (internalConnected)
      internalChannel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
    if (externalConnected)
      externalChannel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
  }
}
