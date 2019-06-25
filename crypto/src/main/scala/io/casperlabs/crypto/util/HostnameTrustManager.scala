package io.casperlabs.crypto.util

import java.net.Socket
import java.security.cert.{CertificateException, X509Certificate}
import javax.net.ssl._
import io.casperlabs.crypto.codec.Base16

class HostnameTrustManager extends X509ExtendedTrustManager {

  private def reject[T](msg: String): T =
    throw new CertificateException(msg)

  def checkClientTrusted(
      x509Certificates: Array[X509Certificate],
      authType: String,
      socket: Socket
  ): Unit =
    reject("Not allowed validation method")

  def checkClientTrusted(
      x509Certificates: Array[X509Certificate],
      authType: String,
      sslEngine: SSLEngine
  ): Unit = {
    Option(sslEngine.getHandshakeSession)
      .getOrElse(reject("No handshake session"))

    val cert = x509Certificates.head
    val peerHost = CertificateHelper
      .publicAddress(cert.getPublicKey)
      .map(Base16.encode)
      .getOrElse(reject(s"Certificate's public key has the wrong algorithm"))
    checkIdentity(Some(peerHost), cert, "https")
  }

  def checkClientTrusted(x509Certificates: Array[X509Certificate], authType: String): Unit =
    reject("Not allowed validation method")

  def checkServerTrusted(
      x509Certificates: Array[X509Certificate],
      authType: String,
      socket: Socket
  ): Unit =
    reject("Not allowed validation method")

  def checkServerTrusted(
      x509Certificates: Array[X509Certificate],
      authType: String,
      sslEngine: SSLEngine
  ): Unit = {
    val sslSession = Option(sslEngine.getHandshakeSession)
      .getOrElse(reject("No handshake session"))

    // check endpoint identity
    Option(sslEngine.getSSLParameters.getEndpointIdentificationAlgorithm) match {
      case Some(identityAlg) if identityAlg.nonEmpty =>
        val cert     = x509Certificates.head
        val peerHost = Option(sslSession.getPeerHost)

        checkIdentity(peerHost, cert, identityAlg)

        CertificateHelper
          .publicAddress(cert.getPublicKey)
          .map(Base16.encode)
          .filter(_ == peerHost.getOrElse(""))
          .getOrElse(reject(s"Certificate's public address doesn't match the hostname"))

      case _ =>
        reject("No endpoint identification algorithm")
    }
  }

  def checkServerTrusted(x509Certificates: Array[X509Certificate], authType: String): Unit =
    reject("Not allowed validation method")

  def getAcceptedIssuers: Array[X509Certificate] =
    Array.empty

  private def checkIdentity(
      hostname: Option[String],
      cert: X509Certificate,
      algorithm: String
  ): Unit = {
    import sun.security.util.HostnameChecker
    algorithm.toLowerCase match {
      case "https" =>
        val host = hostname
          .filter(_.startsWith("["))
          .filter(_.endsWith("]"))
          .map(h => h.substring(1, h.length - 1))
          .orElse(hostname)
          .getOrElse("")
        HostnameChecker.getInstance(HostnameChecker.TYPE_TLS).`match`(host, cert)
      case _ =>
        reject(s"Unknown identification algorithm: $algorithm")
    }
  }
}
