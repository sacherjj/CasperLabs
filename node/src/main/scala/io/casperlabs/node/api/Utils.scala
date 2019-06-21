package io.casperlabs.node.api

import cats.ApplicativeError
import cats.syntax.applicative._
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.ipc
import io.casperlabs.casper.consensus.state

object Utils {
  def toKey[F[_]](keyType: String, keyValue: String)(
      implicit appErr: ApplicativeError[F, Throwable]
  ): F[state.Key] = {
    val keyBytes = ByteString.copyFrom(Base16.decode(keyValue))
    keyType.toLowerCase match {
      case "hash" =>
        keyBytes.size match {
          case 32 => state.Key(state.Key.Value.Hash(state.Key.Hash(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type hash must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "uref" =>
        keyBytes.size match {
          case 32 => state.Key(state.Key.Value.Uref(state.Key.URef(keyBytes))).pure[F]
          case n =>
            appErr.raiseError(
              new IllegalArgumentException(
                s"Key of type uref must have exactly 32 bytes, $n =/= 32 provided."
              )
            )
        }
      case "address" =>
        keyBytes.size match {
          case 32 => state.Key(state.Key.Value.Address(state.Key.Address(keyBytes))).pure[F]
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
