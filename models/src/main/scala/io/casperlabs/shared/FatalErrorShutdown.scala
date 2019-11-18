package io.casperlabs.shared

import cats.effect.Sync
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16

object FatalError {

  /** Stop the node because of self equivocation detected.
    *
    * NOTE: We are throwing here, instead of raising an error, since raised errors are not being
    * caught by `UncaughtExceptionHandler` automatically (in contrast to throwing). Even fatal VM errors.
    * @param blockHash block that created a self equivocation.
    * @tparam F
    * @return
    */
  def selfEquivocationError[F[_]: Sync](blockHash: ByteString): F[Unit] =
    Sync[F].delay(
      throw FatalErrorShutdown(
        s"Node has detected it's own equivocation with block ${Base16.encode(blockHash.toByteArray).take(10)}"
      )
    )
}
