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

object DownloadManagerImpl {

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

    /** Tell the manager to download a new block. */
    case class Download[F[_]](
        summary: BlockSummary,
        source: Node,
        relay: Boolean,
        // Feedback about whether the scheduling itself succeeded.
        scheduleResult: Deferred[F, Either[Throwable, Unit]]
    ) extends Signal[F]

    /** Tell the manager that a block has been downloaded. */
    case class Downloaded[F[_]](blockHash: ByteString) extends Signal[F]
  }

  /** Start the download manager. */
  def apply[F[_]: Sync: Concurrent: Log](
      maxParallelDownloads: Int,
      connectToGossip: Node => F[GossipService[F]],
      backend: Backend[F]
  ): Resource[F, DownloadManager[F]] =
    Resource.make {
      for {
        workersRef <- Ref.of(Map.empty[ByteString, Fiber[F, Unit]])
        semaphore  <- Semaphore[F](maxParallelDownloads.toLong)
        signal     <- MVar[F].empty[Signal[F]]
        manager = new DownloadManagerImpl[F](
          workersRef,
          semaphore,
          signal,
          connectToGossip,
          backend
        )
        managerLoop <- Concurrent[F].start(manager.run)
      } yield (workersRef, managerLoop, manager)
    } {
      case (workersRef, managerLoop, _) =>
        for {
          // TODO: Perhaps it would be nice if after cancelation the `scheduleDownload`
          // wouldn't just block further shcedule attempts but raise an error.
          _       <- managerLoop.cancel.attempt
          workers <- workersRef.get
          _       <- workers.values.toList.map(_.cancel.attempt).sequence.void
        } yield ()
    } map {
      case (_, _, manager) => manager
    }

  /** All dependencies that need to be downloaded before a block. */
  def dependencies(summary: BlockSummary): Seq[ByteString] =
    summary.getHeader.parentHashes ++ summary.getHeader.justifications.map(_.latestBlockHash)
}

class DownloadManagerImpl[F[_]: Sync: Concurrent: Log](
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

  override def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean): F[Unit] =
    for {
      // Feedback about whether we successfully scheduled the item.
      d <- Deferred[F, Either[Throwable, Unit]]
      _ <- signal.put(Signal.Download(summary, source, relay, d))
      _ <- Sync[F].rethrow(d.get)
    } yield ()

  /** Run the manager loop which listens to signals and starts workers when it can. */
  def run: F[Unit] =
    signal.take.flatMap {
      case Signal.Download(summary, source, relay, scheduleResult) =>
        val start = for {
          // At this point we should have already synced and only scheduled things to which we know how to get.
          _ <- ensureNoMissingDependencies(summary)
          // TODO: Check that we're not downloading it already.
          // TODO: Check that we haven't downloaded it before.
          // TODO: Add the source to the DAG.
          // TODO: Check that all dependencies are met and we can start downloading it now, or just later.
          worker <- Concurrent[F].start(download(summary, source, relay))
          _      <- workersRef.update(_ + (summary.blockHash -> worker))
        } yield ()
        // Recurse outside the for comprehension to avoid stack overflow.
        start.attempt.flatMap(scheduleResult.complete(_)) *> run

      case Signal.Downloaded(blockHash) =>
        // Remove the worker.
        // TODO: Check what else we can download now.
        val finish = for {
          _ <- workersRef.update(_ - blockHash)
        } yield ()

        finish *> run
    }

  /** Check that we either have all dependencies already downloaded or scheduled. */
  def ensureNoMissingDependencies(summary: BlockSummary): F[Unit] =
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

  def isScheduled(hash: ByteString): F[Boolean] =
    // TODO: Look it up in the pending DAG.
    Sync[F].pure(false)

  def isDownloaded(hash: ByteString): F[Boolean] =
    backend.hasBlock(hash)

  // TODO: Just say which block hash to download, try all possible sources.
  def download(summary: BlockSummary, source: Node, relay: Boolean): F[Unit] = {
    val id = Base16.encode(summary.blockHash.toByteArray)
    // Try to download until we succeed.
    def loop(): F[Unit] = {
      val tryDownload =
        for {
          // TODO: Pick a source.
          block <- downloadAndRestore(source, summary.blockHash)
          // TODO: Validate
          // TODO: Store
          // TODO: Gossip
        } yield ()

      tryDownload.recoverWith {
        case ex =>
          // TODO: Exponential backoff, pick another node, try to store again, etc.
          Log[F].error(s"Failed to download block $id", ex)
      }
    }
    // Finally tell the manager that we're done.
    Sync[F].guarantee(loop()) {
      signal.put(Signal.Downloaded(summary.blockHash))
    }
  }

  /** Download a block from the source node and decompress it. */
  def downloadAndRestore(source: Node, blockHash: ByteString): F[Block] = {
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
