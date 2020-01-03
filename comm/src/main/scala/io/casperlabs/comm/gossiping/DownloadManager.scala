package io.casperlabs.comm.gossiping

import cats._
import cats.syntax._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.GossipError
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping.DownloadManagerImpl.RetriesConf
import io.casperlabs.comm.gossiping.Utils._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.{Compression, FatalErrorShutdown, Log}
import io.casperlabs.shared.Log.LogOps

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

/** Manage the download, validation, storing and gossiping of blocks. */
trait DownloadManager[F[_]] {

  /** Schedule the download of a full block from the `source` node.
    * If `relay` is `true` then gossip it afterwards, if it's valid.
    * The returned `F[F[Unit]]` represents the success/failure of
    * the scheduling itself; it fails if there's any error accessing
    * the local backend, or if the scheduling cannot be carried out
    * due to missing dependencies (at this point we should have synced
    * already and the schedule should be called in topological order).
    *
    * The unwrapped `F[Unit]` _inside_ the `F[F[Unit]]` can be used to
    * wait until the actual download finishes, or results in an error. */
  def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean): F[WaitHandle[F]]
}

object DownloadManagerImpl {
  implicit val metricsSource: Metrics.Source =
    Metrics.Source(GossipingMetricsSource, "DownloadManager")

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
    def hasBlock(blockHash: ByteString): F[Boolean]
    def validateBlock(block: Block): F[Unit]
    def storeBlock(block: Block): F[Unit]
    def storeBlockSummary(summary: BlockSummary): F[Unit]

    /** Notify about new blocks we were told about but haven't acquired yet. */
    def onScheduled(summary: BlockSummary): F[Unit]

    /** Notify about a new block we downloaded, verified and stored. */
    def onDownloaded(blockHash: ByteString): F[Unit]
  }

  /** Messages the Download Manager uses inside its scheduler "queue". */
  sealed trait Signal[F[_]] extends Product with Serializable
  object Signal {
    final case class Download[F[_]](
        summary: BlockSummary,
        source: Node,
        relay: Boolean,
        scheduleFeedback: ScheduleFeedback[F]
    ) extends Signal[F]
    final case class DownloadSuccess[F[_]](blockHash: ByteString)                extends Signal[F]
    final case class DownloadFailure[F[_]](blockHash: ByteString, ex: Throwable) extends Signal[F]
  }

  /** Keep track of download items. */
  final case class Item[F[_]](
      summary: BlockSummary,
      // Any node that told us it has this block.
      sources: Set[Node],
      // Whether we'll have to relay at the end.
      relay: Boolean,
      // Other blocks we have to download before this one.
      dependencies: Set[ByteString],
      isDownloading: Boolean = false,
      isError: Boolean = false,
      // Keep returning the same Deferred until one attempt to download is finished.
      // The next schedule will create a new Deferred that will be completed anew.
      maybeWatcher: Option[DownloadFeedback[F]]
  ) {
    val canStart: Boolean = !isDownloading && dependencies.isEmpty
  }

  final case class RetriesConf(
      maxRetries: Int Refined NonNegative,
      initialBackoffPeriod: FiniteDuration,
      backoffFactor: Double Refined GreaterEqual[W.`1.0`.T]
  )
  object RetriesConf {
    val noRetries = RetriesConf(0, Duration.Zero, 1.0)
  }

  /** Start the download manager. */
  def apply[F[_]: Concurrent: Log: Timer: Metrics](
      maxParallelDownloads: Int,
      connectToGossip: GossipService.Connector[F],
      backend: Backend[F],
      relaying: Relaying[F],
      retriesConf: RetriesConf
  ): Resource[F, DownloadManager[F]] =
    Resource.make {
      for {
        isShutdown <- Ref.of(false)
        itemsRef   <- Ref.of(Map.empty[ByteString, Item[F]])
        workersRef <- Ref.of(Map.empty[ByteString, Fiber[F, Unit]])
        semaphore  <- Semaphore[F](maxParallelDownloads.toLong)
        signal     <- MVar[F].empty[Signal[F]]
        manager = new DownloadManagerImpl[F](
          isShutdown,
          itemsRef,
          workersRef,
          semaphore,
          signal,
          connectToGossip,
          backend,
          relaying,
          retriesConf
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
          _       <- workers.values.toList.map(_.cancel.attempt).sequence.void
        } yield ()
    } map {
      case (_, _, _, manager) => manager
    }

  /** All dependencies that need to be downloaded before a block. */
  private def dependencies(summary: BlockSummary): Seq[ByteString] =
    summary.parentHashes ++ summary.justifications.map(_.latestBlockHash)
}

