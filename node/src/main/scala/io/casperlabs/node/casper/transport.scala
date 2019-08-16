package io.casperlabs.node.casper

import cats._
import cats.effect.{Concurrent, Resource, Sync}
import cats.instances.option._
import cats.instances.unit._
import cats.mtl.{ApplicativeAsk, MonadState}
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.foldable._
import cats.syntax.functor._
import com.github.ghik.silencer.silent
import io.casperlabs.blockstorage.{BlockStorage, DagStorage}
import io.casperlabs.casper.LastApprovedBlock.LastApprovedBlock
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.deploybuffer.DeployBuffer
import io.casperlabs.casper.util.comm.CasperPacketHandler
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.rp.Connect.{Connections, ConnectionsCell, RPConfAsk}
import io.casperlabs.comm.rp._
import io.casperlabs.comm.transport._
import io.casperlabs.metrics.Metrics
import io.casperlabs.node._
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.p2p.effects._
import io.casperlabs.shared.PathOps._
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler

import scala.concurrent.duration._

/** Create the Casper stack using the TransportLayer and CasperPacketHandler. */
package object transport {
  def eitherTrpConfAsk(implicit ev: RPConfAsk[Task]): RPConfAsk[Effect] =
    new EitherTApplicativeAsk[Task, RPConf, CommError]

  //@silent("is never used")
  def apply(
      port: Int,
      conf: Configuration,
      grpcScheduler: Scheduler
  )(
      implicit
      log: Log[Task],
      logEff: Log[Effect],
      metrics: Metrics[Task],
      metricsEff: Metrics[Effect],
      safetyOracle: FinalityDetector[Effect],
      blockStorage: BlockStorage[Effect],
      dagStorage: DagStorage[Effect],
      connectionsCell: ConnectionsCell[Task],
      nodeDiscovery: NodeDiscovery[Task],
      rpConfState: MonadState[Task, RPConf],
      multiParentCasperRef: MultiParentCasperRef[Effect],
      executionEngineService: ExecutionEngineService[Effect],
      finalizationHandler: LastFinalizedBlockHashContainer[Effect],
      filesApiEff: FilesAPI[Effect],
      deployBuffer: DeployBuffer[Effect],
      validation: Validation[Effect],
      scheduler: Scheduler
  ): Resource[Effect, Unit] = Resource {
    implicit val rpConfAsk = effects.rpConfAsk(rpConfState)
    implicit val timeEff   = Time[Effect]
    for {
      tcpConnections <- CachedConnections[Task, TcpConnTag](Task.catsAsync, metrics).toEffect

      commTmpFolder <- {
        val folder = conf.server.dataDir.resolve("tmp").resolve("comm")
        (folder.deleteDirectory[Task]().whenA(folder.toFile.exists()) *> folder.pure[Task]).toEffect
      }

      implicit0(transport: TransportLayer[Task]) = {
        effects.tcpTransportLayer(
          port,
          conf.tls.certificate,
          conf.tls.key,
          conf.server.maxMessageSize,
          conf.server.chunkSize,
          commTmpFolder
        )(grpcScheduler, log, metrics, tcpConnections)
      }
      implicit0(transportEff: TransportLayer[Effect]) = TransportLayer
        .eitherTTransportLayer[Task]

      implicit0(lab: LastApprovedBlock[Task]) <- LastApprovedBlock.of[Task].toEffect

      implicit0(labEff: LastApprovedBlock[Effect]) = LastApprovedBlock
        .eitherTLastApprovedBlock[CommError, Task]

      peerNodeAsk = effects.peerNodeAsk(rpConfState)

      defaultTimeout = conf.server.defaultTimeout

      casperPacketHandler <- CasperPacketHandler
                              .of[Effect](
                                conf.casper,
                                defaultTimeout,
                                _.value
                              )

      implicit0(packetHandler: PacketHandler[Effect]) = PacketHandler
        .pf[Effect](casperPacketHandler.handle)(
          Applicative[Effect],
          Log.eitherTLog(Monad[Task], log),
          ErrorHandler[Effect]
        )

      // Start receiving messages from peers.
      _ <- transportEff.receive(
            pm => HandleMessages.handle[Effect](pm, defaultTimeout),
            blob => packetHandler.handlePacket(blob.sender, blob.packet).as(())
          )
      _ <- logEff.info(s"Started transport layer on port $port.")

      // Start the loop that keeps the ConnectionCell up to date.
      loop <- Concurrent[Effect].start {
               (for {
                 _ <- timeEff.sleep(1.minute)
                 _ <- dynamicIpCheck(conf)(
                       log,
                       connectionsCell,
                       peerNodeAsk,
                       rpConfState,
                       rpConfAsk,
                       transport,
                       metrics
                     ).toEffect
                 _ <- refreshConnections
               } yield ()).forever
             }

      shutdown = {
        for {
          _     <- log.info("Shutting down transport layer, broadcasting DISCONNECT")
          local <- peerNodeAsk.ask
          msg   = ProtocolHelper.disconnect(local)
          _     <- transport.shutdown(msg)
        } yield ()
      }
    } yield () -> loop.cancel.attempt.void *> shutdown.toEffect
  }

  private def refreshConnections(
      implicit
      time: Time[Effect],
      log: Log[Effect],
      metrics: Metrics[Effect],
      connectionsCell: ConnectionsCell[Effect],
      rpConfAsk: ApplicativeAsk[Effect, RPConf],
      transport: TransportLayer[Effect],
      nodeDiscovery: NodeDiscovery[Effect]
  ): Effect[Unit] =
    for {
      _ <- Connect.clearConnections[Effect]
      _ <- Connect.findAndConnect[Effect](Connect.connect[Effect])
    } yield ()

  // TODO: The check uses the TransportLayer to disconnect from all peers and reconnect with a
  // new hostname if the IP address changes. I'm not sure how this works with the certificate,
  // given that the certificate is checked against the remote peer host name.
  // We should move the disconnect to Kademlia so we don't need the TransportLayer for this.
  private def dynamicIpCheck(
      conf: Configuration
  )(
      implicit
      log: Log[Task],
      connectionsCell: ConnectionsCell[Task],
      peerNodeAsk: ApplicativeAsk[Task, Node],
      rpConfState: MonadState[Task, RPConf],
      rpConfAsk: ApplicativeAsk[Task, RPConf],
      transport: TransportLayer[Task],
      metrics: Metrics[Task]
  ): Task[Unit] =
    (for {
      local <- peerNodeAsk.ask
      newLocal <- WhoAmI
                   .checkLocalPeerNode[Task](conf.server.port, conf.server.kademliaPort, local)
      _ <- newLocal.foldMap { pn =>
            Connect.resetConnections[Task].flatMap(kp(rpConfState.modify(_.copy(local = pn))))
          }
    } yield ()).whenA(conf.server.dynamicHostAddress)
}
