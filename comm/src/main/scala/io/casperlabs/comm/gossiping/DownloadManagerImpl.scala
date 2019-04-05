package io.casperlabs.comm.gossiping

import cats._
import cats.syntax._
import cats.implicits._
import cats.effect._
import cats.effect.syntax._
import cats.effect.concurrent._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.GossipError
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.shared.{Compression, Log, LogSource}
import scala.util.control.NonFatal

object DownloadManagerImpl {

  type Feedback[F[_]] = Deferred[F, Either[Throwable, Unit]]

  /** Interface to the local backend dependencies. */
  trait Backend[F[_]] {
    def hasBlock(blockHash: ByteString): F[Boolean]
    def validateBlock(block: Block): F[Unit]
    def storeBlock(block: Block): F[Unit]
    def storeBlockSummary(summary: BlockSummary): F[Unit]
  }

  /** Messages the Download Manager uses inside its scheduler "queue". */
  sealed trait Signal[F[_]]
  object Signal {
    case class Download[F[_]](
        summary: BlockSummary,
        source: Node,
        relay: Boolean,
        // Feedback about whether the scheduling itself succeeded.
        scheduleFeedback: Feedback[F],
        // Feedback about whether the download eventually succeeded.
        downloadFeedback: Feedback[F]
    ) extends Signal[F]
    case class DownloadSuccess[F[_]](blockHash: ByteString)                extends Signal[F]
    case class DownloadFailure[F[_]](blockHash: ByteString, ex: Throwable) extends Signal[F]
  }

  /** Keep track of download items. */
  case class Item[F[_]](
      summary: BlockSummary,
      // Any node that told us it has this block.
      sources: Set[Node],
      // Whether we'll have to relay at the end.
      relay: Boolean,
      // Other blocks we have to download before this one.
      dependencies: Set[ByteString],
      isDownloading: Boolean = false,
      isError: Boolean = false,
      watchers: List[Feedback[F]]
  ) {
    def canStart = !isDownloading && dependencies.isEmpty
  }

  /** Start the download manager. */
  def apply[F[_]: Concurrent: Log](
      maxParallelDownloads: Int,
      connectToGossip: Node => F[GossipService[F]],
      backend: Backend[F],
      relaying: Relaying[F]
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
          relaying
        )
        managerLoop <- Concurrent[F].start(manager.run)
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
    summary.getHeader.parentHashes ++ summary.getHeader.justifications.map(_.latestBlockHash)

  private def base16(blockHash: ByteString) =
    Base16.encode(blockHash.toByteArray)
}

