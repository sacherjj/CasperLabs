package io.casperlabs.shared

import java.nio.charset.Charset
import java.nio.file.{Files, OpenOption, Path}

import cats.Monad
import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import io.casperlabs.catscontrib.Catscontrib._
import simulacrum.typeclass

/* FilesAPI typeclass. Use .attempt to handle errors if you care. */
@typeclass trait FilesAPI[F[_]] {
  def readBytes(path: Path): F[Array[Byte]]

  def readString(path: Path, charset: Charset = Charset.defaultCharset()): F[String]

  /* If `path` doesn't exist then will try to create it with all non-existent parent directories */
  def writeBytes(path: Path, data: Array[Byte], options: List[OpenOption] = Nil): F[Unit]

  /* If `path` doesn't exist then will try to create it with all non-existent parent directories */
  def writeString(
      path: Path,
      data: String,
      charset: Charset = Charset.defaultCharset(),
      options: List[OpenOption] = Nil
  ): F[Unit]
}

object FilesAPI {
  private implicit val logSource: LogSource = LogSource(this.getClass)

  def create[F[_]: Sync: Log]: FilesAPI[F] =
    new FilesAPI[F] {
      override def readBytes(path: Path): F[Array[Byte]] =
        Sync[F]
          .delay {
            Files.readAllBytes(path)
          }

      override def readString(
          path: Path,
          charset: Charset = Charset.defaultCharset()
      ): F[String] =
        readBytes(path).map(array => new String(array, charset))

      override def writeBytes(
          path: Path,
          data: Array[Byte],
          options: List[OpenOption] = Nil
      ): F[Unit] =
        (for {
          _ <- Sync[F]
                .delay(Files.createDirectories(path.getParent))
                .whenA(!path.getParent.toFile.exists())
          _ <- Sync[F]
                .delay {
                  Files.write(path, data, options: _*)
                }
        } yield ()).onError {
          case e =>
            Log[F].error(s"Failed to write data to file: $path", e)
        }

      override def writeString(
          path: Path,
          data: String,
          charset: Charset = Charset.defaultCharset(),
          options: List[OpenOption] = Nil
      ): F[Unit] = writeBytes(path, data.getBytes(charset), options)
    }

  implicit def eitherTFilesApi[E, F[_]: Monad: FilesAPI]: FilesAPI[EitherT[F, E, ?]] =
    new FilesAPI[EitherT[F, E, ?]] {
      override def readBytes(path: Path): EitherT[F, E, Array[Byte]] =
        FilesAPI[F].readBytes(path).liftM[EitherT[?[_], E, ?]]

      override def readString(
          path: Path,
          charset: Charset = Charset.defaultCharset()
      ): EitherT[F, E, String] =
        FilesAPI[F]
          .readString(
            path,
            charset
          )
          .liftM[EitherT[?[_], E, ?]]

      override def writeBytes(
          path: Path,
          data: Array[Byte],
          options: List[OpenOption] = Nil
      ): EitherT[F, E, Unit] =
        FilesAPI[F].writeBytes(path, data, options).liftM[EitherT[?[_], E, ?]]

      override def writeString(
          path: Path,
          data: String,
          charset: Charset = Charset.defaultCharset(),
          options: List[OpenOption] = Nil
      ): EitherT[F, E, Unit] =
        FilesAPI[F].writeString(path, data, charset, options).liftM[EitherT[?[_], E, ?]]
    }
}
