package io.casperlabs.storage.dag

import cats.Apply
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
  // Do not forget updating Flyway migration scripts at:
  // resources/db/migrations

  private def msg(b: BlockHash): String = Base16.encode(b.toByteArray).take(10)

  override def getRepresentation: F[DagRepresentation[F]] =
    (this: DagRepresentation[F]).pure[F]
  override def insert(block: Block): F[DagRepresentation[F]] = {
    val blockSummary = BlockSummary.fromBlock(block)

    val blockMetadataQuery =
      sql"""|INSERT OR IGNORE INTO block_metadata
            |(block_hash, rank, data)
            |VALUES (${block.blockHash}, ${block.rank}, ${blockSummary.toByteString})
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

      Update[(Validator, BlockHash)](
        """|INSERT OR REPLACE INTO validator_latest_messages
           |(validator, block_hash)
           |VALUES (?, ?)
           |""".stripMargin
      ).updateMany(toUpdateValidators.map((_, blockSummary.blockHash)))
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
  ): F[Vector[Vector[BlockHash]]] =
    sql"""|SELECT rank, block_hash
          |FROM block_metadata
          |WHERE rank>=$startBlockNumber AND rank<=$endBlockNumber
          |ORDER BY rank
          |""".stripMargin
      .query[(Long, BlockHash)]
      .to[Vector]
      .transact(xa)
      .flatMap(rearrangeSQLiteResult)

  override def topoSort(startBlockNumber: Long): F[Vector[Vector[BlockHash]]] =
    sql"""SELECT rank, block_hash
         |FROM block_metadata
         |WHERE rank>=$startBlockNumber
         |ORDER BY rank""".stripMargin
      .query[(Long, BlockHash)]
      .to[Vector]
      .transact(xa)
      .flatMap(rearrangeSQLiteResult)

  override def topoSortTail(tailLength: Int): F[Vector[Vector[BlockHash]]] =
    sql"""|SELECT a.rank, a.block_hash
          |FROM block_metadata a
          |INNER JOIN (
          | SELECT max(rank) max_rank FROM block_metadata
          |) b
          |ON a.rank>b.max_rank-$tailLength
          |ORDER BY a.rank
          |""".stripMargin
      .query[(Long, BlockHash)]
      .to[Vector]
      .transact(xa)
      .flatMap(rearrangeSQLiteResult)

  /* Returns hashes grouped by ranks, in ascending order. */
  private def rearrangeSQLiteResult(
      blockHashesAndRanks: Vector[(Long, BlockHash)]
  ): F[Vector[Vector[BlockHash]]] = {
    case class State(acc: Vector[Vector[BlockHash]] = Vector.empty, rank: Long = -1)

    blockHashesAndRanks
      .foldLeftM(State()) {
        case (State(_, currentRank), (rank, blockHash)) if currentRank == -1 =>
          State(Vector(Vector(blockHash)), rank).pure[F]
        case (State(acc, currentRank), (rank, blockHash)) if rank == currentRank =>
          State(acc.updated(acc.size - 1, acc.last :+ blockHash), currentRank).pure[F]
        case (State(acc, currentRank), (rank, blockHash)) if rank > currentRank =>
          State(acc :+ Vector(blockHash), rank).pure[F]
        case (State(acc, currentRank), (rank, blockHash)) =>
          new IllegalArgumentException(
            s"Ranks must increase monotonically, got prev rank: $currentRank, prev block: ${msg(
              acc.last.last
            )}, next rank: ${rank}, next block: ${msg(blockHash)}"
          ).raiseError[F, State]
      }
      .map(_.acc)
  }

  override def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
    sql"""|SELECT block_hash
          |FROM validator_latest_messages
          |WHERE validator=$validator""".stripMargin
      .query[BlockHash]
      .option
      .transact(xa)

  override def latestMessage(validator: Validator): F[Option[BlockSummary]] =
    sql"""|SELECT m.data
          |FROM validator_latest_messages v
          |INNER JOIN block_metadata m
          |ON v.validator=$validator AND v.block_hash=m.block_hash""".stripMargin
      .query[BlockSummary]
      .option
      .transact(xa)

  override def latestMessageHashes: F[Map[Validator, BlockHash]] =
    sql"""|SELECT *
          |FROM validator_latest_messages""".stripMargin
      .query[(Validator, BlockHash)]
      .to[List]
      .transact(xa)
      .map(_.toMap)

  override def latestMessages: F[Map[Validator, BlockSummary]] =
    sql"""|SELECT v.validator, m.data
          |FROM validator_latest_messages v
          |INNER JOIN block_metadata m
          |ON m.block_hash=v.block_hash""".stripMargin
      .query[(Validator, BlockSummary)]
      .to[List]
      .transact(xa)
      .map(_.toMap)
}

object SQLiteDagStorage {
  private[dag] def create[F[_]: Sync](
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

  def apply[F[_]: Sync](implicit xa: Transactor[F], met: Metrics[F]): Resource[F, DagStorage[F]] =
    Resource.make(SQLiteDagStorage.create[F])(_.close())
}
