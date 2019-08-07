package io.casperlabs.blockstorage.util.fileIO

import java.io.{FileNotFoundException, FileOutputStream}
import java.nio.file.Path

import cats.effect.Sync
import cats.implicits._
import io.casperlabs.blockstorage.util.fileIO.IOError.RaiseIOError

final case class FileOutputStreamIO[F[_]: Sync: RaiseIOError] private (
    private val stream: FileOutputStream
) {
  def write(bytes: Array[Byte]): F[Unit] =
    handleIo(stream.write(bytes), StreamWriteFailed.apply)

  def flush: F[Unit] =
    handleIo(stream.flush(), StreamFlushFailed.apply)

  def close: F[Unit] =
    handleIo(stream.close(), ClosingFailed.apply)
}

object FileOutputStreamIO {
  def open[F[_]: Sync: RaiseIOError](path: Path, append: Boolean): F[FileOutputStreamIO[F]] =
    handleIo(new FileOutputStream(path.toFile, append), {
      case e: FileNotFoundException => FileNotFound(e)
      case e                        => UnexpectedIOError(e)
    }).map(FileOutputStreamIO.apply[F])
}
