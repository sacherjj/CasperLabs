package io.casperlabs.storage.era

import cats._
import cats.implicits._
import cats.effect.Sync
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import java.util.concurrent.TimeUnit
import io.casperlabs.casper.consensus.Era
import io.casperlabs.storage.util.DoobieCodecs
import io.casperlabs.storage.BlockHash

class SQLiteEraStorage[F[_]: Sync](
    tickUnit: TimeUnit,
    readXa: Transactor[F],
    writeXa: Transactor[F]
) extends EraStorage[F]
    with DoobieCodecs {

  override def addEra(era: Era): F[Boolean] = {
    val hash        = era.keyBlockHash
    val parentHash  = Option(era.parentKeyBlockHash).filterNot(_.isEmpty)
    val startMillis = TimeUnit.MILLISECONDS.convert(era.startTick, tickUnit)
    val endMillis   = TimeUnit.MILLISECONDS.convert(era.endTick, tickUnit)

    val insertEra =
      sql"""INSERT OR IGNORE INTO eras
            (hash, parent_hash, start_millis, end_millis, start_tick, end_tick, data)
            VALUES ($hash, $parentHash, $startMillis, $endMillis, ${era.startTick}, ${era.endTick}, $era)
            """.update.run

    insertEra.transact(writeXa).map(_ > 0)
  }

  override def getEra(keyBlockHash: BlockHash): F[Option[Era]] =
    sql"""SELECT data FROM eras WHERE hash=$keyBlockHash""".query[Era].option.transact(readXa)

  override def getChildEras(keyBlockHash: BlockHash): F[Set[Era]] =
    sql"""SELECT data FROM eras WHERE parent_hash=$keyBlockHash"""
      .query[Era]
      .to[Set]
      .transact(readXa)

  override def getChildlessEras: F[Set[Era]] =
    sql"""SELECT data
          FROM   eras e
          WHERE  NOT EXISTS (
            SELECT 1 FROM eras c
            WHERE  c.parent_hash = e.hash
          )
      """.query[Era].to[Set].transact(readXa)
}

object SQLiteEraStorage {
  def create[F[_]: Sync](
      tickUnit: TimeUnit,
      readXa: Transactor[F],
      writeXa: Transactor[F]
  ): F[EraStorage[F]] = Sync[F].delay {
    new SQLiteEraStorage[F](tickUnit, readXa, writeXa)
  }
}
