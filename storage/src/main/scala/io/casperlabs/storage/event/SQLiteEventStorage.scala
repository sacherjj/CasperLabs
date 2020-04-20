package io.casperlabs.storage.event

import cats._
import cats.implicits._
import cats.effect.Sync
import doobie._
import doobie.implicits._
import io.casperlabs.casper.consensus.info.Event
import io.casperlabs.storage.util.DoobieCodecs
import io.casperlabs.shared.Time

class SQLiteEventStorage[F[_]: Sync: Time](
    readXa: Transactor[F],
    writeXa: Transactor[F]
) extends EventStorage[F]
    with DoobieCodecs {
  override def storeEvents(values: Seq[Event.Value]): F[List[Event]] = {
    def insert(now: Long, value: Event.Value): ConnectionIO[Event] =
      for {
        _  <- sql"""INSERT INTO events(create_time_millis, value) VALUES ($now, $value)""".update.run
        id <- sql"""SELECT last_insert_rowid()""".query[Long].unique
      } yield Event().withEventId(id).withValue(value)

    for {
      now    <- Time[F].currentMillis
      events <- values.toList.traverse(insert(now, _)).transact(writeXa)
    } yield events
  }

  override def getEvents(minId: Long, maxId: Long): fs2.Stream[F, Event] = {
    val fromQuery   = sql"""
    SELECT id, value
    FROM   events
    WHERE  id >= $minId
    ORDER BY id
    """
    val fromToQuery = sql"""
    SELECT id, value
    FROM   events
    WHERE  id >= $minId AND id <= $maxId
    ORDER BY id
    """

    val query =
      if (maxId > 0)
        fromToQuery
      else fromQuery

    query
      .query[(Long, Event.Value)]
      .map {
        case (id, value) =>
          Event().withEventId(id).withValue(value)
      }
      .stream
      .transact(readXa)
  }

}

object SQLiteEventStorage {
  def create[F[_]: Sync: Time](
      readXa: Transactor[F],
      writeXa: Transactor[F]
  ): F[EventStorage[F]] = Sync[F].delay {
    new SQLiteEventStorage[F](readXa, writeXa)
  }
}
