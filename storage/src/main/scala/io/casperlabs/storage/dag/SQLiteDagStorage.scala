package io.casperlabs.storage.dag

import cats._
import cats.data._
import cats.effect._
import cats.implicits._
import doobie._
import doobie.implicits._
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.storage.DagStorageMetricsSource
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.{MeteredDagRepresentation, MeteredDagStorage}
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
    val blockSummary     = BlockSummary.fromBlock(block)
    val deployErrorCount = block.getBody.deploys.count(_.isError)
    val blockMetadataQuery =
      sql"""|INSERT OR IGNORE INTO block_metadata
            |(block_hash, validator, rank, data, block_size, deploy_error_count)
            |VALUES (${block.blockHash}, ${block.validatorPublicKey}, ${block.rank}, ${blockSummary.toByteString}, ${block.serializedSize}, $deployErrorCount)
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
      if (block.isGenesisLike) {
        val newValidators = block.state.bonds
          .map(_.validatorPublicKey)
          .toSet
          .diff(block.justifications.map(_.validatorPublicKey).toSet)
          .toList
        // Will ignore existing entries, because genesis should only be the first block and can't be added twice
        Update[(Validator, BlockHash, Long)](
          """|INSERT OR IGNORE INTO validator_latest_messages
             |(validator, block_hash, rank)
             |VALUES (?, ?, ?)""".stripMargin
        ).updateMany(newValidators.map((_, blockSummary.blockHash, 0L)))
      } else {
        // Insert in case if new block has a higher rank than the previous max rank of validator
        sql"""|INSERT OR REPLACE INTO validator_latest_messages
              |SELECT ${blockSummary.validatorPublicKey} as validator,
              |       ${blockSummary.blockHash} as block_hash,
              |       ${blockSummary.rank} as rank
              |WHERE NOT exists(
              |        SELECT 1
              |        FROM validator_latest_messages
              |        WHERE validator = ${blockSummary.validatorPublicKey}
              |          AND rank > ${blockSummary.rank}
              |    )""".stripMargin.update.run
      }
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

  override def lookup(blockHash: BlockHash): F[Option[Message]] =
    sql"""|SELECT data 
          |FROM block_metadata 
          |WHERE block_hash=$blockHash""".stripMargin
      .query[BlockSummary]
      .option
      .transact(xa)
      .flatMap(Message.fromOptionalSummary[F](_))

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
  ): fs2.Stream[F, Vector[BlockSummary]] =
    sql"""|SELECT rank, data
          |FROM block_metadata
          |WHERE rank>=$startBlockNumber AND rank<=$endBlockNumber
          |ORDER BY rank
          |""".stripMargin
      .query[(Long, BlockSummary)]
      .stream
      .transact(xa)
      .groupByRank

  override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockSummary]] =
    sql"""|SELECT rank, data
          |FROM block_metadata
          |WHERE rank>=$startBlockNumber
          |ORDER BY rank""".stripMargin
      .query[(Long, BlockSummary)]
      .stream
      .transact(xa)
      .groupByRank

  override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockSummary]] =
    sql"""|SELECT a.rank, a.data
          |FROM block_metadata a
          |INNER JOIN (
          | SELECT max(rank) max_rank FROM block_metadata
          |) b
          |ON a.rank>b.max_rank-$tailLength
          |ORDER BY a.rank
          |""".stripMargin
      .query[(Long, BlockSummary)]
      .stream
      .transact(xa)
      .groupByRank

  override def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
    sql"""|SELECT block_hash
          |FROM validator_latest_messages
          |WHERE validator=$validator""".stripMargin
      .query[BlockHash]
      .option
      .transact(xa)

  override def latestMessage(validator: Validator): F[Option[Message]] =
    sql"""|SELECT m.data
          |FROM validator_latest_messages v
          |INNER JOIN block_metadata m
          |ON v.validator=$validator AND v.block_hash=m.block_hash""".stripMargin
      .query[BlockSummary]
      .option
      .transact(xa)
      .flatMap {
        case None     => none[Message].pure[F]
        case Some(bs) => toMessageSummaryF(bs).map(Some(_))
      }

  override def latestMessageHashes: F[Map[Validator, BlockHash]] =
    sql"""|SELECT *
          |FROM validator_latest_messages""".stripMargin
      .query[(Validator, BlockHash)]
      .to[List]
      .transact(xa)
      .map(_.toMap)

  override def latestMessages: F[Map[Validator, Message]] =
    sql"""|SELECT v.validator, m.data
          |FROM validator_latest_messages v
          |INNER JOIN block_metadata m
          |ON m.block_hash=v.block_hash""".stripMargin
      .query[(Validator, BlockSummary)]
      .to[List]
      .transact(xa)
      .flatMap(_.traverse { case (v, bs) => toMessageSummaryF(bs).map(v -> _) })
      .map(_.toMap)

  private val toMessageSummaryF: BlockSummary => F[Message] = bs =>
    MonadThrowable[F].fromTry(Message.fromBlockSummary(bs))
}

object SQLiteDagStorage {

  private case class Fs2State(
      buffer: Vector[BlockSummary] = Vector.empty,
      rank: Long = -1
  )

  private implicit class StreamOps[F[_]: Bracket[*[_], Throwable]](
      val stream: fs2.Stream[F, (Long, BlockSummary)]
  ) {
    private type ErrorOr[B] = Either[Throwable, B]
    private type G[B]       = StateT[ErrorOr, Vector[Vector[BlockSummary]], B]

    /* Returns block summaries grouped by ranks, in ascending order. */
    def groupByRank: fs2.Stream[F, Vector[BlockSummary]] = go(Fs2State(), stream).stream

    /** Check [[https://fs2.io/guide.html#statefully-transforming-streams]]
      * and [[https://blog.leifbattermann.de/2017/10/08/error-and-state-handling-with-monad-transformers-in-scala/]]
      * if it's hard to understand what's going on
      *  */
    private def go(
        state: Fs2State,
        s: fs2.Stream[F, (Long, BlockSummary)]
    ): fs2.Pull[F, Vector[BlockSummary], Unit] =
      s.pull.uncons.flatMap {
        case Some((chunk, streamTail)) =>
          chunk
            .foldLeftM[G, Fs2State](state) {
              case (Fs2State(_, currentRank), (rank, summary)) if currentRank == -1 =>
                Fs2State(Vector(summary), rank).pure[G]
              case (Fs2State(acc, currentRank), (rank, summary)) if rank == currentRank =>
                Fs2State(acc :+ summary, currentRank).pure[G]
              case (Fs2State(acc, currentRank), (rank, summary)) if rank > currentRank =>
                put(acc) >> Fs2State(Vector(summary), rank).pure[G]
              case (Fs2State(acc, currentRank), (rank, summary)) =>
                error(
                  new IllegalArgumentException(
                    s"Ranks must increase monotonically, got prev rank: $currentRank, prev block: ${msg(
                      acc.last
                    )}, next rank: ${rank}, next block: ${msg(summary)}"
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

    private def put(summaries: Vector[BlockSummary]) =
      StateT.modify[ErrorOr, Vector[Vector[BlockSummary]]](_ :+ summaries)

    private def error(e: Throwable) =
      StateT.liftF[ErrorOr, Vector[Vector[BlockSummary]], Fs2State](e.asLeft[Fs2State])

    private def msg(summary: BlockSummary): String =
      Base16.encode(summary.blockHash.toByteArray).take(10)
  }

  private[storage] def create[F[_]: Sync](
      implicit xa: Transactor[F],
      met: Metrics[F]
  ): F[DagStorage[F] with DagRepresentation[F]] =
    for {
      dagStorage <- Sync[F].delay(
                     new SQLiteDagStorage[F](xa)
                       with MeteredDagStorage[F]
                       with MeteredDagRepresentation[F] {
                       override implicit val m: Metrics[F] = met
                       override implicit val ms: Source =
                         Metrics.Source(DagStorageMetricsSource, "sqlite")
                       override implicit val a: Apply[F] = Sync[F]
                     }
                   )
    } yield dagStorage: DagStorage[F] with DagRepresentation[F]
}
