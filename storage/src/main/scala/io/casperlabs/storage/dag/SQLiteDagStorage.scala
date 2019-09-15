package io.casperlabs.storage.dag

import cats._
import cats.data._
import cats.effect._
import cats.implicits._
import doobie._
import doobie.implicits._
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.storage.DagStorageMetricsSource
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.MeteredDagStorage
import io.casperlabs.storage.util.DoobieCodecs

class SQLiteDagStorage[F[_]: Bracket[?[_], Throwable]](
    xa: Transactor[F]
) extends DagStorage[F]
    with DagRepresentation[F]
    with DoobieCodecs {
  import SQLiteDagStorage.StreamOps

  override def getRepresentation: F[DagRepresentation[F]] =
    (this: DagRepresentation[F]).pure[F]
  override def insert(block: Block): F[DagRepresentation[F]] = {
    val blockSummary = BlockSummary.fromBlock(block)

    val blockMetadataQuery =
      sql"""|INSERT OR IGNORE INTO block_metadata
            |(block_hash, validator, rank, data)
            |VALUES (${block.blockHash}, ${block.validatorPublicKey}, ${block.rank}, ${blockSummary.toByteString})
            |""".stripMargin.update.run

    val justificationsQuery =
      Update[(BlockHash, BlockHash)](
        """|INSERT OR IGNORE INTO block_justifications 
           |(justification_block_hash, block_hash) 
           |VALUES (?, ?)""".stripMargin
      ).updateMany(
        blockSummary.justifications
          .map(j => (j.latestBlockHash, blockSummary.blockHash))
          .toList
      )

    val latestMessagesQuery = {
      val newValidators = block.state.bonds
        .map(_.validatorPublicKey)
        .toSet
        .diff(block.justifications.map(_.validatorPublicKey).toSet)
      val validator = block.validatorPublicKey

      val toUpdateValidators = if (block.isGenesisLike) {
        // For Genesis, all validators are "new".
        newValidators.toList
      } else {
        // For any other block, only validator that produced it
        // needs to have its "latest message" updated.
        List(validator)
      }

      Update[(Validator, BlockHash, Long)](
        """|INSERT OR IGNORE INTO validator_latest_messages
           |(validator, block_hash, rank)
           |VALUES (?, ?, ?)
           |""".stripMargin
      ).updateMany(toUpdateValidators.map((_, blockSummary.blockHash, blockSummary.rank)))
    }

    val topologicalSortingQuery =
      if (block.isGenesisLike) {
        ().pure[ConnectionIO]
      } else {
        Update[(BlockHash, BlockHash)](
          """|INSERT OR IGNORE INTO block_parents 
             |(parent_block_hash, child_block_hash) 
             |VALUES (?, ?)""".stripMargin
        ).updateMany(blockSummary.parentHashes.map((_, blockSummary.blockHash)).toList).void
      }

    val transaction = for {
      _ <- blockMetadataQuery
      _ <- justificationsQuery
      _ <- latestMessagesQuery
      _ <- topologicalSortingQuery
    } yield ()

    for {
      _   <- transaction.transact(xa)
      dag <- getRepresentation
    } yield dag
  }

  override def checkpoint(): F[Unit] = ().pure[F]

  override def clear(): F[Unit] =
    (for {
      _ <- sql"DELETE FROM block_parents".update.run
      _ <- sql"DELETE FROM block_justifications".update.run
      _ <- sql"DELETE FROM validator_latest_messages".update.run
      _ <- sql"DELETE FROM block_metadata".update.run
    } yield ()).transact(xa)

  override def close(): F[Unit] = ().pure[F]

  override def children(blockHash: BlockHash): F[Set[BlockHash]] =
    sql"""|SELECT child_block_hash
          |FROM block_parents 
          |WHERE parent_block_hash=$blockHash""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(xa)

  override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
    sql"""|SELECT block_hash 
          |FROM block_justifications 
          |WHERE justification_block_hash=$blockHash""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(xa)

  override def lookup(blockHash: BlockHash): F[Option[BlockSummary]] =
    sql"""|SELECT data 
          |FROM block_metadata 
          |WHERE block_hash=$blockHash""".stripMargin
      .query[BlockSummary]
      .option
      .transact(xa)

  override def contains(blockHash: BlockHash): F[Boolean] =
    sql"""|SELECT 1 
          |FROM block_metadata 
          |WHERE block_hash=$blockHash""".stripMargin
      .query[Long]
      .option
      .transact(xa)
      .map(_.nonEmpty)

  override def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockHash]] =
    sql"""|SELECT rank, block_hash
          |FROM block_metadata
          |WHERE rank>=$startBlockNumber AND rank<=$endBlockNumber
          |ORDER BY rank
          |""".stripMargin
      .query[(Long, BlockHash)]
      .stream
      .transact(xa)
      .groupByRank

  override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockHash]] =
    sql"""SELECT rank, block_hash
         |FROM block_metadata
         |WHERE rank>=$startBlockNumber
         |ORDER BY rank""".stripMargin
      .query[(Long, BlockHash)]
      .stream
      .transact(xa)
      .groupByRank

  override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockHash]] =
    sql"""|SELECT a.rank, a.block_hash
          |FROM block_metadata a
          |INNER JOIN (
          | SELECT max(rank) max_rank FROM block_metadata
          |) b
          |ON a.rank>b.max_rank-$tailLength
          |ORDER BY a.rank
          |""".stripMargin
      .query[(Long, BlockHash)]
      .stream
      .transact(xa)
      .groupByRank

  override def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
    sql"""|SELECT block_hash
          |FROM validator_latest_messages a
          |WHERE validator = $validator
          |  AND NOT EXISTS(
          |        SELECT 1
          |        FROM validator_latest_messages b
          |        WHERE a.validator = b.validator
          |          AND a.rank < b.rank
          |    )""".stripMargin
      .query[BlockHash]
      .option
      .transact(xa)

  override def latestMessage(validator: Validator): F[Option[BlockSummary]] =
    sql"""|SELECT data
          |FROM (SELECT validator, block_hash
          |      FROM validator_latest_messages a
          |      WHERE validator = $validator
          |        AND NOT EXISTS(
          |              SELECT 1
          |              FROM validator_latest_messages b
          |              WHERE a.validator = b.validator
          |                AND a.rank < b.rank
          |          )) c
          |         INNER JOIN block_metadata ON c.block_hash = block_metadata.block_hash""".stripMargin
      .query[BlockSummary]
      .option
      .transact(xa)

  override def latestMessageHashes: F[Map[Validator, BlockHash]] =
    sql"""|SELECT validator, block_hash
          |FROM validator_latest_messages a
          |WHERE NOT EXISTS(
          |    SELECT 1
          |    FROM validator_latest_messages b
          |    WHERE a.validator = b.validator
          |      AND a.rank < b.rank
          |)""".stripMargin
      .query[(Validator, BlockHash)]
      .to[List]
      .transact(xa)
      .map(_.toMap)

  override def latestMessages: F[Map[Validator, BlockSummary]] =
    sql"""|SELECT c.validator, data
          |FROM (SELECT validator, block_hash
          |      FROM validator_latest_messages a
          |      WHERE NOT EXISTS(
          |              SELECT 1
          |              FROM validator_latest_messages b
          |              WHERE a.validator = b.validator
          |                AND a.rank < b.rank
          |          )) c
          |         INNER JOIN block_metadata 
          |                    ON c.block_hash = block_metadata.block_hash""".stripMargin
      .query[(Validator, BlockSummary)]
      .to[List]
      .transact(xa)
      .map(_.toMap)
}

