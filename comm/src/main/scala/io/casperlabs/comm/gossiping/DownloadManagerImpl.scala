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

  implicit val logSource: LogSource = LogSource(this.getClass)

  /** Interface to the local backend dependencies. */
  trait Backend[F[_]] {
    def hasBlock(blockHash: ByteString): F[Boolean]
    def validateBlock(block: Block): F[Unit]
    def storeBlock(block: Block): F[Unit]
    def storeBlockSummary(summary: BlockSummary): F[Unit]
  }

  /** Messages the DM uses inside its scheduler "queue". */
  sealed trait Signal[F[_]]
  object Signal {
    case class Download[F[_]](
        summary: BlockSummary,
        source: Node,
        relay: Boolean,
        // Feedback about whether the scheduling itself succeeded.
        scheduleResult: Deferred[F, Either[Throwable, Unit]]
    ) extends Signal[F]
    case class DownloadSuccess[F[_]](blockHash: ByteString) extends Signal[F]
    case class DownloadFailure[F[_]](blockHash: ByteString) extends Signal[F]
  }

  /** Keep track of download items. */
  case class Item(
      summary: BlockSummary,
      // Any node that told us it has this block.
      sources: Set[Node],
      // Whether we'll have to relay at the end.
      relay: Boolean,
      // Other blocks we have to download before this one.
      dependencies: Set[ByteString],
      isDownloading: Boolean = false,
      isError: Boolean = false
  ) {
    def canStart = !isDownloading && dependencies.isEmpty
  }

  /** Start the download manager. */
  def apply[F[_]: Sync: Concurrent: Log](
      maxParallelDownloads: Int,
      connectToGossip: Node => F[GossipService[F]],
      backend: Backend[F]
  ): Resource[F, DownloadManager[F]] =
    Resource.make {
      for {
        isShutdown <- Ref.of(false)
        itemsRef   <- Ref.of(Map.empty[ByteString, Item])
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
          backend
        )
        managerLoop <- Concurrent[F].start(manager.run)
      } yield (isShutdown, workersRef, managerLoop, manager)
    } {
      case (isShutdown, workersRef, managerLoop, _) =>
        for {
          _       <- Log[F].info("Shutting down the DownloadManager...")
          _       <- isShutdown.set(true)
          _       <- managerLoop.cancel.attempt
          workers <- workersRef.get
          _       <- workers.values.toList.map(_.cancel.attempt).sequence.void
        } yield ()
    } map {
      case (_, _, _, manager) => manager
    }

  /** All dependencies that need to be downloaded before a block. */
  def dependencies(summary: BlockSummary): Seq[ByteString] =
    summary.getHeader.parentHashes ++ summary.getHeader.justifications.map(_.latestBlockHash)

  def base16(blockHash: ByteString) =
    Base16.encode(blockHash.toByteArray)
}

