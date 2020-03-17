package io.casperlabs.comm.gossiping.downloadmanager

import cats._
import cats.effect.concurrent.Deferred
import cats.implicits._
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.WaitHandle
import io.casperlabs.metrics.Metrics

import scala.concurrent.duration.{Duration, FiniteDuration}

trait DownloadManagerTypes {

  /**
    * Marks a type used to uniquely refer to a [[Downloadable]] on scheduling.
    * May contain additional information for validation of the target [[Downloadable]].
    * E.g. [[io.casperlabs.casper.consensus.DeploySummary]] for [[DeployDownloadManager]] or [[io.casperlabs.casper.consensus.BlockSummary]] for [[BlockDownloadManager]]
    */
  type Handle

  /**
    * Unique identifier of [[Downloadable]].
    * E.g. [[io.casperlabs.casper.consensus.Block.blockHash]] or [[io.casperlabs.casper.consensus.Deploy.deployHash]].
    */
  type Identifier

  /**
    * Marks a type needed to be downloaded.
    * E.g. [[io.casperlabs.casper.consensus.Deploy]] for [[DeployDownloadManager]] or [[io.casperlabs.casper.consensus.Block]] for [[BlockDownloadManager]].
    */
  type Downloadable
}

/** Manages the download, validation, storing and gossiping of [[DownloadManagerTypes#Downloadable]].*/
trait DownloadManager[F[_]] extends DownloadManagerTypes {

  /** Schedule the download of a full [[Downloadable]] from the `source` node by a [[Handle]].
    * If `relay` is `true` then gossip it afterwards, if it's valid.
    * The returned `F[F[Unit]]` represents the success/failure of
    * the scheduling itself; it fails if there's any error accessing
    * the local backend, or if the scheduling cannot be carried out
    * due to missing dependencies (at this point we should have synced
    * already and the schedule should be called in topological order).
    *
    * The unwrapped `F[Unit]` _inside_ the `F[F[Unit]]` can be used to
    * wait until the actual download finishes, or results in an error. */
  def scheduleDownload(handle: Handle, source: Node, relay: Boolean): F[WaitHandle[F]]
}

trait DownloadManagerCompanion extends DownloadManagerTypes {

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics](implicit ev: Metrics.Source) =
    for {
      _ <- Metrics[F].incrementCounter("downloads_failed", 0)
      _ <- Metrics[F].incrementCounter("downloads_succeeded", 0)
      _ <- Metrics[F].incrementGauge("downloads_scheduled", 0)
      _ <- Metrics[F].incrementGauge("downloads_ongoing", 0)
      _ <- Metrics[F].incrementGauge("fetches_ongoing", 0)
    } yield ()

  type Feedback[F[_], A] = Deferred[F, Either[Throwable, A]]
  // Feedback about whether the download eventually succeeded.
  type DownloadFeedback[F[_]] = Feedback[F, Unit]
  // Feedback about whether the scheduling itself succeeded.
  type ScheduleFeedback[F[_]] = Feedback[F, DownloadFeedback[F]]

  /** Interface to the storage and consensus dependencies and callbacks. */
  trait Backend[F[_]] {
    def contains(identifier: Identifier): F[Boolean]
    def validate(downloadable: Downloadable): F[Unit]
    def store(downloadable: Downloadable): F[Unit]

    /** Notify about new downloadables we were told about but haven't acquired yet. */
    def onScheduled(handle: Handle): F[Unit]

    /** Notify about a new downloadable we downloaded, verified and stored. */
    def onDownloaded(identifier: Identifier): F[Unit]
  }

  /** Messages the Download Manager uses inside its scheduler "queue". */
  sealed trait Signal[F[_]] extends Product with Serializable
  object Signal {
    case class Download[F[_]](
        handle: Handle,
        source: Node,
        relay: Boolean,
        scheduleFeedback: ScheduleFeedback[F]
    ) extends Signal[F]
    case class DownloadSuccess[F[_]](identifier: Identifier)                extends Signal[F]
    case class DownloadFailure[F[_]](identifier: Identifier, ex: Throwable) extends Signal[F]
  }

  /** Keep track of download items. */
  case class Item[F[_]](
      handle: Handle,
      // Any node that told us it has this downloadable.
      sources: Set[Node],
      // Whether we'll have to relay at the end.
      relay: Boolean,
      // Other downloadables we have to download before this one.
      dependencies: Set[Identifier],
      isDownloading: Boolean = false,
      isError: Boolean = false,
      // Keep returning the same Deferred until one attempt to download is finished.
      // The next schedule will create a new Deferred that will be completed anew.
      maybeWatcher: Option[DownloadFeedback[F]]
  ) {
    val canStart: Boolean = !isDownloading && dependencies.isEmpty
  }

  case class RetriesConf(
      maxRetries: Int Refined NonNegative,
      initialBackoffPeriod: FiniteDuration,
      backoffFactor: Double Refined GreaterEqual[W.`1.0`.T]
  )
  object RetriesConf {
    val noRetries = RetriesConf(0, Duration.Zero, 1.0)
  }

  /** All dependencies that need to be downloaded before a downloadable. */
  def dependencies(handle: Handle): Seq[Identifier]
}
