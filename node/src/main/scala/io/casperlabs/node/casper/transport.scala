package io.casperlabs.node.casper

import cats._
import cats.data._
import cats.effect.{Concurrent, Resource, Sync}
import cats.effect.concurrent.{Ref, Semaphore}
import cats.mtl.MonadState
import cats.syntax.applicative._
import cats.syntax.apply._
import cats.syntax.flatMap._
import cats.syntax.functor._
import com.olegpy.meow.effects._
import io.casperlabs.blockstorage.util.fileIO.IOError
import io.casperlabs.blockstorage.util.fileIO.IOError.RaiseIOError
import io.casperlabs.blockstorage.{
  BlockDagFileStorage,
  BlockDagStorage,
  BlockStore,
  FileLMDBIndexBlockStore
}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.util.comm.CasperPacketHandler
import io.casperlabs.catscontrib.Catscontrib._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits.{bracketEitherTThrowable, taskLiftEitherT}
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm._
import io.casperlabs.comm.discovery._
import io.casperlabs.comm.rp.Connect.{ConnectionsCell, RPConfAsk, RPConfState}
import io.casperlabs.comm.rp._
import io.casperlabs.comm.transport._
import io.casperlabs.metrics.Metrics
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.node._
import io.casperlabs.p2p.effects._
import io.casperlabs.shared.PathOps._
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.{Task, TaskLike}
import monix.execution.Scheduler

import scala.concurrent.duration._

/** Create the Casper stack using the TransportLayer and CasperPacketHandler. */
package object transport {
  implicit def eitherTrpConfAsk(implicit ev: RPConfAsk[Task]): RPConfAsk[Effect] =
    new EitherTApplicativeAsk[Task, RPConf, CommError]

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
      safetyOracle: SafetyOracle[Effect],
      blockStore: BlockStore[Effect],
      blockDagStorage: BlockDagStorage[Effect],
      connectionsCell: ConnectionsCell[Task],
      nodeDiscovery: NodeDiscovery[Task],
      rpConfState: MonadState[Task, RPConf],
      multiParentCasperRef: MultiParentCasperRef[Effect],
      executionEngineService: ExecutionEngineService[Effect],
      scheduler: Scheduler
  ): Resource[Effect, Unit] = Resource {
    for {
      tcpConnections <- CachedConnections[Task, TcpConnTag](Task.catsAsync, metrics).toEffect

      commTmpFolder <- {
        val folder = conf.server.dataDir.resolve("tmp").resolve("comm")
        (folder.deleteDirectory[Task]() *> folder.pure[Task]).toEffect
      }

      transport = {
        effects.tcpTransportLayer(
          port,
          conf.tls.certificate,
          conf.tls.key,
          conf.server.maxMessageSize,
          conf.server.chunkSize,
          commTmpFolder
        )(grpcScheduler, log, metrics, tcpConnections)
      }
      transportEff = TransportLayer.eitherTTransportLayer(Monad[Task], log, transport)

      lab    <- LastApprovedBlock.of[Task].toEffect
      labEff = LastApprovedBlock.eitherTLastApprovedBlock[CommError, Task](Monad[Task], lab)

      rpConfAskEff = eitherTrpConfAsk(effects.rpConfAsk(rpConfState))
      peerNodeAsk  = effects.peerNodeAsk(rpConfState)

      connectionsCellEff: ConnectionsCell[Effect] = Cell.eitherTCell(Monad[Task], connectionsCell)

      timeEff: Time[Effect] = Time.eitherTTime(Monad[Task], effects.time)

      defaultTimeout = conf.server.defaultTimeout.millis

      casperPacketHandler <- CasperPacketHandler
                              .of[Effect](
                                conf.casper,
                                defaultTimeout,
                                executionEngineService,
                                _.value
                              )(
                                labEff,
                                metricsEff,
                                blockStore,
                                Cell.eitherTCell(Monad[Task], connectionsCell),
                                NodeDiscovery.eitherTNodeDiscovery(Monad[Task], nodeDiscovery),
                                TransportLayer.eitherTTransportLayer(Monad[Task], log, transport),
                                ErrorHandler[Effect],
                                rpConfAskEff,
                                safetyOracle,
                                Sync[Effect],
                                Concurrent[Effect],
                                timeEff,
                                logEff,
                                multiParentCasperRef,
                                blockDagStorage,
                                executionEngineService,
                                scheduler
                              )

      packetHandler = PacketHandler.pf[Effect](casperPacketHandler.handle)(
        Applicative[Effect],
        Log.eitherTLog(Monad[Task], log),
        ErrorHandler[Effect]
      )

      _ <- transportEff.receive(
            pm =>
              HandleMessages.handle[Effect](pm, defaultTimeout)(
                Sync[Effect],
                logEff,
                timeEff,
                metricsEff,
                transportEff,
                ErrorHandler[Effect],
                packetHandler,
                connectionsCellEff,
                rpConfAskEff
              ),
            blob => packetHandler.handlePacket(blob.sender, blob.packet).as(())
          )
      _ <- logEff.info(s"Started transport layer on port $port.")

      shutdown = {
        for {
          _     <- log.info("Shutting down transport layer, broadcasting DISCONNECT")
          local <- peerNodeAsk.ask
          msg   = ProtocolHelper.disconnect(local)
          _     <- transport.shutdown(msg)
        } yield ()
      }
    } yield () -> shutdown.toEffect
  }
}
