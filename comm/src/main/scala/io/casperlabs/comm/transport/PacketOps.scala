package io.casperlabs.comm.transport

import java.io._
import java.nio.file._
import java.text.SimpleDateFormat
import java.util.Date

import cats.effect.Sync
import cats.implicits._
import io.casperlabs.comm.CommError
import io.casperlabs.comm.CommError._
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.shared.GracefulClose._

import scala.util.Random

object PacketOps {

  def restore[F[_]: Sync](file: Path): F[CommErr[Packet]] =
    for {
      fin       <- Sync[F].delay(new FileInputStream(file.toFile))
      packetErr <- Sync[F].delay(Packet.parseFrom(fin)).attempt
      resErr <- packetErr match {
                 case Left(th) =>
                   gracefullyClose(fin) *> Left(unabletoRestorePacket(file, th)).pure[F]
                 case Right(packet) => gracefullyClose(fin) *> Right(packet).pure[F]
               }
    } yield resErr

  implicit class RichPacket(packet: Packet) {
    def store[F[_]: Sync](folder: Path): F[CommErr[Path]] =
      for {
        packetFile <- createPacketFile[F](folder, "_packet.bts")
        file       = packetFile.file
        fos        = packetFile.fos
        orErr <- Sync[F].delay {
                  fos.write(packet.toByteArray)
                  fos.flush()
                }.attempt
        resErr <- orErr match {
                   case Left(th) =>
                     gracefullyClose(fos) *> Left(unableToStorePacket(packet, th)).pure[F]
                   case Right(_) =>
                     gracefullyClose(fos) map {
                       case Left(th) => Left(unableToStorePacket(packet, th))
                       case Right(_) => Right(file)
                     }
                 }
      } yield resErr
  }

  final case class PacketFile(file: Path, fos: FileOutputStream)

  def createPacketFile[F[_]: Sync](folder: Path, postfix: String): F[PacketFile] =
    for {
      _        <- Sync[F].delay(folder.toFile.mkdirs())
      t        <- timestamp
      fileName = t + postfix
      file     <- Sync[F].delay(folder.resolve(fileName))
      fos      <- Sync[F].delay(new FileOutputStream(file.toFile))
    } yield PacketFile(file, fos)

  private val TS_FORMAT = "yyyyMMddHHmmss"

  private def timestamp[F[_]: Sync]: F[String] = Sync[F].delay {
    val dateFormat = new SimpleDateFormat(TS_FORMAT)
    val bytes      = Array.ofDim[Byte](4)
    Random.nextBytes(bytes)
    val date = dateFormat.format(new Date())
    val hex  = Base16.encode(bytes)
    s"${date}_$hex"
  }
}
