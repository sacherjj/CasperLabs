package io.casperlabs.node.api

import cats.ApplicativeError
import cats.syntax.applicative._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc

object Utils {
  def toKey[F[_]](keyType: String, keyBytes: ByteString)(
      implicit appErr: ApplicativeError[F, Throwable]
  ): F[ipc.Key] = {
    keyType.toLowerCase match {
      case "hash" =>
        keyBytes.size match {
          case 32 => ipc.Key(ipc.Key.KeyInstance.Hash(ipc.KeyHash(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type hash must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "uref" =>
        keyBytes.size match {
          case 32 => ipc.Key(ipc.Key.KeyInstance.Uref(ipc.KeyURef(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type uref must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "address" =>
        keyBytes.size match {
          case 32 => ipc.Key(ipc.Key.KeyInstance.Account(ipc.KeyAddress(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type address must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case _ =>
        appErr.raiseError(
          new IllegalArgumentException(
            s"Key variant $keyType not valid. Must be one of hash, uref, address."
          )
        )
    }
  }
}
