package io.casperlabs.comm.gossiping.downloadmanager

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.effect.implicits._
import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined.auto._
import io.casperlabs.casper.consensus.{Deploy, DeploySummary}
import io.casperlabs.comm.GossipError
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils.showNode
import io.casperlabs.comm.gossiping.Utils._
import io.casperlabs.comm.gossiping._
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log.LogOps
import io.casperlabs.shared.{Compression, Log}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.util.control.NonFatal

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

  // to use .minimumOption on collections of (Node, Int)
  implicit val sourcesAndCountersOrder: Order[(Node, Int)] = Order[Int].contramap[(Node, Int)](_._2)

  /** Start the download manager. */
  def apply[F[_]: Concurrent: Log: Timer: Metrics](
      maxParallelDownloads: Int,
      connectToGossip: GossipService.Connector[F],
      backend: Backend[F],
      relaying: Relaying[F],
      retriesConf: RetriesConf
  ): Resource[F, DeployDownloadManager[F]] =
    Resource.make {
      for {
        isShutdown <- Ref.of(false)
        itemsRef   <- Ref.of(Map.empty[ByteString, Item[F]])
        workersRef <- Ref.of(Map.empty[ByteString, Fiber[F, Unit]])
        semaphore  <- Semaphore[F](maxParallelDownloads.toLong)
        signal     <- MVar[F].empty[Signal[F]]
        manager = new DeployDownloadManagerImpl[F](
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
          _       <- workers.values.toList.traverse(_.cancel.attempt)
        } yield ()
    } map {
      case (_, _, _, manager) => manager
    }

  override def dependencies(summary: DeploySummary): Seq[ByteString] =
    summary.getHeader.dependencies
}