class DownloadManagerImpl[F[_]: Concurrent: Log: Timer: Metrics](
    isShutdown: Ref[F, Boolean],
    // Keep track of active downloads and dependencies.
    itemsRef: Ref[F, Map[ByteString, DownloadManagerImpl.Item[F]]],
    // Keep track of ongoing downloads so we can cancel them.
    workersRef: Ref[F, Map[ByteString, Fiber[F, Unit]]],
    // Limit parallel downloads.
    semaphore: Semaphore[F],
    // Single item control signals for the manager loop.
    signal: MVar[F, DownloadManagerImpl.Signal[F]],
    // Establish gRPC connection to another node.
    connectToGossip: GossipService.Connector[F],
    backend: DownloadManagerImpl.Backend[F],
    relaying: Relaying[F],
    retriesConf: RetriesConf
) extends DownloadManager[F] {

  import DownloadManagerImpl._

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

  override def scheduleDownload(
      summary: BlockSummary,
      source: Node,
      relay: Boolean
  ): F[WaitHandle[F]] =
    for {
      // Fail rather than block forever.
      _ <- ensureNotShutdown
      // Feedback about whether we successfully scheduled the item.
      sf <- makeScheduleFeedback
      _  <- signal.put(Signal.Download(summary, source, relay, sf))
      df <- Sync[F].rethrow(sf.get)
    } yield Sync[F].rethrow(df.get)

  /** Run the manager loop which listens to signals and starts workers when it can. */
  def run: F[Unit] =
    signal.take.flatMap {
      case Signal.Download(summary, source, relay, scheduleFeedback) =>
        // At this point we should have already synced and only scheduled things to which we know how to get.
        val start =
          isDownloaded(summary.blockHash).ifM(
            for {
              downloadFeedback <- makeDownloadFeedback
              _                <- downloadFeedback.complete(Right(()))
            } yield downloadFeedback,
            for {
              _                        <- ensureNoMissingDependencies(summary)
              items                    <- itemsRef.get
              itemAndFeedback          <- mergeItem(items, summary, source, relay)
              (item, downloadFeedback) = itemAndFeedback
              _                        <- backend.onScheduled(summary).start.whenA(!items.contains(summary.blockHash))
              _                        <- itemsRef.update(_ + (summary.blockHash -> item))
              _                        <- if (item.canStart) startWorker(item) else Sync[F].unit
              _                        <- setScheduledGauge
            } yield downloadFeedback
          )
        // Report any startup errors so the caller knows something's fatally wrong, then carry on.
        start
          .attemptAndLog("An error occurred when handling Download signal.")
          .flatMap(scheduleFeedback.complete) >> run

      case Signal.DownloadSuccess(blockHash) =>
        val finish = for {
          _ <- workersRef.update(_ - blockHash)
          // Remove the item and check what else we can download now.
          next <- itemsRef.modify { items =>
                   val item = items(blockHash)
                   val dependants = items.collect {
                     case (hash, dep) if dep.dependencies contains blockHash =>
                       hash -> dep.copy(dependencies = dep.dependencies - blockHash)
                   }
                   val startables = dependants.collect {
                     case (_, dep) if dep.canStart => dep
                   }
                   (items ++ dependants - blockHash, item.maybeWatcher.toList -> startables.toList)
                 }
          (watchers, startables) = next
          _                      <- watchers.traverse(_.complete(Right(())).attempt.void)
          _                      <- backend.onDownloaded(blockHash).start
          _                      <- startables.traverse(startWorker)
          _                      <- setScheduledGauge
        } yield ()

        finish.attemptAndLog("An error occurred when handling DownloadSuccess signal.") >> run

      case Signal.DownloadFailure(blockHash, ex) =>
        val finish = for {
          _ <- workersRef.update(_ - blockHash)
          // Keep item so its dependencies are not downloaded.
          // If it's scheduled again we'll try once more.
          // Old stuff will be forgotten when the node is restarted.
          watchers <- itemsRef.modify { items =>
                       val item = items(blockHash)
                       val tombstone: Item[F] =
                         item.copy(isDownloading = false, isError = true, maybeWatcher = None)
                       (items + (blockHash -> tombstone), item.maybeWatcher.toList)
                     }
          // Tell whoever scheduled it before that it's over.
          _ <- watchers.traverse(_.complete(Left(ex)).attempt.void)
          _ <- setScheduledGauge
        } yield ()

        finish.attemptAndLog("An error occurred when handling DownloadFailure signal.") >> run
    }

  // Indicate how many items we have in the queue.
  private def setScheduledGauge =
    for {
      items <- itemsRef.get
      _     <- Metrics[F].setGauge("downloads_scheduled", items.size.toLong)
    } yield ()

  /** Either create a new item or add the source to an existing one.
    * Return the deferred that will eventually signal the completion of the task.
    */
  private def mergeItem(
      items: Map[ByteString, Item[F]],
      summary: BlockSummary,
      source: Node,
      relay: Boolean
  ): F[(Item[F], DownloadFeedback[F])] =
    makeDownloadFeedback flatMap { freshDownloadFeedback =>
      items.get(summary.blockHash) map { existing =>
        Sync[F].pure {
          // If it was already scheduled we keep the existing Deferred.
          val downloadFeedback = existing.maybeWatcher getOrElse freshDownloadFeedback
          existing.copy(
            sources = existing.sources + source,
            relay = existing.relay || relay,
            maybeWatcher = Some(downloadFeedback)
          ) -> downloadFeedback
        }
      } getOrElse {
        // Collect which dependencies have already been downloaded.
        dependencies(summary).toList.traverse { hash =>
          if (items.contains(hash)) Sync[F].pure(hash -> false)
          else isDownloaded(hash).map(hash            -> _)
        } map { deps =>
          val pending = deps.filterNot(_._2).map(_._1).toSet
          Item(
            summary,
            Set(source),
            relay,
            dependencies = pending,
            maybeWatcher = Some(freshDownloadFeedback)
          ) -> freshDownloadFeedback
        }
      }
    }

  /** Check that we either have all dependencies already downloaded or scheduled. */
  private def ensureNoMissingDependencies(summary: BlockSummary): F[Unit] =
    dependencies(summary).toList.traverse { hash =>
      isScheduled(hash).ifM((hash -> true).pure[F], isDownloaded(hash).map(hash -> _))
    } map {
      _.filterNot(_._2).map(_._1)
    } flatMap {
      case Nil =>
        Sync[F].unit
      case missing =>
        Sync[F].raiseError(GossipError.MissingDependencies(summary.blockHash, missing))
    }

  private def isScheduled(hash: ByteString): F[Boolean] =
    itemsRef.get.map(_ contains hash)

  private def isDownloaded(hash: ByteString): F[Boolean] =
    backend.hasBlock(hash)

  /** Kick off the download and mark the item. */
  private def startWorker(item: Item[F]): F[Unit] =
    for {
      _ <- itemsRef.update(_ + (item.summary.blockHash -> item.copy(isDownloading = true)))
      worker <- Concurrent[F].start {
                 // Indicate how many items are currently being attempted, including their retry wait time.
                 Metrics[F].gauge("downloads_ongoing") {
                   download(item.summary.blockHash)
                 }
               }
      _ <- workersRef.update(_ + (item.summary.blockHash -> worker))
    } yield ()

  // Just say which block hash to download, try all possible sources.
  private def download(blockHash: ByteString): F[Unit] = {
    val id                     = hex(blockHash)
    val success                = signal.put(Signal.DownloadSuccess(blockHash))
    def failure(ex: Throwable) = signal.put(Signal.DownloadFailure(blockHash, ex))

    def tryDownload(summary: BlockSummary, source: Node, relay: Boolean) =
      for {
        block <- fetchAndRestore(source, blockHash)
        _     <- backend.validateBlock(block)
        _     <- backend.storeBlock(block)
        // This could arguably be done by `storeBlock` but this way it's explicit,
        // so we don't forget to talk to both kind of storages.
        _ <- backend.storeBlockSummary(summary)
        _ <- relaying.relay(List(summary.blockHash)).whenA(relay)
        _ <- success
        _ <- Metrics[F].incrementCounter("downloads_succeeded")
      } yield ()

    // Try to download until we succeed or give up.
    def loop(counterPerSource: Map[Node, Int], lastError: Option[Throwable]): F[Unit] =
      itemsRef.get.map(_(blockHash)).flatMap { item =>
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
                  s"Could not download block $id from any of the sources; tried $countersSum requests for $sourcesNum sources: $ex"
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
                    s"Scheduling download of block $id from ${source.show -> "peer"} later, $attempt, $delay"
                  ) *>
                    Timer[F].sleep(delay) *>
                    tryDownload(item.summary, source, item.relay).handleErrorWith {
                      case NonFatal(ex) =>
                        val nextAttempt = attempt + 1
                        Metrics[F].incrementCounter("downloads_failed") *>
                          Log[F].warn(
                            s"Retrying download of block $id from other sources, failed source: ${source.show -> "peer"}, prev $attempt: $ex"
                          ) *>
                          loop(counterPerSource.updated(source, nextAttempt), ex.some)
                    }
                case _: Duration.Infinite => sys.error("Unreachable")
              }
          }
        }
      }

    itemsRef.get.map(_(blockHash)).flatMap { item =>
      // Make sure the manager knows we're done, even if we fail unexpectedly.
      loop(item.sources.map((_, 0)).toMap, lastError = none[Throwable]) recoverWith {
        case NonFatal(ex) => failure(ex)
      }
    }
  }

  /** Download a block from the source node and decompress it. */
  private def fetchAndRestore(source: Node, blockHash: ByteString): F[Block] = {
    def invalid(msg: String) =
      GossipError.InvalidChunks(msg, source)

    // Keep track of how much we have downloaded so far and cancel the stream if it goes over the promised size.
    case class Acc(
        header: Option[Chunk.Header],
        totalSizeSoFar: Int,
        chunks: List[ByteString],
        error: Option[GossipError]
    ) {
      def invalid(msg: String): Acc =
        copy(error = Some(GossipError.InvalidChunks(msg, source)))

      def append(data: ByteString): Acc =
        copy(totalSizeSoFar = totalSizeSoFar + data.size, chunks = data :: chunks)
    }

    val effect =
      for {
        stub <- connectToGossip(source)
        req = GetBlockChunkedRequest(
          blockHash = blockHash,
          acceptedCompressionAlgorithms = Seq("lz4")
        )

        acc <- stub.getBlockChunked(req).foldWhileLeftL(Acc(None, 0, Nil, None)) {
                case (acc, chunk) if acc.header.isEmpty && chunk.content.isHeader =>
                  val header = chunk.getHeader
                  header.compressionAlgorithm match {
                    case "" | "lz4" =>
                      Left(acc.copy(header = Some(header)))
                    case other =>
                      Right(
                        acc.invalid(s"Block chunks compressed with unexpected algorithm: $other")
                      )
                  }

                case (acc, chunk) if acc.header.nonEmpty && chunk.content.isHeader =>
                  Right(acc.invalid("Block chunks contained a second header."))

                case (acc, _) if acc.header.isEmpty =>
                  Right(acc.invalid("Block chunks did not start with a header."))

                case (acc, chunk) if chunk.getData.isEmpty =>
                  Right(acc.invalid("Block chunks contained empty data frame."))

                case (acc, chunk)
                    if acc.totalSizeSoFar + chunk.getData.size > acc.header.get.contentLength =>
                  Right(acc.invalid("Block chunks are exceeding the promised content length."))

                case (acc, chunk) =>
                  Left(acc.append(chunk.getData))
              }

        content <- if (acc.error.nonEmpty) {
                    Sync[F].raiseError[Array[Byte]](acc.error.get)
                  } else if (acc.header.isEmpty) {
                    Sync[F].raiseError[Array[Byte]](invalid("Did not receive a header."))
                  } else {
                    val header  = acc.header.get
                    val content = acc.chunks.toArray.reverse.flatMap(_.toByteArray)
                    if (header.compressionAlgorithm.isEmpty) {
                      Sync[F].pure(content)
                    } else {
                      Compression
                        .decompress(content, header.originalContentLength)
                        .fold(
                          Sync[F].raiseError[Array[Byte]](invalid("Could not decompress chunks."))
                        )(Sync[F].pure(_))
                    }
                  }

        block <- Sync[F].delay(Block.parseFrom(content))
        _ <- Sync[F]
              .raiseError(invalid("Retrieved block has unexpected block hash."))
              .whenA(block.blockHash != blockHash)
      } yield block

    // Indicate how many fetches we are trying to do at a time. If it's larger then the semaphore
    // we configured we'd know where we'd have to raise it to allow maximum throughput.
    Metrics[F].gauge("fetches_ongoing") {
      semaphore.withPermit {
        effect
      }
    }
  }
}
