package io.casperlabs.comm.transport

import java.nio.file.Path

import io.casperlabs.configuration.{ignore, SubConfig}
import io.casperlabs.shared.Resources
import scala.io.Source

final case class Tls(
    certificate: Path,
    key: Path,
    @ignore
    customCertificateLocation: Boolean = false,
    @ignore
    customKeyLocation: Boolean = false,
    secureRandomNonBlocking: Boolean
) extends SubConfig {
  def readCertAndKey: Tls.CertAndKey = {
    val c = Resources.withResource(Source.fromFile(certificate.toFile))(_.mkString)
    val k = Resources.withResource(Source.fromFile(key.toFile))(_.mkString)
    (c, k)
  }
}

object Tls {

  /** PEM file contents */
  type CertAndKey = (String, String)
}
