package io.casperlabs.comm.transport

import java.security.KeyStore
import javax.net.ssl._
import io.casperlabs.crypto.util.HostnameTrustManager
import io.netty.handler.ssl.util.SimpleTrustManagerFactory

class HostnameTrustManagerFactory private () extends SimpleTrustManagerFactory {
  def engineInit(keyStore: KeyStore): Unit                                 = {}
  def engineInit(managerFactoryParameters: ManagerFactoryParameters): Unit = {}
  def engineGetTrustManagers(): Array[TrustManager] =
    Array[TrustManager](hostNameTrustManager)

  private[this] val hostNameTrustManager = new HostnameTrustManager()
}

object HostnameTrustManagerFactory {
  val Instance: HostnameTrustManagerFactory = new HostnameTrustManagerFactory()
}