class DownloadManagerImpl[F[_]: Sync: Concurrent: Log](
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
    connectToGossip: Node => F[GossipService[F]],
    backend: DownloadManagerImpl.Backend[F],
    relaying: Relaying[F]
) extends DownloadManager[F] {

  import DownloadManagerImpl._

  private def ensureNotShutdown: F[Unit] =
    isShutdown.get.ifM(
      Sync[F]
        .raiseError(new java.lang.IllegalStateException("Download Manager already shut down.")),
      Sync[F].unit
    )

  override def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean): F[F[Unit]] =
    for {
      // Fail rather than block forever.
      _ <- ensureNotShutdown
      // Feedback about whether we successfully scheduled the item.
      sr <- Deferred[F, Either[Throwable, Unit]]
      dr <- Deferred[F, Either[Throwable, Unit]]
      _  <- signal.put(Signal.Download(summary, source, relay, sr, dr))
      _  <- Sync[F].rethrow(sr.get)
    } yield Sync[F].rethrow(dr.get)

  /** Run the manager loop which listens to signals and starts workers when it can. */
  def run: F[Unit] =
    signal.take.flatMap {
      case Signal.Download(summary, source, relay, scheduleFeedback, downloadFeedback) =>
        // At this point we should have already synced and only scheduled things to which we know how to get.
        val start =
          isDownloaded(summary.blockHash).ifM(
            downloadFeedback.complete(Right(())),
            ensureNoMissingDependencies(summary) *> {
              for {
                items <- itemsRef.get
                item  <- mergeItem(items, summary, source, relay, downloadFeedback)
                _     <- itemsRef.update(_ + (summary.blockHash -> item))
                _     <- if (item.canStart) startWorker(item) else Sync[F].unit
              } yield ()
            }
          )
        // Report any startup errors so the caller knows something's fatally wrong, then carry on.
        start.attempt.flatMap(scheduleFeedback.complete(_)) *> run

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
                   (items ++ dependants - blockHash, item.watchers -> startables.toList)
                 }
          (watchers, startables) = next
          _                      <- watchers.traverse(_.complete(Right(())).attempt.void)
          _                      <- startables.traverse(startWorker(_))
        } yield ()

        finish.attempt *> run

      case Signal.DownloadFailure(blockHash, ex) =>
        val finish = for {
          _ <- workersRef.update(_ - blockHash)
          // Keep item so its dependencies are not downloaded.
          // If it's scheduled again we'll try once more.
          // Old stuff will be forgotten when the node is restarted.
          watchers <- itemsRef.modify { items =>
                       val item = items(blockHash)
                       val tombstone: Item[F] =
                         item.copy(isDownloading = false, isError = true, watchers = Nil)
                       (items + (blockHash -> tombstone), item.watchers)
                     }
          // Tell whoever scheduled it before that it's over.
          _ <- watchers.traverse(_.complete(Left(ex)).attempt.void)
        } yield ()

        finish.attempt *> run
    }

  /** Either create a new item or add the source to an existing one. */
  private def mergeItem(
      items: Map[ByteString, Item[F]],
      summary: BlockSummary,
      source: Node,
      relay: Boolean,
      downloadFeedback: Feedback[F]
  ): F[Item[F]] =
    items.get(summary.blockHash) map { existing =>
      Sync[F].pure {
        existing.copy(
          sources = existing.sources + source,
          relay = existing.relay || relay,
          watchers = downloadFeedback :: existing.watchers
        )
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
          watchers = List(downloadFeedback)
        )
      }
    }

  /** Check that we either have all dependencies already downloaded or scheduled. */
  private def ensureNoMissingDependencies(summary: BlockSummary): F[Unit] =
    dependencies(summary).toList.traverse { hash =>
      isScheduled(hash) flatMap {
        case true  => Sync[F].pure(hash           -> true)
        case false => isDownloaded(hash).map(hash -> _)
      }
    } map {
      _.filterNot(_._2).map(_._1)
    } flatMap {
      case missing if missing.nonEmpty =>
        Sync[F].raiseError(GossipError.MissingDependencies(summary.blockHash, missing))
      case _ =>
        Sync[F].unit
    }

  private def isScheduled(hash: ByteString): F[Boolean] =
    itemsRef.get.map(_ contains hash)

  private def isDownloaded(hash: ByteString): F[Boolean] =
    backend.hasBlock(hash)

  /** Kick off the download and mark the item. */
  private def startWorker(item: Item[F]): F[Unit] =
    for {
      _      <- itemsRef.update(_ + (item.summary.blockHash -> item.copy(isDownloading = true)))
      worker <- Concurrent[F].start(download(item.summary.blockHash))
      _      <- workersRef.update(_ + (item.summary.blockHash -> worker))
    } yield ()

  // Just say which block hash to download, try all possible sources.
  private def download(blockHash: ByteString): F[Unit] = {
    val id                     = base16(blockHash)
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
        _ <- if (relay) relaying.relay(List(summary.blockHash)) else ().pure[F]
        _ <- success
      } yield ()

    // Try to download until we succeed or give up.
    def loop(tried: Set[Node], errors: List[Throwable]): F[Unit] =
      // Get the latest sources.
      itemsRef.get.map(_(blockHash)).flatMap { item =>
        (item.sources -- tried).headOption match {
          case Some(source) =>
            tryDownload(item.summary, source, item.relay).recoverWith {
              case NonFatal(ex) =>
                // TODO: Exponential backoff, pick another node, try to store again, etc.
                Log[F].error(s"Failed to download block $id from ${source.host}", ex) *>
                  loop(tried + source, ex :: errors)
            }

          case None =>
            Log[F].error(
              s"Could not download block $id from any of the sources; tried ${tried.size}."
            ) *> failure(errors.head)
        }
      }

    // Make sure the manager knows we're done, even if we fail unexpectedly.
    loop(Set.empty, List(new IllegalStateException("No source to download from."))) recoverWith {
      case NonFatal(ex) => failure(ex)
    }
  }

  /** Download a block from the source node and decompress it. */
  private def fetchAndRestore(source: Node, blockHash: ByteString): F[Block] = {
    def invalid(msg: String) =
      GossipError.InvalidChunks(msg, source)

    // Keep track of how much we have downloaded so far and cancel the stream if it goes over the promised size.
    case class Acc(
        header: Option[Chunk.Header],
        totalSizeSofar: Int,
        chunks: List[ByteString],
        error: Option[GossipError]
    ) {
      def invalid(msg: String) =
        copy(error = Some(GossipError.InvalidChunks(msg, source)))

      def append(data: ByteString) =
        copy(totalSizeSofar = totalSizeSofar + data.size, chunks = data :: chunks)
    }

    semaphore.withPermit {
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
                    if acc.totalSizeSofar + chunk.getData.size > acc.header.get.contentLength =>
                  Right(acc.invalid("Block chunks are exceeding the promised content length."))

                case (acc, chunk) =>
                  Left(acc.append(chunk.getData))
              }

        content <- if (acc.error.nonEmpty) {
                    Sync[F].raiseError(acc.error.get)
                  } else if (acc.header.isEmpty) {
                    Sync[F].raiseError(invalid("Did not receive a header."))
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
      } yield block
    }
  }
}
