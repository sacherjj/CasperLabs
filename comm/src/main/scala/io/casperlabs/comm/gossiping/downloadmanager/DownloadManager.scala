package io.casperlabs.comm.gossiping.downloadmanager

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.google.protobuf.ByteString
import com.google.common.cache.{Cache, CacheBuilder}
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.catscontrib.effect.implicits.fiberSyntax
import io.casperlabs.comm.GossipError
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping.relaying.Relaying
import io.casperlabs.comm.gossiping.{Chunk, GossipService, WaitHandle}
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.shared.Log._
import io.casperlabs.shared.{Compression, Log}
import monix.execution.Scheduler
import monix.tail.Iterant

import scala.annotation.tailrec
import scala.collection.immutable.Queue
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.Try
import java.util.concurrent.TimeUnit

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

trait DownloadManagerCompanion extends DownloadManagerTypes {

  implicit val metricsSource: Metrics.Source

  // to use .minimumOption on collections of (Node, Int)
  implicit val sourcesAndCountersOrder: Order[(Node, Int)] = Order[Int].contramap[(Node, Int)](_._2)

  /** Export base 0 values so we have non-empty series for charts. */
  def establishMetrics[F[_]: Monad: Metrics] =
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

    /** Notify about new downloadables we were told about but haven't acquired yet. Called once per downloadable.. */
    def onScheduled(handle: Handle): F[Unit]

    /** Notify about new source of a downloadable we were told about but haven't acquired yet. */
    def onScheduled(handle: Handle, source: Node): F[Unit]

    /** Notify about a new downloadable we downloaded, verified and stored. */
    def onDownloaded(identifier: Identifier): F[Unit]

    /** Notify about a downloadable having exhausted all its retries and ultimately failed.
      * It won't be reattempted until it's rescheduled.
      */
    def onFailed(identifier: Identifier): F[Unit]
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

  /** When we first get a notificaton about a new item, we add it as new.
    * Subsequent sources can be added with `scheduleDownload` individually,
    * however that would require first syncing with them to the their dependencies
    * as well, so the new source can be added to all missing items, not just the tip.
    * The `addSource` method makes this easier when we know the item is already scheduled
    * by recursively adding the new source to all dependencies we already know about.
    *
    * This method collects the the ancestors of an item we already have where the
    * peer that is now telling us about it was not yet available as a source.
    */
  def collectAncestorsForNewSource[F[_]](
      items: Map[Identifier, Item[F]],
      id: Identifier,
      source: Node
  ): Set[Identifier] = {

    @tailrec
    def loop(
        acc: Set[Identifier],
        queue: Queue[Identifier]
    ): Set[Identifier] =
      queue.dequeueOption match {
        case None => acc
        case Some((id, queue)) if acc(id) =>
          loop(acc, queue)
        case Some((id, queue)) =>
          items.get(id) match {
            case Some(item) if item.isError || !item.sources(source) =>
              loop(acc + id, queue ++ item.dependencies)
            case _ =>
              loop(acc, queue)
          }
      }

    loop(Set.empty, Queue(id)) - id
  }

  /** Collect every scheduled item that depends on something.
    * This is used only rarely, when an item fails to download completely.
    */
  def collectDescendants[F[_]](
      items: Map[Identifier, Item[F]],
      id: Identifier
  ): Set[Identifier] = {

    @tailrec
    def loop(
        acc: Set[Identifier],
        queue: Queue[Identifier]
    ): Set[Identifier] =
      queue.dequeueOption match {
        case None => acc
        case Some((id, queue)) if acc(id) =>
          loop(acc, queue)

        case Some((id, queue)) =>
          val dependants = items.collect {
            case (hash, dep) if dep.dependencies contains id => hash
          }
          loop(acc + id, queue ++ dependants)
      }

    loop(Set.empty, Queue(id)) - id
  }

