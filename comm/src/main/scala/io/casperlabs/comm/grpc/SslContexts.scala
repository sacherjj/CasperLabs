package io.casperlabs.comm.grpc

import io.casperlabs.comm.transport.HostnameTrustManagerFactory
import io.grpc.netty.GrpcSslContexts
import io.netty.handler.ssl.{ClientAuth, SslContext, SslContextBuilder}
import java.io.ByteArrayInputStream

object SslContexts {
  // For mutual TLS.
  def forClient(cert: String, privateKey: String): SslContext = {
    val cis = new ByteArrayInputStream(cert.getBytes())
    val kis = new ByteArrayInputStream(privateKey.getBytes())

    GrpcSslContexts.forClient
      .trustManager(HostnameTrustManagerFactory.Instance)
      .keyManager(cis, kis)
      .build
  }

  // For server-only TLS.
  def forClientUnauthenticated: SslContext =
    GrpcSslContexts.forClient
      .trustManager(HostnameTrustManagerFactory.Instance)
      .build

  // For mutual or server-only TLS, based on the `clientAuth` setting.
  def forServer(
      cert: String,
      privateKey: String,
      clientAuth: ClientAuth
  ): SslContext = {
    val cis = new ByteArrayInputStream(cert.getBytes())
    val kis = new ByteArrayInputStream(privateKey.getBytes())

    GrpcSslContexts
      .configure(SslContextBuilder.forServer(cis, kis))
      .trustManager(HostnameTrustManagerFactory.Instance)
      .clientAuth(clientAuth)
      .build
  }
}
