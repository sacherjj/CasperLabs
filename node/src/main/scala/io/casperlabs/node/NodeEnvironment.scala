package io.casperlabs.node

import java.io.File
import java.security.cert.X509Certificate

import cats.syntax._
import cats.implicits._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.NodeIdentifier
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.util.CertificateHelper
import io.casperlabs.node.configuration.Configuration
import io.casperlabs.shared.Log
import monix.eval.Task

import scala.util._

object NodeEnvironment {

  def create(conf: Configuration)(implicit log: Log[Task]): Task[NodeIdentifier] =
    for {
      dataDir <- Task.delay(conf.server.dataDir.toFile)
      _       <- canCreateDataDir(dataDir)
      _       <- haveAccessToDataDir(dataDir)
      _       <- log.info(s"Using data dir: ${dataDir.getAbsolutePath}")
      _       <- hasCertificate(conf)
      _       <- hasKey(conf)
      name    <- name(conf)
    } yield NodeIdentifier(name)

  private def isValid(pred: Boolean, msg: String): Task[Unit] =
    if (pred) MonadThrowable[Task].raiseError(new java.lang.IllegalArgumentException(msg))
    else ().pure[Task]

  private def name(conf: Configuration): Task[String] = {
    val certificate: Task[X509Certificate] =
      Task
        .delay(CertificateHelper.fromFile(conf.tls.certificate.toFile))
        .recoverWith {
          case e =>
            MonadThrowable[Task].raiseError(
              new java.lang.IllegalArgumentException(
                s"Failed to read the X.509 certificate: ${e.getMessage}"
              )
            )
        }

    for {
      cert <- certificate
      pk   = cert.getPublicKey
      name <- certBase16(CertificateHelper.publicAddress(pk))
    } yield name
  }

  private def certBase16(maybePubAddr: Option[Array[Byte]]): Task[String] =
    maybePubAddr match {
      case Some(bytes) => Base16.encode(bytes).pure[Task]
      case None =>
        MonadThrowable[Task].raiseError(
          new java.lang.IllegalArgumentException(
            "Certificate must contain a secp256r1 EC Public Key"
          )
        )
    }

  private def canCreateDataDir(dataDir: File): Task[Unit] = isValid(
    !dataDir.exists() && !dataDir.mkdir(),
    s"The data dir must be a directory and have read and write permissions:\\n${dataDir.getAbsolutePath}"
  )

  private def haveAccessToDataDir(dataDir: File): Task[Unit] = isValid(
    !dataDir.isDirectory || !dataDir.canRead || !dataDir.canWrite,
    s"The data dir must be a directory and have read and write permissions:\n${dataDir.getAbsolutePath}"
  )

  private def hasCertificate(conf: Configuration): Task[Unit] = isValid(
    !conf.tls.certificate.toFile.exists(),
    s"Certificate file ${conf.tls.certificate} not found"
  )

  private def hasKey(conf: Configuration): Task[Unit] = isValid(
    !conf.tls.key.toFile.exists(),
    s"Secret key file ${conf.tls.key} not found"
  )
}