  case class DownloadedCache[F[_]: Sync, K](
      cache: Cache[K, DownloadedCache.Marker.type]
  ) {
    def put(identifier: K): F[Unit] =
      Sync[F].delay(cache.put(identifier, DownloadedCache.Marker))

    def contains(identifier: K): F[Boolean] =
      Sync[F].delay(Option(cache.getIfPresent(identifier)).isDefined)
  }
  object DownloadedCache {
    object Marker
    def apply[F[_]: Sync, K <: Object](expiry: FiniteDuration = 1.hour): F[DownloadedCache[F, K]] =
      Sync[F].delay {
        DownloadedCache(
          CacheBuilder
            .newBuilder()
            .expireAfterWrite(expiry.toSeconds, TimeUnit.SECONDS)
            .build[K, Marker.type]()
        )
      }
  }
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

  /** Check if an item has already been scheduled for download. */
  def isScheduled(id: Identifier): F[Boolean]

  /** Check if an item has been recently downloaded. */
  def wasDownloaded(id: Identifier): F[Boolean]

  /** Add a new source to an existing schedule and all of its dependencies. */
  def addSource(id: Identifier, source: Node): F[WaitHandle[F]]
}

trait DownloadManagerImpl[F[_]] extends DownloadManager[F] { self =>
  // Workaround to reuse the existing code
  val companion: DownloadManagerCompanion

  override type Handle       = companion.Handle
  override type Identifier   = companion.Identifier
  override type Downloadable = companion.Downloadable

  import companion._

  // Can't specify context bounds in traits
  implicit val H: ContextShift[F]
  implicit val C: Concurrent[F]
  implicit val T: Timer[F]
  implicit val L: Log[F]
  implicit val M: Metrics[F]
  implicit val E: Eq[Identifier]
  implicit val S: Show[Identifier]
  // For graceful shutdown
  val isShutdown: Ref[F, Boolean]
  // Keep track of active downloads and dependencies.
  val itemsRef: Ref[F, Map[Identifier, companion.Item[F]]]
  // Keep track of ongoing downloads so we can cancel them.
  val workersRef: Ref[F, Map[Identifier, Fiber[F, Unit]]]
  // Keep track of recently downloaded items.
  val recentlyDownloaded: DownloadedCache[F, Identifier]
  // Limit parallel downloads.
  val semaphore: Semaphore[F]
  // Single item control signals for the manager loop.
  val signal: MVar[F, companion.Signal[F]]
  // Establish gRPC connection to another node.
  val connectToGossip: GossipService.Connector[F]
  val egressScheduler: Scheduler
  val backend: companion.Backend[F]
  val relaying: Relaying[F]
  val retriesConf: companion.RetriesConf
  // Description of 'downloadables' to use in logs
  val kind: String

  def toByteString(id: Identifier): ByteString

  implicit class IdentifierOps(id: Identifier) {
    def toByteString: ByteString = self.toByteString(id)
  }

  // 'From*' because of a compilation error `have same type after erasure: (handle: Object)Object`
  def extractIdFromHandle(handle: Handle): Identifier
  def extractIdFromDownloadable(downloadable: Downloadable): Identifier

  implicit class HandleOps(h: Handle) {
    def id: Identifier = extractIdFromHandle(h)
  }

  implicit class DownloadableOps(d: Downloadable) {
    def id: Identifier = extractIdFromDownloadable(d)
  }

  /** Get one downloadable as a stream of chunks from a source. */
  def fetch(source: Node, id: Identifier): Iterant[F, Chunk]

  /** Parse the downloaded and restored bytes into an Downloadable. */
  def tryParseDownloadable(bytes: Array[Byte]): Try[Downloadable]

  private def ensureNotShutdown: F[Unit] =
    isShutdown.get.ifM(
      Sync[F]
        .raiseError(new java.lang.IllegalStateException("Download Manager already shut down.")),
      Sync[F].unit
    )

  private def makeDownloadFeedback =
    Deferred[F, Either[Throwable, Unit]]

