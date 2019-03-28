package io.casperlabs.comm.auth

import com.google.protobuf.ByteString

/** The identitity which is currently acting on the system. */
sealed trait Principal

object Principal {

  /** The remote party had an SSL certificate. */
  case class Peer(id: ByteString) extends Principal
}
