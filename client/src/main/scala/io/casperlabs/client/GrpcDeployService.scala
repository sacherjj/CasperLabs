package io.casperlabs.client

import java.io.Closeable
import java.security.KeyStore
import java.util.concurrent.TimeUnit

import cats.Id
import cats.data.StateT
import cats.mtl.implicits._
import cats.syntax.option._
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.info.{BlockInfo, DeployInfo}
import io.casperlabs.casper.consensus.state.Value
import io.casperlabs.client.configuration.ConnectOptions
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.util.HostnameTrustManager
import io.casperlabs.graphz
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.node.api.casper._
import io.casperlabs.node.api.control.{ControlGrpcMonix, ProposeRequest}
import io.grpc.ManagedChannel
import io.grpc.netty.{GrpcSslContexts, NegotiationType, NettyChannelBuilder}
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.ssl.util.SimpleTrustManagerFactory
import javax.net.ssl._
import monix.eval.Task
import monix.execution.Scheduler

import scala.util.Either

class GrpcDeployService(conn: ConnectOptions, scheduler: Scheduler)
    extends DeployService[Task]
    with Closeable {
  private val DefaultMaxMessageSize = 256 * 1024 * 1024

  private var externalConnected = false
  private var internalConnected = false

  private def makeChannel(port: Int): ManagedChannel = {
    val builder = NettyChannelBuilder
      .forAddress(conn.host, port)
      .maxInboundMessageSize(DefaultMaxMessageSize)
      //.eventLoopGroup(new NioEventLoopGroup(0, scheduler))
      // The above line is commented out on purpose; it causes
      // a problem on some systems. The reason for this is still
      // under investigation (see NODE-832).
      .executor(scheduler)

    // Decide whether to use SSL encryption.
    val maybeSslContext: Option[io.netty.handler.ssl.SslContext] =
      (conn.nodeId, conn.tlsApiCertificate, conn.useTls) match {
        case (None, None, None) | (_, _, Some(false)) =>
          none

        case (None, None, Some(true)) =>
          // We expect that the server will have a normal SSL certificate signed by a Root CA that
          // we can verify using the systems built-in certs. PAssing `null` as a file should do the same.
          GrpcSslContexts.forClient.build.some

        case (_, Some(crt), _) =>
          // Server side TLS only. The client will compare the server side cert with
          // what it has on disk. An example of obtaining a cert programmatically:
          // openssl s_client -showcerts -connect localhost:40401 </dev/null 2>/dev/null | openssl x509 -outform PEM > node.crt
          GrpcSslContexts.forClient
            .trustManager(crt)
            .build
            .some

        case (Some(_), _, _) =>
          // Server side TLS only. The client will compare the Node ID it's expecting the server
          // to have with the hash of the public key from the TLS certificate.
          val trustManagerFactory = new SimpleTrustManagerFactory {
            private val hostNameTrustManager = new HostnameTrustManager()

            def engineInit(keyStore: KeyStore): Unit                                 = {}
            def engineInit(managerFactoryParameters: ManagerFactoryParameters): Unit = {}
            def engineGetTrustManagers(): Array[TrustManager] =
              Array[TrustManager](hostNameTrustManager)
          }

          GrpcSslContexts.forClient
            .trustManager(trustManagerFactory)
            .build
            .some
      }

    maybeSslContext match {
      case None =>
        builder.usePlaintext
      case Some(sslContext) =>
        builder
          .negotiationType(NegotiationType.TLS)
          .sslContext(sslContext)
        // If the node ID is given then assume the cert is made for that name.
        conn.nodeId foreach { hash =>
          builder.overrideAuthority(hash) // So it doesn't expect for the host name to match the Common Name in the cert.
        }
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

  def deploy(d: consensus.Deploy): Task[Either[Throwable, String]] =
    casperServiceStub
      .deploy(DeployRequest().withDeploy(d))
      .map { _ =>
        val hash = Base16.encode(d.deployHash.toByteArray)
        s"Success! Deploy $hash deployed."
      }
      .attempt

  def propose(): Task[Either[Throwable, String]] =
    controlServiceStub
      .propose(ProposeRequest())
      .map(response => Base16.encode(response.blockHash.toByteArray))
      .attempt

  def showBlock(hash: String): Task[Either[Throwable, BlockInfo]] =
    casperServiceStub
      .getBlockInfo(GetBlockInfoRequest(hash, view = BlockInfo.View.FULL))
      .attempt

  def showDeploy(
      hash: String,
      bytesStandard: Boolean,
      json: Boolean
  ): Task[Either[Throwable, String]] =
    casperServiceStub
      .getDeployInfo(GetDeployInfoRequest(hash, view = DeployInfo.View.BASIC))
      .map(Printer.print(_, bytesStandard, json))
      .attempt

  def showDeploys(
      hash: String,
      bytesStandard: Boolean,
      json: Boolean
  ): Task[Either[Throwable, String]] =
    casperServiceStub
      .streamBlockDeploys(StreamBlockDeploysRequest(hash, view = DeployInfo.View.BASIC))
      .zipWithIndex
      .map {
        case (d, idx) =>
          if (json) {
            Printer.print(d, bytesStandard, json = true)
          } else {
            s"""
               |------------- deploy # $hash / $idx ---------------
               |${Printer.print(d, bytesStandard, json = false)}
               |---------------------------------------------------
               |""".stripMargin
          }
      }
      .toListL
      .map { xs =>
        val showLength =
          s"""
           |count: ${xs.length}
           |""".stripMargin

        xs.mkString("\n") + "\n" + showLength
      }
      .attempt

  def queryState(
      blockHash: String,
      keyVariant: String,
      keyValue: String,
      path: String
  ): Task[Either[Throwable, Value]] =
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
      .attempt

  def visualizeDag(depth: Int, showJustificationLines: Boolean): Task[Either[Throwable, String]] =
    casperServiceStub
      .streamBlockInfos(StreamBlockInfosRequest(depth = depth, view = BlockInfo.View.BASIC))
      .toListL
      .flatMap { infos =>
        type G[A] = StateT[Task, StringBuffer, A]
        implicit val ser = new graphz.StringSerializer[G]
        val state        = GraphzGenerator.dagAsCluster[G](infos, GraphConfig(showJustificationLines))
        state.runS(new StringBuffer).map(_.toString)
      }
      .attempt

  def showBlocks(
      depth: Int,
      bytesStandard: Boolean,
      json: Boolean
  ): Task[Either[Throwable, String]] =
    casperServiceStub
      .streamBlockInfos(StreamBlockInfosRequest(depth = depth, view = BlockInfo.View.BASIC))
      .map { bi =>
        if (json) {
          Printer.print(bi, bytesStandard, json = true)
        } else {
          s"""
           |------------- block @ ${bi.getSummary.rank} ---------------
           |${Printer.print(bi, bytesStandard, json = false)}
           |-----------------------------------------------------
           |""".stripMargin
        }
      }
      .toListL
      .map { xs =>
        val showLength =
          s"""
           |count: ${xs.length}
           |""".stripMargin

        xs.mkString("\n") + "\n" + showLength
      }
      .attempt

  override def close(): Unit = {
    if (internalConnected)
      internalChannel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
    if (externalConnected)
      externalChannel.shutdown().awaitTermination(10, TimeUnit.SECONDS)
  }
}