object SQLiteDagStorage {

  private case class Fs2State(
      buffer: Vector[BlockHash] = Vector.empty,
      rank: Long = -1
  )

  private implicit class StreamOps[F[_]: Bracket[?[_], Throwable]](
      val stream: fs2.Stream[F, (Long, BlockHash)]
  ) {
    private type ErrorOr[A] = Either[Throwable, A]
    private type G[A]       = StateT[ErrorOr, Vector[Vector[BlockHash]], A]

    /* Returns hashes grouped by ranks, in ascending order. */
    def groupByRank: fs2.Stream[F, Vector[BlockHash]] = go(Fs2State(), stream).stream

    /** Check [[https://fs2.io/guide.html#statefully-transforming-streams]]
      * and [[https://blog.leifbattermann.de/2017/10/08/error-and-state-handling-with-monad-transformers-in-scala/]]
      * if it's hard to understand what's going on
      *  */
    private def go(
        state: Fs2State,
        s: fs2.Stream[F, (Long, BlockHash)]
    ): fs2.Pull[F, Vector[BlockHash], Unit] =
      s.pull.uncons.flatMap {
        case Some((chunk, streamTail)) =>
          chunk
            .foldLeftM[G, Fs2State](state) {
              case (Fs2State(_, currentRank), (rank, blockHash)) if currentRank == -1 =>
                Fs2State(Vector(blockHash), rank).pure[G]
              case (Fs2State(acc, currentRank), (rank, blockHash)) if rank == currentRank =>
                Fs2State(acc :+ blockHash, currentRank).pure[G]
              case (Fs2State(acc, currentRank), (rank, blockHash)) if rank > currentRank =>
                put(acc) >> Fs2State(Vector(blockHash), rank).pure[G]
              case (Fs2State(acc, currentRank), (rank, blockHash)) =>
                error(
                  new IllegalArgumentException(
                    s"Ranks must increase monotonically, got prev rank: $currentRank, prev block: ${msg(
                      acc.last
                    )}, next rank: ${rank}, next block: ${msg(blockHash)}"
                  )
                )
            }
            .run(Vector.empty)
            .fold(
              ex => fs2.Pull.raiseError[F](ex), {
                case (output, newState) =>
                  fs2.Pull.output(fs2.Chunk.vector(output)) >> go(newState, streamTail)
              }
            )
        case None => fs2.Pull.output(fs2.Chunk(state.buffer)) >> fs2.Pull.done
      }

    private def put(blockHashes: Vector[BlockHash]) =
      StateT.modify[ErrorOr, Vector[Vector[BlockHash]]](_ :+ blockHashes)

    private def error(e: Throwable) =
      StateT.liftF[ErrorOr, Vector[Vector[BlockHash]], Fs2State](e.asLeft[Fs2State])

    private def msg(b: BlockHash): String = Base16.encode(b.toByteArray).take(10)
  }

  private[storage] def create[F[_]: Sync](
      implicit xa: Transactor[F],
      met: Metrics[F]
  ): F[DagStorage[F]] =
    for {
      dagStorage <- Sync[F].delay(new SQLiteDagStorage[F](xa) with MeteredDagStorage[F] {
                     override implicit val m: Metrics[F] = met
                     override implicit val ms: Source =
                       Metrics.Source(DagStorageMetricsSource, "sqlite")
                     override implicit val a: Apply[F] = Sync[F]
                   })
    } yield dagStorage: DagStorage[F]
}
