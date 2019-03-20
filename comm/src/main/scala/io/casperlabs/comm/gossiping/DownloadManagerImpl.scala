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
import io.casperlabs.shared.Compression

object DownloadManagerImpl {

  /** Messages the DM uses inside its scheduler "queue". */
  sealed trait Signal
  object Signal {

    /** Tell the manager to download a new block. */
    case class Download(summary: BlockSummary, source: Node, relay: Boolean) extends Signal

    /** Tell the manager that a block has been downloaded. */
    case class Downloaded(blockHash: ByteString) extends Signal
  }

  /** Start the download manager. */
  def apply[F[_]: Sync: Concurrent](
      maxParallelDownloads: Int,
      connector: Node => F[GossipService[F]],
      validate: Block => F[Unit],
      store: Block => F[Unit]
  ): Resource[F, DownloadManager[F]] =
    Resource.make {
      for {
        workersRef <- Ref.of(Map.empty[ByteString, Fiber[F, Unit]])
        semaphore  <- Semaphore[F](maxParallelDownloads.toLong)
        signal     <- MVar[F].empty[Signal]
        manager = new DownloadManagerImpl[F](
          workersRef,
          semaphore,
          signal,
          connector
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
}

class DownloadManagerImpl[F[_]: Sync: Concurrent](
    // Keep track of ongoing downloads so we can cancel them.
    workersRef: Ref[F, Map[ByteString, Fiber[F, Unit]]],
    // Limit parallel downloads.
    semaphore: Semaphore[F],
    // Single item control signals for the manager loop.
    signal: MVar[F, DownloadManagerImpl.Signal],
    // Establish gRPC connection to another node.
    // TODO: Handle connection errors.
    connector: Node => F[GossipService[F]]
    // TODO: Ref to a DAG to keep track of dependencies.
) extends DownloadManager[F] {

  import DownloadManagerImpl._

  override def scheduleDownload(summary: BlockSummary, source: Node, relay: Boolean): F[Unit] =
    signal.put {
      Signal.Download(summary, source, relay)
    }

  /** Run the manager loop which listens to signals and starts workers when it can. */
  def run: F[Unit] =
    signal.take.flatMap {
      case item: Signal.Download =>
        // TODO: Add the source to the DAG.
        // TODO: Check that we're not downloading it already.
        // TODO: Check that we haven't downloaded it before.
        // TODO: Check that all dependencies are met and we can start downloading it.
        val start = for {
          worker <- Concurrent[F].start(download(item))
          _      <- workersRef.update(_ + (item.summary.blockHash -> worker))
        } yield ()
        // Recurse outside the flatMap to avoid stack overflow.
        start *> run

      case Signal.Downloaded(blockHash) =>
        // Remove the worker.
        // TODO: Check what else we can download now.
        val finish = for {
          _ <- workersRef.update(_ - blockHash)
        } yield ()

        finish *> run
    }

  // TODO: Just say which block hash to download, try all possible sources.
  def download(item: Signal.Download): F[Unit] =
    for {
      block <- downloadAndRestore(item.source, item.summary.blockHash)
      // TODO: Validate
      // TODO: Store
      // TODO: Gossip
      // TODO: Signal failure.
      _ <- signal.put(Signal.Downloaded(item.summary.blockHash))
    } yield ()

  /** Download a block from the source node and decompress it. */
  def downloadAndRestore(source: Node, blockHash: ByteString): F[Block] = {
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
        stub <- connector(source)
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
                  Right(acc.invalid("Block chunks didn't start with a header."))

                case (acc, chunk) if chunk.getData.isEmpty =>
                  Right(acc.invalid("Block chunks contained empty data frame."))

                case (acc, chunk)
                    if acc.totalSizeSofar + chunk.getData.size > acc.header.get.contentLength =>
                  Right(acc.invalid("Block chunks are exceeding the promised content length."))

                case (acc, chunk) =>
                  Left(acc.append(chunk.getData))
              }

        content <- acc.error map {
                    Sync[F].raiseError(_)
                  } getOrElse {
                    val header  = acc.header.get
                    val content = acc.chunks.toArray.reverse.flatMap(_.toByteArray)
                    if (header.compressionAlgorithm.isEmpty)
                      Sync[F].pure(content)
                    else {
                      Compression
                        .decompress(content, header.originalContentLength)
                        .fold(
                          Sync[F].raiseError[Array[Byte]](
                            GossipError.InvalidChunks("Could not decompress chunks.", source)
                          )
                        )(Sync[F].pure(_))
                    }
                  }

        block <- Sync[F].delay(Block.parseFrom(content))
      } yield block
    }
  }
}
