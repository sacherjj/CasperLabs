package io.casperlabs.comm.grpc

import io.grpc.{Metadata, ServerCall, Status}

// Similar to io.grpc.ForwardingServerCall but rolled into one.
class ForwardingServerCall[A, B](delegate: ServerCall[A, B]) extends ServerCall[A, B] {

  override def sendMessage(message: B) =
    delegate.sendMessage(message)

  override def request(numMessages: Int) =
    delegate.request(numMessages)

  override def sendHeaders(headers: Metadata) =
    delegate.sendHeaders(headers)

  override def isReady() =
    delegate.isReady

  override def close(status: Status, trailers: Metadata) =
    delegate.close(status, trailers)

  override def isCancelled() =
    delegate.isCancelled

  override def setMessageCompression(enabled: Boolean) =
    delegate.setMessageCompression(enabled)

  override def setCompression(compressor: String) =
    delegate.setCompression(compressor)

  override def getAttributes() =
    delegate.getAttributes

  override def getAuthority() =
    delegate.getAuthority

  override def getMethodDescriptor() =
    delegate.getMethodDescriptor
}