  private def makeScheduleFeedback =
    Deferred[F, Either[Throwable, Deferred[F, Either[Throwable, Unit]]]]

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
  override def scheduleDownload(handle: Handle, source: Node, relay: Boolean): F[WaitHandle[F]] =
    for {
      // Fail rather than block forever.
      _ <- ensureNotShutdown
      // Feedback about whether we successfully scheduled the item.
      sf <- makeScheduleFeedback
      _  <- signal.put(Signal.Download(handle, source, relay, sf))
      df <- Sync[F].rethrow(sf.get)
    } yield Sync[F].rethrow(df.get)

  /** Try to add an alternative source to an existing download.
    * The caller is supposed to check before with `isScheduled` whether this makes sense.
    * If the item is no longer scheduled (because it has been downloaded),
    * then return a completed wait handle.
    */
  override def addSource(id: Identifier, source: Node): F[WaitHandle[F]] =
    itemsRef.get.flatMap {
      case items if !items.contains(id) =>
        ().pure[F].pure[F]

      case items =>
        def schedule(id: Identifier) =
          scheduleDownload(items(id).handle, source, relay = false)

        val ancestors = collectAncestorsForNewSource(items, id, source)

        ancestors.toList.traverse(schedule) >> schedule(id)
    }

  /** Run the manager loop which listens to signals and starts workers when it can. */
  def run: F[Unit] =
    signal.take.flatMap {
      case Signal.Download(handle, source, relay, scheduleFeedback) =>
        // At this point we should have already synced and only scheduled things to which we know how to get.
        val start =
          isDownloaded(handle.id).ifM(
            for {
              downloadFeedback <- makeDownloadFeedback
              _                <- downloadFeedback.complete(Right(()))
            } yield downloadFeedback,
            for {
              _                        <- ensureNoMissingDependencies(handle)
              items                    <- itemsRef.get
              itemAndFeedback          <- mergeItem(items, handle, source, relay)
              (item, downloadFeedback) = itemAndFeedback

              // Notify the rest of the system if this is the first time we schedule this item
              // or if we're adding a new source to an item that already existed,
              // or if the item has been scheduled again after failure and will now be retried.
              existingItem = items.get(handle.id)
              _ <- backend
                    .onScheduled(handle)
                    .forkAndLog
                    .whenA(item.canStart || existingItem.isEmpty)
              _ <- backend
                    .onScheduled(handle, source)
                    .forkAndLog
                    .whenA(item.canStart || existingItem.forall(!_.sources(source)))

              _ <- itemsRef.update(_ + (handle.id -> item))
              _ <- if (item.canStart) startWorker(item) else Sync[F].unit
              _ <- setScheduledGauge()
            } yield downloadFeedback
          )
        // Report any startup errors so the caller knows something's fatally wrong, then carry on.
        start.attempt.flatMap(scheduleFeedback.complete) >> run

      case Signal.DownloadSuccess(id) =>
        val finish = for {
          _ <- workersRef.update(_ - id)
          // Remember that we downloaded this item without having to hit the backend for a while.
          _ <- recentlyDownloaded.put(id)
          // Remove the item and check what else we can download now.
          next <- itemsRef.modify { items =>
                   val item = items(id)
                   val dependants = items.collect {
                     case (hash, dep) if dep.dependencies contains id =>
                       hash -> dep.copy(dependencies = dep.dependencies - id)
                   }
                   val startables = dependants.collect {
                     case (_, dep) if dep.canStart => dep
                   }
                   (items ++ dependants - id, item.maybeWatcher.toList -> startables.toList)
                 }
          (watchers, startables) = next
          _                      <- watchers.traverse(_.complete(Right(())).attempt.void)
          _                      <- backend.onDownloaded(id).forkAndLog
          _                      <- startables.traverse(startWorker)
          _                      <- setScheduledGauge()
        } yield ()

        finish.attemptAndLog("An error occurred when handling DownloadSuccess signal.") >> run

      case Signal.DownloadFailure(id, ex) =>
        val finish = for {
          _ <- workersRef.update(_ - id)
          watchers <- itemsRef.modify { items =>
                       // Mark every descendant as faulty, so we know we have to retry them if re-scheduled.
                       val descendants = collectDescendants(items, id)
                       val tombstones: Seq[(Identifier, Item[F])] =
                         (descendants + id).toSeq.map { id =>
                           val tombstone: Item[F] = items(id).copy(
                             isDownloading = false,
                             isError = true,
                             maybeWatcher = None
                           )
                           id -> tombstone
                         }
                       (items ++ tombstones, items(id).maybeWatcher.toList)
                     }
          // Tell whoever scheduled it before that it's over.
          _ <- watchers.traverse(_.complete(Left(ex)).attempt.void)
          _ <- backend.onFailed(id).forkAndLog
          _ <- setScheduledGauge()
        } yield ()

        finish.attemptAndLog("An error occurred when handling DownloadFailure signal.") >> run
    }

