package io.casperlabs.casper.highway.mocks

import cats._
import cats.implicits._
import cats.effect._
import cats.effect.concurrent.Ref
import io.casperlabs.casper.consensus.Era
import io.casperlabs.storage.BlockHash
import io.casperlabs.storage.era.EraStorage

class MockEraStorage[F[_]: Applicative](
    erasRef: Ref[F, Map[BlockHash, Era]]
) extends EraStorage[F] {
  def addEra(era: Era): F[Boolean] =
    erasRef.modify { es =>
      es.updated(era.keyBlockHash, era) -> !es.contains(era.keyBlockHash)
    }

  def getEra(keyBlockHash: BlockHash): F[Option[Era]] =
    erasRef.get.map(_.get(keyBlockHash))

  def getChildEras(keyBlockHash: BlockHash): F[Set[Era]] = ???
  def getChildlessEras: F[Set[Era]]                      = ???
}

object MockEraStorage {
  def apply[F[_]: Sync] =
    for {
      erasRef <- Ref.of[F, Map[BlockHash, Era]](Map.empty)
    } yield new MockEraStorage(erasRef)
}