class DeployDownloadManagerImpl[F[_]: Concurrent: Log: Timer: Metrics](
    isShutdown: Ref[F, Boolean],
    // Keep track of active downloads and dependencies.
    itemsRef: Ref[F, Map[ByteString, DeployDownloadManagerImpl.Item[F]]],
    // Keep track of ongoing downloads so we can cancel them.
    workersRef: Ref[F, Map[ByteString, Fiber[F, Unit]]],
    // Limit parallel downloads.
    semaphore: Semaphore[F],
    // Single item control signals for the manager loop.
    signal: MVar[F, DeployDownloadManagerImpl.Signal[F]],
    // Establish gRPC connection to another node.
    connectToGossip: GossipService.Connector[F],
    backend: DeployDownloadManagerImpl.Backend[F],
    relaying: Relaying[F],
    retriesConf: DeployDownloadManagerImpl.RetriesConf
) extends DeployDownloadManager[F] {

  import DeployDownloadManagerImpl._

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
      summary: DeploySummary,
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
          isDownloaded(summary.deployHash).ifM(
            for {
              downloadFeedback <- makeDownloadFeedback
              _                <- downloadFeedback.complete(Right(()))
            } yield downloadFeedback,
            for {
              _                        <- ensureNoMissingDependencies(summary)
              items                    <- itemsRef.get
              itemAndFeedback          <- mergeItem(items, summary, source, relay)
              (item, downloadFeedback) = itemAndFeedback
              _                        <- backend.onScheduled(summary).start.whenA(!items.contains(summary.deployHash))
              _                        <- itemsRef.update(_ + (summary.deployHash -> item))
              _                        <- if (item.canStart) startWorker(item) else Sync[F].unit
              _                        <- setScheduledGauge()
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
          _                      <- setScheduledGauge()
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
      items: Map[ByteString, Item[F]],
      summary: DeploySummary,
      source: Node,
      relay: Boolean
  ): F[(Item[F], DownloadFeedback[F])] =
    makeDownloadFeedback flatMap { freshDownloadFeedback =>
      items.get(summary.deployHash) map { existing =>
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
  private def ensureNoMissingDependencies(summary: DeploySummary): F[Unit] =
    dependencies(summary).toList.traverse { hash =>
      isScheduled(hash).ifM((hash -> true).pure[F], isDownloaded(hash).map(hash -> _))
    } map {
      _.filterNot(_._2).map(_._1)
    } flatMap {
      case Nil =>
        Sync[F].unit
      case missing =>
        Sync[F].raiseError(GossipError.MissingDependencies(summary.deployHash, missing))
    }

  private def isScheduled(hash: ByteString): F[Boolean] =
    itemsRef.get.map(_ contains hash)

  private def isDownloaded(hash: ByteString): F[Boolean] =
    backend.contains(hash)

  /** Kick off the download and mark the item. */
  private def startWorker(item: Item[F]): F[Unit] =
    for {
      _ <- itemsRef.update(_ + (item.handle.deployHash -> item.copy(isDownloading = true)))
      worker <- Concurrent[F].start {
                 // Indicate how many items are currently being attempted, including their retry wait time.
                 Metrics[F].gauge("downloads_ongoing") {
                   download(item.handle.deployHash)
                 }
               }
      _ <- workersRef.update(_ + (item.handle.deployHash -> worker))
    } yield ()

  // Just say which block hash to download, try all possible sources.
  private def download(blockHash: ByteString): F[Unit] = {
    val id                     = hex(blockHash)
    val success                = signal.put(Signal.DownloadSuccess(blockHash))
    def failure(ex: Throwable) = signal.put(Signal.DownloadFailure(blockHash, ex))

    def tryDownload(summary: DeploySummary, source: Node, relay: Boolean) =
      for {
        block <- fetchAndRestore(source, blockHash)
        _     <- backend.validate(block)
        _     <- backend.store(block)
        _     <- relaying.relay(List(summary.deployHash)).whenA(relay)
        _     <- success
        _     <- Metrics[F].incrementCounter("downloads_succeeded")
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
                    tryDownload(item.handle, source, item.relay).handleErrorWith {
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
  private def fetchAndRestore(source: Node, deployHash: ByteString): F[Deploy] = {
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
        req = StreamDeploysChunkedRequest(
          deployHashes = List(deployHash),
          acceptedCompressionAlgorithms = Seq("lz4")
        )

        acc <- stub.streamDeploysChunked(req).foldWhileLeftL(Acc(None, 0, Nil, None)) {
                case (acc, chunk) if acc.header.isEmpty && chunk.content.isHeader =>
                  val header = chunk.getHeader
                  header.compressionAlgorithm match {
                    case "" | "lz4" =>
                      Left(acc.copy(header = Some(header)))
                    case other =>
                      Right(
                        acc.invalid(s"Deploy chunks compressed with unexpected algorithm: $other")
                      )
                  }

                case (acc, chunk) if acc.header.nonEmpty && chunk.content.isHeader =>
                  Right(acc.invalid("Deploy chunks contained a second header."))

                case (acc, _) if acc.header.isEmpty =>
                  Right(acc.invalid("Deploy chunks did not start with a header."))

                case (acc, chunk) if chunk.getData.isEmpty =>
                  Right(acc.invalid("Deploy chunks contained empty data frame."))

                case (acc, chunk)
                    if acc.totalSizeSoFar + chunk.getData.size > acc.header.get.contentLength =>
                  Right(acc.invalid("Deploy chunks are exceeding the promised content length."))

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

        deploy <- Sync[F].delay(Deploy.parseFrom(content))
        _ <- Sync[F]
              .raiseError(invalid("Retrieved deploy has unexpected deploy hash."))
              .whenA(deploy.deployHash != deployHash)
      } yield deploy

    // Indicate how many fetches we are trying to do at a time. If it's larger then the semaphore
    // we configured we'd know where we'd have to raise it to allow maximum throughput.
    Metrics[F].gauge("fetches_ongoing") {
      semaphore.withPermit {
        effect
      }
    }
  }
}