  // Indicate how many items we have in the queue.
  private def setScheduledGauge() =
    for {
      items <- itemsRef.get
      _     <- Metrics[F].setGauge("downloads_scheduled", items.size.toLong)
    } yield ()

  /** Either create a new item or add the source to an existing one.
    * Return the deferred that will eventually signal the completion of the task.
    */
  private def mergeItem(
      items: Map[Identifier, Item[F]],
      handle: Handle,
      source: Node,
      relay: Boolean
  ): F[(Item[F], DownloadFeedback[F])] =
    makeDownloadFeedback flatMap { freshDownloadFeedback =>
      items.get(handle.id) map { existing =>
        Sync[F].pure {
          // If it was already scheduled we keep the existing Deferred.
          val downloadFeedback = existing.maybeWatcher getOrElse freshDownloadFeedback
          existing.copy(
            sources = existing.sources + source,
            relay = existing.relay || relay,
            maybeWatcher = Some(downloadFeedback),
            // A new ping should mean this item can now be considered again;
            // also stops `addSource` from traversing it repeatedly.
            isError = false
          ) -> downloadFeedback
        }
      } getOrElse {
        // Collect which dependencies have already been downloaded.
        dependencies(handle).toList.traverse { hash =>
          if (items.contains(hash)) Sync[F].pure(hash -> false)
          else isDownloaded(hash).map(hash            -> _)
        } map { deps =>
          val pending = deps.filterNot(_._2).map(_._1).toSet
          Item(
            handle,
            Set(source),
            relay,
            dependencies = pending,
            maybeWatcher = Some(freshDownloadFeedback)
          ) -> freshDownloadFeedback
        }
      }
    }

  /** Check that we either have all dependencies already downloaded or scheduled. */
  private def ensureNoMissingDependencies(handle: Handle): F[Unit] =
    dependencies(handle).toList.traverse { id =>
      isScheduled(id).ifM((id -> true).pure[F], isDownloaded(id).map(id -> _))
    } map {
      _.filterNot(_._2).map(_._1)
    } flatMap {
      case Nil =>
        Sync[F].unit
      case missing =>
        Sync[F].raiseError(
          GossipError.MissingDependencies(handle.id.toByteString, missing.map(_.toByteString))
        )
    }

  override def isScheduled(id: Identifier): F[Boolean] =
    itemsRef.get.map(_ contains id)

  private def isDownloaded(id: Identifier): F[Boolean] =
    wasDownloaded(id).ifM(true.pure[F], backend.contains(id))

  override def wasDownloaded(id: Identifier): F[Boolean] =
    recentlyDownloaded.contains(id)

  /** Kick off the download and mark the item. */
  private def startWorker(item: Item[F]): F[Unit] =
    for {
      _ <- itemsRef.update(_ + (item.handle.id -> item.copy(isDownloading = true)))
      worker <- Concurrent[F].start {
                 // Indicate how many items are currently being attempted, including their retry wait time.
                 download(item.handle.id).timerGauge("downloads")
               }
      _ <- workersRef.update(_ + (item.handle.id -> worker))
    } yield ()

