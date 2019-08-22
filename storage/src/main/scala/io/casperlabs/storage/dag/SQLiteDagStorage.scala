package io.casperlabs.storage.dag

import cats.Apply
import cats.effect._
import cats.implicits._
import com.google.protobuf.ByteString
import doobie._
import doobie.implicits._
import io.casperlabs.casper.consensus.Block
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.storage.{BlockMetadata, DagStorageMetricsSource}
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.MeteredDagStorage

class SQLiteDagStorage[F[_]: Bracket[?[_], Throwable]](
    xa: Transactor[F]
) extends DagStorage[F]
    with DagRepresentation[F] {
  import io.casperlabs.models.BlockImplicits._

  implicit val metaByteString: Meta[ByteString] =
    Meta[Array[Byte]].imap(ByteString.copyFrom)(_.toByteArray)

  implicit val metaBlockMetadata: Meta[BlockMetadata] =
    Meta[Array[Byte]].imap(BlockMetadata.fromBytes)(_.toByteString.toByteArray)

  private def msg(b: BlockHash): String = Base16.encode(b.toByteArray).take(10)

  override def getRepresentation: F[DagRepresentation[F]] =
    (this: DagRepresentation[F]).pure[F]
  override def insert(block: Block): F[DagRepresentation[F]] = {
    val blockMetadata = BlockMetadata.fromBlock(block)

    val justificationsQuery =
      Update[(BlockHash, BlockHash)](
        """|INSERT OR IGNORE INTO dag_storage_justifications 
           |(justification_block_hash, block_hash) 
           |VALUES (?, ?)""".stripMargin
      ).updateMany(
        blockMetadata.justifications.map(j => (j.latestBlockHash, blockMetadata.blockHash))
      )

    val blocksMetadataQuery = {
      val newValidators = block.getHeader.getState.bonds
        .map(_.validatorPublicKey)
        .toSet
        .diff(block.getHeader.justifications.map(_.validatorPublicKey).toSet)
      val validator = block.getHeader.validatorPublicKey

      val toUpdateValidators = if (block.isGenesisLike) {
        // For Genesis, all validators are "new".
        if (newValidators.nonEmpty) {
          newValidators.toList
        } else {
          // We still need to be able to 'lookup' such block
          List(ByteString.EMPTY)
        }
      } else {
        // For any other block, only validator that produced it
        // needs to have its "latest message" updated.
        List(validator)
      }

      Update[(BlockHash, Validator, Long, ByteString)](
        """|INSERT OR IGNORE INTO dag_storage_blocks_metadata
           |(block_hash, validator, rank, data)
           |VALUES (?, ?, ?, ?)
           |""".stripMargin
      ).updateMany(
        toUpdateValidators
          .map((blockMetadata.blockHash, _, blockMetadata.rank, blockMetadata.toByteString))
      )
    }

    val topologicalSortingQuery =
      if (block.isGenesisLike) {
        sql"""|INSERT OR IGNORE INTO dag_storage_topological_sorting 
              |(parent_block_hash, child_block_hash, child_rank) 
              |VALUES (${ByteString.EMPTY}, ${blockMetadata.blockHash}, 0)""".stripMargin.update.run
      } else {
        Update[(BlockHash, BlockHash, Long)](
          """|INSERT OR IGNORE INTO dag_storage_topological_sorting 
             |(parent_block_hash, child_block_hash, child_rank) 
             |VALUES (?, ?, ?)""".stripMargin
        ).updateMany(
          blockMetadata.parents.map((_, blockMetadata.blockHash, blockMetadata.rank))
        )
      }

    val transaction = for {
      _ <- justificationsQuery
      _ <- blocksMetadataQuery
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
      _ <- sql"DELETE FROM dag_storage_topological_sorting".update.run
      _ <- sql"DELETE FROM dag_storage_justifications".update.run
      _ <- sql"DELETE FROM dag_storage_blocks_metadata".update.run
    } yield ()).transact(xa)

  override def close(): F[Unit] = ().pure[F]

  override def children(blockHash: BlockHash): F[Set[BlockHash]] =
    sql"""|SELECT child_block_hash
          |FROM dag_storage_topological_sorting 
          |WHERE parent_block_hash=$blockHash""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(xa)

  override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
    sql"""|SELECT block_hash 
          |FROM dag_storage_justifications 
          |WHERE justification_block_hash=$blockHash""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(xa)

  override def lookup(blockHash: BlockHash): F[Option[BlockMetadata]] =
    sql"""|SELECT data 
          |FROM dag_storage_blocks_metadata 
          |WHERE block_hash=$blockHash""".stripMargin
      .query[BlockMetadata]
      .option
      .transact(xa)

  override def contains(blockHash: BlockHash): F[Boolean] =
    sql"""|SELECT 1 
          |FROM dag_storage_blocks_metadata 
          |WHERE block_hash=$blockHash""".stripMargin
      .query[Long]
      .option
      .transact(xa)
      .map(_.nonEmpty)

  override def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): F[Vector[Vector[BlockHash]]] =
    sql"""|SELECT DISTINCT child_rank, child_block_hash
          |FROM dag_storage_topological_sorting
          |WHERE child_rank >= $startBlockNumber
          |  AND child_rank <= $endBlockNumber
          |ORDER BY child_rank""".stripMargin
      .query[(Long, BlockHash)]
      .to[Vector]
      .transact(xa)
      .flatMap(rearrangeSQLiteResult)

  override def topoSort(startBlockNumber: Long): F[Vector[Vector[BlockHash]]] =
    sql"""|SELECT DISTINCT child_rank, child_block_hash
          |FROM dag_storage_topological_sorting
          |WHERE child_rank >= $startBlockNumber
          |ORDER BY child_rank""".stripMargin
      .query[(Long, BlockHash)]
      .to[Vector]
      .transact(xa)
      .flatMap(rearrangeSQLiteResult)

  override def topoSortTail(tailLength: Int): F[Vector[Vector[BlockHash]]] =
    sql"""|SELECT DISTINCT a.child_rank, a.child_block_hash
          |FROM dag_storage_topological_sorting a
          |INNER JOIN (
          |    SELECT max(child_rank) max_rank FROM dag_storage_topological_sorting
          |) b
          |ON a.child_rank>b.max_rank-${tailLength}
          |ORDER BY a.child_rank""".stripMargin
      .query[(Long, BlockHash)]
      .to[Vector]
      .transact(xa)
      .flatMap(rearrangeSQLiteResult)

  /* Expects that ranks are monotonically increasing */
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
          |FROM (SELECT block_hash, MAX(rank)
          |      FROM dag_storage_blocks_metadata
          |      WHERE validator = $validator
          |      GROUP BY validator)""".stripMargin
      .query[BlockHash]
      .option
      .transact(xa)

  override def latestMessage(validator: Validator): F[Option[BlockMetadata]] =
    sql"""|SELECT data
          |FROM (SELECT data, MAX(rank)
          |      FROM dag_storage_blocks_metadata
          |      WHERE validator = $validator
          |      GROUP BY validator)""".stripMargin
      .query[BlockMetadata]
      .option
      .transact(xa)

  override def latestMessageHashes: F[Map[Validator, BlockHash]] =
    sql"""|SELECT validator, block_hash
          |FROM (SELECT validator, block_hash, MAX(rank)
          |      FROM dag_storage_blocks_metadata
          |      WHERE validator!=${ByteString.EMPTY}
          |      GROUP BY validator)""".stripMargin
      .query[(Validator, BlockHash)]
      .to[List]
      .transact(xa)
      .map(_.toMap)

  override def latestMessages: F[Map[Validator, BlockMetadata]] =
    sql"""|SELECT validator, data
          |FROM (SELECT validator, data, MAX(rank)
          |      FROM dag_storage_blocks_metadata
          |      WHERE validator!=${ByteString.EMPTY}
          |      GROUP BY validator)""".stripMargin
      .query[(Validator, BlockMetadata)]
      .to[List]
      .transact(xa)
      .map(_.toMap)
}

object SQLiteDagStorage {
  def create[F[_]: Sync](implicit xa: Transactor[F], met: Metrics[F]): F[DagStorage[F]] =
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
