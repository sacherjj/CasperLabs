package io.casperlabs.comm.transport

import io.casperlabs.configuration.{ignore, SubConfig}
import io.casperlabs.shared.Resources
import java.nio.file.Path
import scala.io.Source

final case class Tls(
    certificate: Path,
    key: Path,
    apiCertificate: Path,
    apiKey: Path
) extends SubConfig {
  def readCertAndKey: Tls.CertAndKey =
    read(certificate, key)

  def readApiCertAndKey: Tls.CertAndKey =
    read(apiCertificate, apiKey)

  private def read(cp: Path, kp: Path): Tls.CertAndKey = {
    val c = Resources.withResource(Source.fromFile(cp.toFile))(_.mkString)
    val k = Resources.withResource(Source.fromFile(kp.toFile))(_.mkString)
    (c, k)
  }
}

object Tls {

  /** PEM file contents */
  type CertAndKey = (String, String)
}