  // Just say which identifier to download, try all possible sources.
  private def download(id: Identifier): F[Unit] = {
    val success                = signal.put(Signal.DownloadSuccess(id))
    def failure(ex: Throwable) = signal.put(Signal.DownloadFailure(id, ex))

    def throttledFetchAndRestore(source: Node, handle: Handle): F[Downloadable] =
      // Indicate how many fetches we are trying to do at a time. If it's larger then the semaphore
      // we configured we'd know where we'd have to raise it to allow maximum throughput.
      semaphore
        .withPermit {
          ContextShift[F].evalOn(egressScheduler) {
            // Measure an individual download.
            fetchAndRestore(source, handle).timerGauge("restore")
          }
        }
        .timerGauge("fetches") // Measure with wait time.

    def tryDownload(handle: Handle, source: Node, relay: Boolean) =
      for {
        downloadable <- throttledFetchAndRestore(source, handle)
        _            <- backend.validate(downloadable)
        _            <- backend.store(downloadable)
        _            <- relaying.relay(List(handle.id.toByteString)).whenA(relay)
        _            <- success
        _            <- Metrics[F].incrementCounter("downloads_succeeded")
      } yield ()

    // Try to download until we succeed or give up.
    def loop(counterPerSource: Map[Node, Int], lastError: Option[Throwable]): F[Unit] =
      itemsRef.get.map(_(id)).flatMap { item =>
        val prevSources           = counterPerSource.keySet
        val potentiallyNewSources = item.sources
        if (potentiallyNewSources.size > prevSources.size) {
          val newSources = potentiallyNewSources.map((_, 0)).toMap |+| counterPerSource
          // We've got new sources during download, so need to recheck the least tried source
          loop(newSources, lastError)
        } else {
          // No sources changes, can continue.
          // Finding least tried source.
          counterPerSource.toList.minimumOption match {
            case None =>
              failure(new IllegalStateException("No source to download from."))

            case Some((_, attempt)) if attempt > retriesConf.maxRetries.toInt =>
              // Least tried source is tried more than allowed => don't have sources to try anymore.
              // Let's just return the last error, unwrapped, so callers don't have to anticipate
              // whether this component is going to do retries or not.
              // Alternatively we could use `Throwable.addSupressed` to collect all of them.
              val countersSum = counterPerSource.values.sum
              val sourcesNum  = counterPerSource.size
              val ex          = lastError.getOrElse(new IllegalStateException("No errors captured."))
              Log[F]
                .error(
                  s"Could not download ${kind} ${id.show -> "id"} from any of the sources; tried $countersSum requests for $sourcesNum sources: $ex"
                ) *> failure(ex)

            case Some((source, attempt)) =>
              val duration = if (attempt > 0) {
                retriesConf.initialBackoffPeriod *
                  math.pow(retriesConf.backoffFactor, (attempt - 1).toDouble)
              } else {
                Duration.Zero
              }
              duration match {
                case delay: FiniteDuration =>
                  Log[F].debug(
                    s"Scheduling download of ${kind} ${id.show -> "id"} from ${source.show -> "peer"} later, $attempt, $delay"
                  ) *>
                    Timer[F].sleep(delay) *>
                    tryDownload(item.handle, source, item.relay).recoverWith {
                      case NonFatal(ex) =>
                        val nextAttempt = attempt + 1
                        Metrics[F].incrementCounter("downloads_failed") *>
                          Log[F].warn(
                            s"Retrying download of ${kind} ${id.show -> "id"} from other sources, failed source: ${source.show -> "peer"}, prev $attempt: $ex"
                          ) >>
                          loop(counterPerSource.updated(source, nextAttempt), ex.some)
                    }
                case _: Duration.Infinite => sys.error("Unreachable")
              }
          }
        }
      }

    itemsRef.get.map(_(id)).flatMap { item =>
      // Make sure the manager knows we're done, even if we fail unexpectedly.
      loop(item.sources.map((_, 0)).toMap, lastError = none[Throwable]) recoverWith {
        case NonFatal(ex) => failure(ex)
      }
    }
  }

