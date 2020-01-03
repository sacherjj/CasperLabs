package io.casperlabs.storage.era

import cats._
import cats.implicits._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.casper.consensus.Era
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.storage.BlockHash

trait EraStorage[F[_]] {
  def addEra(era: Era): F[Unit]

  /** Retrieve the era, if it exists, by its key block hash. */
  def getEra(eraId: BlockHash): F[Option[Era]]

  /** Retrieve the era, or raise an error if it's not found. */
  def getEraUnsafe(eraId: BlockHash)(implicit E: MonadThrowable[F]): F[Era] =
    getEra(eraId) flatMap {
      MonadThrowable[F].fromOption(
        _,
        new NoSuchElementException(s"Era ${Base16.encode(eraId.toByteArray)} could not be found.")
      )
    }

  /** Retrieve the child eras from the era tree. */
  def getChildEras(eraId: BlockHash): F[Set[Era]]
}