class DownloadManagerImpl[F[_]: Sync: Concurrent: Log](
    isShutdown: Ref[F, Boolean],
    // Keep track of active downloads and dependencies.
    itemsRef: Ref[F, Map[ByteString, DownloadManagerImpl.Item]],
    // Keep track of ongoing downloads so we can cancel them.
    workersRef: Ref[F, Map[ByteString, Fiber[F, Unit]]],
    // Limit parallel downloads.
    semaphore: Semaphore[F],
    // Single item control signals for the manager loop.
    signal: MVar[F, DownloadManagerImpl.Signal[F]],
    // Establish gRPC connection to another node.
    // TODO: Handle connection errors.
    connectToGossip: Node => F[GossipService[F]],
    // TODO: Handle storage errors.
    backend: DownloadManagerImpl.Backend[F]
    // TODO: Ref to a DAG to keep track of pending dependencies.
) extends DownloadManager[F] {

  import DownloadManagerImpl._

  implicit val logSource: LogSource = LogSource(this.getClass)

  private def ensureNotShutdown: F[Unit] =
    isShutdown.get.ifM(
      Sync[F]
        .raiseError(new java.lang.IllegalStateException("Download manager already shut down.")),
      Sync[F].unit
    )

  override def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean): F[Unit] =
    for {
      // Fail rather than block forever.
      _ <- ensureNotShutdown
      // Feedback about whether we successfully scheduled the item.
      d <- Deferred[F, Either[Throwable, Unit]]
      _ <- signal.put(Signal.Download(summary, source, relay, d))
      _ <- Sync[F].rethrow(d.get)
    } yield ()

  /** Run the manager loop which listens to signals and starts workers when it can. */
  def run: F[Unit] =
    signal.take.flatMap {
      case Signal.Download(summary, source, relay, scheduleResult) =>
        // At this point we should have already synced and only scheduled things to which we know how to get.
        val start = ensureNoMissingDependencies(summary) flatMap { _ =>
          // Check that we haven't downloaded it before.
          isDownloaded(summary.blockHash).ifM(
            Sync[F].unit,
            add(summary, source, relay) flatMap { item =>
              if (item.canStart) startWorker(item)
              else Sync[F].unit
            }
          )
        }
        // Report any startup errors so the caller knows something's fatally wrong, then carry on.
        start.attempt.flatMap(scheduleResult.complete(_)) *> run

      case Signal.DownloadSuccess(blockHash) =>
        val finish = for {
          _ <- workersRef.update(_ - blockHash)
          // Remove the item and check what else we can download now.
          startables <- itemsRef.modify { items =>
                         val dependants = items.collect {
                           case (hash, dep) if dep.dependencies contains blockHash =>
                             hash -> dep.copy(dependencies = dep.dependencies - blockHash)
                         }
                         val startables = dependants.collect {
                           case (_, dep) if dep.canStart => dep
                         }
                         (items ++ dependants - blockHash, startables.toList)
                       }
          _ <- startables.traverse(startWorker(_))
        } yield ()

        finish.attempt *> run

      case Signal.DownloadFailure(blockHash) =>
        val finish = for {
          _ <- workersRef.update(_ - blockHash)
          // Keep item so its dependencies are not downloaded. If it's scheduled again we'll try once more.
          // Old stuff will be forgotten when the node is restarted.
          _ <- itemsRef.update { items =>
                val item = items(blockHash).copy(isDownloading = false, isError = true)
                items + (blockHash -> item)
              }
        } yield ()

        finish.attempt *> run
    }

  /** Add a new item to the schedule. */
  private def add(summary: BlockSummary, source: Node, relay: Boolean): F[Item] =
    for {
      items <- itemsRef.get
      item <- items.get(summary.blockHash) map { existing =>
               Sync[F].pure {
                 existing.copy(sources = existing.sources + source, relay = existing.relay || relay)
               }
             } getOrElse {
               // Collect which dependencies have already been downloaded.
               dependencies(summary).toList.traverse { hash =>
                 if (items.contains(hash)) Sync[F].pure(hash -> false)
                 else isDownloaded(hash).map(hash            -> _)
               } map { deps =>
                 val pending = deps.filterNot(_._2).map(_._1).toSet
                 Item(summary, Set(source), relay, pending)
               }
             }
      // This is only called in `run` which is is synchronized so there is no race condition.
      _ <- itemsRef.update(_ + (summary.blockHash -> item))
    } yield item

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
  private def startWorker(item: Item): F[Unit] =
    for {
      _      <- itemsRef.update(_ + (item.summary.blockHash -> item.copy(isDownloading = true)))
      worker <- Concurrent[F].start(download(item.summary.blockHash))
      _      <- workersRef.update(_ + (item.summary.blockHash -> worker))
    } yield ()

  // Just say which block hash to download, try all possible sources.
  private def download(blockHash: ByteString): F[Unit] = {
    val id      = base16(blockHash)
    val failure = signal.put(Signal.DownloadFailure(blockHash))
    val success = signal.put(Signal.DownloadSuccess(blockHash))

    // Try to download until we succeed or give up.
    def loop(tried: Set[Node]): F[Unit] = {
      def tryDownload(summary: BlockSummary, source: Node, relay: Boolean) =
        for {
          block <- fetchAndRestore(source, blockHash)
          _     <- backend.validateBlock(block)
          _     <- backend.storeBlock(block)
          // This could arguably be done by `storeBlock` but this way it's explicit,
          // so we don't forget to talk to both kind of storages.
          _ <- backend.storeBlockSummary(summary)
          // TODO: Gossip
          _ <- success
        } yield ()

      // Get the latest sources.
      itemsRef.get.map(_(blockHash)).flatMap { item =>
        (item.sources -- tried).headOption match {
          case Some(source) =>
            tryDownload(item.summary, source, item.relay).recoverWith {
              case NonFatal(ex) =>
                // TODO: Exponential backoff, pick another node, try to store again, etc.
                Log[F].error(s"Failed to download block $id from ${source.host}", ex) *>
                  loop(tried + source)
            }

          case None =>
            Log[F].error(
              s"Could not download block $id from any of the sources; tried ${tried.size}."
            ) *> failure
        }
      }
    }

    // Make sure the manager knows we're done, even if we fail unexpectedly.
    loop(Set.empty) recoverWith {
      case NonFatal(_) => failure
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