  /** Download from the source node and decompress it. */
  protected def fetchAndRestore(source: Node, handle: Handle): F[Downloadable] =
    for {
      content      <- restore(source, fetch(source, handle.id))
      downloadable <- Sync[F].fromTry(tryParseDownloadable(content))
      _            <- checkId(source, downloadable, handle.id)
    } yield downloadable

  /** Fetches specified chunks, accumulates and validates them. */
  protected def restore(source: Node, chunks: Iterant[F, Chunk]): F[Array[Byte]] =
    for {
      acc <- chunks.foldWhileLeftL(ChunksAcc(None, 0, Nil, None)) {
              case (acc, chunk) if acc.header.isEmpty && chunk.content.isHeader =>
                val header = chunk.getHeader
                header.compressionAlgorithm match {
                  case "" | "lz4" =>
                    Left(acc.copy(header = Some(header)))
                  case other =>
                    Right(
                      acc.invalid(source, s"Chunks compressed with unexpected algorithm: $other")
                    )
                }

              case (acc, chunk) if acc.header.nonEmpty && chunk.content.isHeader =>
                Right(acc.invalid(source, "Chunks contained a second header."))

              case (acc, _) if acc.header.isEmpty =>
                Right(acc.invalid(source, "Chunks did not start with a header."))

              case (acc, chunk) if chunk.getData.isEmpty =>
                Right(acc.invalid(source, "Chunks contained empty data frame."))

              case (acc, chunk)
                  if acc.totalSizeSoFar + chunk.getData.size > acc.header.get.contentLength =>
                Right(acc.invalid(source, "Chunks are exceeding the promised content length."))

              case (acc, chunk) =>
                Left(acc.append(chunk.getData))
            }
      content <- if (acc.error.nonEmpty) {
                  Sync[F].raiseError[Array[Byte]](acc.error.get)
                } else if (acc.header.isEmpty) {
                  Sync[F]
                    .raiseError[Array[Byte]](invalidChunks(source, "Did not receive a header."))
                } else {
                  val header  = acc.header.get
                  val content = acc.chunks.toArray.reverse.flatMap(_.toByteArray)
                  if (header.compressionAlgorithm.isEmpty) {
                    Sync[F].pure(content)
                  } else {
                    Compression
                      .decompress(content, header.originalContentLength)
                      .fold(
                        Sync[F]
                          .raiseError[Array[Byte]](
                            invalidChunks(source, "Could not decompress chunks.")
                          )
                      )(Sync[F].pure(_))
                  }
                }
    } yield content

  /** Keep track of how much we have downloaded so far and cancel the stream if it goes over the promised size. */
  case class ChunksAcc(
      header: Option[Chunk.Header],
      totalSizeSoFar: Int,
      chunks: List[ByteString],
      error: Option[GossipError]
  ) {
    def invalid(source: Node, msg: String): ChunksAcc =
      copy(error = Some(GossipError.InvalidChunks(msg, source)))

    def append(data: ByteString): ChunksAcc =
      copy(totalSizeSoFar = totalSizeSoFar + data.size, chunks = data :: chunks)
  }

  protected def checkId(source: Node, downloadable: Downloadable, id: Identifier) =
    Sync[F]
      .raiseError(
        invalidChunks(
          source,
          s"Retrieved ${kind} has unexpected ${downloadable.id.show -> "id"}."
        )
      )
      .whenA(downloadable.id =!= id)

  protected def invalidChunks(source: Node, msg: String) =
    GossipError.InvalidChunks(msg, source)
}
