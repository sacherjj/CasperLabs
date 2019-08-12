package io.casperlabs.shared

import java.io.{ByteArrayOutputStream, File}
import java.nio.charset.{Charset, StandardCharsets}
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
      charset: Charset = StandardCharsets.UTF_8,
      options: List[OpenOption] = Nil
  ): F[Unit]

  def readBytesFromResources(name: String): F[Array[Byte]]

  def readStringFromResources(name: String, charset: Charset = StandardCharsets.UTF_8): F[String]
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
          charset: Charset = StandardCharsets.UTF_8
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
          charset: Charset = StandardCharsets.UTF_8,
          options: List[OpenOption] = Nil
      ): F[Unit] = writeBytes(path, data.getBytes(charset), options)

      override def readBytesFromResources(name: String): F[Array[Byte]] = Sync[F].delay {
        val is         = getClass.getClassLoader.getResourceAsStream(name)
        val data       = Array.ofDim[Byte](1024)
        var nRead: Int = is.read(data)
        val buffer     = new ByteArrayOutputStream()
        while (nRead != -1) {
          buffer.write(data, 0, nRead)
          nRead = is.read(data)
        }
        buffer.flush()
        buffer.toByteArray
      }

      override def readStringFromResources(name: String, charset: Charset): F[String] =
        readBytesFromResources(name).map(array => new String(array, charset))
    }

  implicit def eitherTFilesApi[E, F[_]: Monad: FilesAPI]: FilesAPI[EitherT[F, E, ?]] =
    new FilesAPI[EitherT[F, E, ?]] {
      override def readBytes(path: Path): EitherT[F, E, Array[Byte]] =
        FilesAPI[F].readBytes(path).liftM[EitherT[?[_], E, ?]]

      override def readString(
          path: Path,
          charset: Charset
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
          options: List[OpenOption]
      ): EitherT[F, E, Unit] =
        FilesAPI[F].writeBytes(path, data, options).liftM[EitherT[?[_], E, ?]]

      override def writeString(
          path: Path,
          data: String,
          charset: Charset,
          options: List[OpenOption]
      ): EitherT[F, E, Unit] =
        FilesAPI[F].writeString(path, data, charset, options).liftM[EitherT[?[_], E, ?]]

      override def readBytesFromResources(name: String): EitherT[F, E, Array[Byte]] =
        FilesAPI[F].readBytesFromResources(name).liftM[EitherT[?[_], E, ?]]

      override def readStringFromResources(
          name: String,
          charset: Charset
      ): EitherT[F, E, String] =
        FilesAPI[F].readStringFromResources(name, charset).liftM[EitherT[?[_], E, ?]]
    }
}
