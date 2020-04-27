package io.casperlabs.comm.gossiping.downloadmanager

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Deploy, DeploySummary}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping._
import io.casperlabs.comm.gossiping.downloadmanager.DeployDownloadManagerImpl.RetriesConf
import io.casperlabs.comm.gossiping.relaying.DeployRelaying
import io.casperlabs.crypto.codec.ByteArraySyntax
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import monix.execution.Scheduler
import monix.tail.Iterant
import scala.util.Try

trait DeployDownloadManager[F[_]] extends DownloadManager[F] {
  override type Handle       = DeploySummary
  override type Identifier   = ByteString
  override type Downloadable = Deploy
}

object DeployDownloadManagerImpl extends DownloadManagerCompanion {
  override type Handle       = DeploySummary
  override type Identifier   = ByteString
  override type Downloadable = Deploy

  implicit val metricsSource: Metrics.Source =
    Metrics.Source(DeployGossipingMetricsSource, "DownloadManager")

  /** Start the download manager. */
  def apply[F[_]: ContextShift: Concurrent: Log: Timer: Metrics](
      maxParallelDownloads: Int,
      connectToGossip: GossipService.Connector[F],
      backend: Backend[F],
      relaying: DeployRelaying[F],
      retriesConf: RetriesConf,
      egressScheduler: Scheduler
  ): Resource[F, DeployDownloadManager[F]] =
    Resource.make {
      for {
        isShutdown <- Ref.of(false)
        itemsRef   <- Ref.of(Map.empty[ByteString, Item[F]])
        workersRef <- Ref.of(Map.empty[ByteString, Fiber[F, Unit]])
        semaphore  <- Semaphore[F](maxParallelDownloads.toLong)
        signal     <- MVar[F].empty[Signal[F]]
        manager = new DeployDownloadManagerImpl[F](
          this,
          isShutdown,
          itemsRef,
          workersRef,
          semaphore,
          signal,
          connectToGossip,
          backend,
          relaying,
          retriesConf,
          egressScheduler
        )
        managerLoop <- manager.run.start
      } yield (isShutdown, workersRef, managerLoop, manager)
    } {
      case (isShutdown, workersRef, managerLoop, _) =>
        for {
          _       <- Log[F].info("Shutting down the Download Manager...")
          _       <- isShutdown.set(true)
          _       <- managerLoop.cancel.attempt
          workers <- workersRef.get
          _       <- workers.values.toList.traverse(_.cancel.attempt)
        } yield ()
    } map {
      case (_, _, _, manager) => manager
    }

  override def dependencies(summary: DeploySummary): Seq[ByteString] =
    summary.getHeader.dependencies
}

class DeployDownloadManagerImpl[F[_]](
    val companion: DeployDownloadManagerImpl.type,
    val isShutdown: Ref[F, Boolean],
    // Keep track of active downloads and dependencies.
    val itemsRef: Ref[F, Map[ByteString, DeployDownloadManagerImpl.Item[F]]],
    // Keep track of ongoing downloads so we can cancel them.
    val workersRef: Ref[F, Map[ByteString, Fiber[F, Unit]]],
    // Limit parallel downloads.
    val semaphore: Semaphore[F],
    // Single item control signals for the manager loop.
    val signal: MVar[F, DeployDownloadManagerImpl.Signal[F]],
    // Establish gRPC connection to another node.
    val connectToGossip: GossipService.Connector[F],
    val backend: DeployDownloadManagerImpl.Backend[F],
    val relaying: DeployRelaying[F],
    val retriesConf: RetriesConf,
    val egressScheduler: Scheduler
)(
    implicit
    override val H: ContextShift[F],
    override val C: Concurrent[F],
    override val T: Timer[F],
    override val L: Log[F],
    override val M: Metrics[F]
) extends DeployDownloadManager[F]
    with DownloadManagerImpl[F] {
  override val kind                       = "deploy"
  override implicit val E: Eq[Identifier] = Eq.instance((a: ByteString, b: ByteString) => a == b)
  override implicit val S: Show[Identifier] =
    Show.show((bs: ByteString) => bs.toByteArray.base16Encode)

  override def toByteString(bs: ByteString) = bs

  override def extractIdFromHandle(summary: DeploySummary) = summary.deployHash

  override def extractIdFromDownloadable(deploy: Deploy) = deploy.deployHash

  override def fetch(source: Node, deployHash: ByteString) = {
    val itF = connectToGossip(source).map { stub =>
      val req = StreamDeploysChunkedRequest(
        deployHashes = List(deployHash),
        acceptedCompressionAlgorithms = Seq("lz4")
      )
      stub.streamDeploysChunked(req)
    }
    Iterant.liftF(itF).flatten
  }

  override def tryParseDownloadable(bytes: Array[Byte]) =
    Try(Deploy.parseFrom(bytes))
}
