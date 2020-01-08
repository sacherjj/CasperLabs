package io.casperlabs.storage.dag

import cats._
import cats.data._
import cats.effect._
import cats.implicits._
import doobie._
import doobie.implicits._
import io.casperlabs.casper.consensus.info.BlockInfo
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.Source
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.storage.DagStorageMetricsSource
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.block.SQLiteBlockStorage.blockInfoCols
import io.casperlabs.storage.dag.DagRepresentation.Validator
import io.casperlabs.storage.dag.DagStorage.{MeteredDagRepresentation, MeteredDagStorage}
import io.casperlabs.storage.util.DoobieCodecs
import com.google.protobuf.ByteString
import scala.collection.JavaConverters._

class SQLiteDagStorage[F[_]: Bracket[*[_], Throwable]](
    readXa: Transactor[F],
    writeXa: Transactor[F]
) extends DagStorage[F]
    with DagRepresentation[F]
    with FinalityStorage[F]
    with DoobieCodecs {
  import SQLiteDagStorage.StreamOps

  override def getRepresentation: F[DagRepresentation[F]] =
    (this: DagRepresentation[F]).pure[F]

  override def insert(block: Block): F[DagRepresentation[F]] = {
    val blockSummary     = BlockSummary.fromBlock(block)
    val deploys          = block.getBody.deploys
    val deployErrorCount = deploys.count(_.isError)
    val deployCostTotal  = deploys.map(_.cost).sum
    val deployGasPriceAvg =
      if (deployCostTotal == 0L) 0L
      else
        deploys
          .map(d => d.cost * d.getDeploy.getHeader.gasPrice)
          .sum / deployCostTotal
    val blockMetadataQuery =
      (fr"""INSERT OR IGNORE INTO block_metadata
            (block_hash, validator, rank, """ ++ blockInfoCols() ++ fr""")
            VALUES (${block.blockHash}, ${block.validatorPublicKey}, ${block.rank}, ${blockSummary.toByteString}, ${block.serializedSize}, $deployErrorCount, $deployCostTotal, $deployGasPriceAvg)
            """).update.run

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

    val latestMessagesQuery =
      if (!block.isGenesisLike) {
        // CON-557 will add a validity condition that a block cannot cite multiple latest messages
        // from its creator, i.e. merging of swimlane is not allowed.
        val validatorPreviousMessage =
          Option(blockSummary.getHeader.validatorPrevBlockHash).filterNot(_.isEmpty)

        val insertQuery =
          sql""" INSERT OR IGNORE INTO validator_latest_messages (validator, block_hash)
                 VALUES (${blockSummary.validatorPublicKey}, ${blockSummary.blockHash})""".stripMargin

        validatorPreviousMessage
          .fold {
            // No previous message visible from the justifications.
            // This is the first block from this validator (at least according to the creator of the message).
            insertQuery.update.run
          } { lastMessageHash =>
            // Delete previous entry if the new block cites it.
            // Insert new one.
            sql"""|DELETE FROM validator_latest_messages
                  |WHERE validator = ${blockSummary.validatorPublicKey}
                  |AND block_hash = $lastMessageHash""".stripMargin.update.run >>
              insertQuery.update.run
          }
      } else ().pure[ConnectionIO]

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
      _   <- transaction.transact(writeXa)
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
    } yield ()).transact(writeXa)

  override def close(): F[Unit] = ().pure[F]

  override def children(blockHash: BlockHash): F[Set[BlockHash]] =
    sql"""|SELECT child_block_hash
          |FROM block_parents
          |WHERE parent_block_hash=$blockHash""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(readXa)

  override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
    sql"""|SELECT block_hash
          |FROM block_justifications
          |WHERE justification_block_hash=$blockHash""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(readXa)

  override def lookup(blockHash: BlockHash): F[Option[Message]] =
    sql"""|SELECT data
          |FROM block_metadata
          |WHERE block_hash=$blockHash""".stripMargin
      .query[BlockSummary]
      .option
      .transact(readXa)
      .flatMap(Message.fromOptionalSummary[F](_))

  override def contains(blockHash: BlockHash): F[Boolean] =
    sql"""|SELECT 1
          |FROM block_metadata
          |WHERE block_hash=$blockHash""".stripMargin
      .query[Long]
      .option
      .transact(readXa)
      .map(_.nonEmpty)

  override def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockInfo]] =
    (fr"""SELECT rank, """ ++ blockInfoCols() ++ fr"""
          FROM block_metadata
          WHERE rank>=$startBlockNumber AND rank<=$endBlockNumber
          ORDER BY rank
          """)
      .query[(Long, BlockInfo)]
      .stream
      .transact(readXa)
      .groupByRank

  override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockInfo]] =
    (fr"""SELECT rank, """ ++ blockInfoCols() ++ fr"""
          FROM block_metadata
          WHERE rank>=$startBlockNumber
          ORDER BY rank""")
      .query[(Long, BlockInfo)]
      .stream
      .transact(readXa)
      .groupByRank

  override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockInfo]] =
    (fr"""SELECT a.rank, """ ++ blockInfoCols("a") ++ fr"""
          FROM block_metadata a
          INNER JOIN (
           SELECT max(rank) max_rank FROM block_metadata
          ) b
          ON a.rank>b.max_rank-$tailLength
          ORDER BY a.rank
          """)
      .query[(Long, BlockInfo)]
      .stream
      .transact(readXa)
      .groupByRank

  override def latestMessageHash(validator: Validator): F[Set[BlockHash]] =
    sql"""|SELECT block_hash
          |FROM validator_latest_messages
          |WHERE validator=$validator""".stripMargin
      .query[BlockHash]
      .to[Set]
      .transact(readXa)

  override def latestMessage(validator: Validator): F[Set[Message]] =
    sql"""|SELECT m.data
          |FROM validator_latest_messages v
          |INNER JOIN block_metadata m
          |ON v.validator=$validator AND v.block_hash=m.block_hash""".stripMargin
      .query[BlockSummary]
      .to[List]
      .transact(readXa)
      .flatMap(_.traverse(toMessageSummaryF))
      .map(_.toSet)

  override def latestMessageHashes: F[Map[Validator, Set[BlockHash]]] =
    sql"""|SELECT *
          |FROM validator_latest_messages""".stripMargin
      .query[(Validator, BlockHash)]
      .to[List]
      .transact(readXa)
      .map(_.groupBy(_._1).mapValues(_.map(_._2).toSet))

  override def latestMessages: F[Map[Validator, Set[Message]]] =
    sql"""|SELECT v.validator, m.data
          |FROM validator_latest_messages v
          |INNER JOIN block_metadata m
          |ON m.block_hash=v.block_hash""".stripMargin
      .query[(Validator, BlockSummary)]
      .to[List]
      .transact(readXa)
      .flatMap(_.traverse { case (v, bs) => toMessageSummaryF(bs).map(v -> _) })
      .map(_.groupBy(_._1).mapValues(_.map(_._2).toSet))

  override def markAsFinalized(
      mainParent: BlockHash,
      secondary: Set[BlockHash]
  ): F[Unit] = {
    val mainPQuery =
      sql"""UPDATE block_metadata SET is_finalized=TRUE, is_main_chain=TRUE WHERE block_hash=$mainParent""".update.run
    val secondaryQuery = NonEmptyList.fromList(secondary.toList).fold(doobie.free.connection.unit) {
      nel =>
        val q = fr"""UPDATE block_metadata SET is_finalized=TRUE WHERE """ ++ Fragments
          .in(fr"block_hash", nel)

        q.update.run.void
    }

    val lfbChainQuery =
      sql"""INSERT INTO lfb_chain (block_hash, indirectly_finalized)
             VALUES ($mainParent, ${ByteString.copyFrom(secondary.asJava)})""".update.run.void

    val transaction = for {
      _ <- mainPQuery
      _ <- secondaryQuery
      _ <- lfbChainQuery
    } yield ()

    transaction.transact(writeXa)
  }

  override def isFinalized(block: BlockHash): F[Boolean] =
    sql"""SELECT is_finalized FROM block_metadata WHERE block_hash=$block"""
      .query[Boolean]
      .unique
      .transact(readXa)

  override def getLastFinalizedBlock: F[BlockHash] =
    sql"""SELECT block_hash FROM lfb_chain ORDER BY id DESC LIMIT 1"""
      .query[BlockHash]
      .unique
      .transact(readXa)

  private val toMessageSummaryF: BlockSummary => F[Message] = bs =>
    MonadThrowable[F].fromTry(Message.fromBlockSummary(bs))
}

object SQLiteDagStorage {

  private case class Fs2State(
      buffer: Vector[BlockInfo] = Vector.empty,
      rank: Long = -1
  )

  private implicit class StreamOps[F[_]: Bracket[*[_], Throwable]](
      val stream: fs2.Stream[F, (Long, BlockInfo)]
  ) {
    private type ErrorOr[B] = Either[Throwable, B]
    private type G[B]       = StateT[ErrorOr, Vector[Vector[BlockInfo]], B]

    /* Returns block summaries grouped by ranks, in ascending order. */
    def groupByRank: fs2.Stream[F, Vector[BlockInfo]] = go(Fs2State(), stream).stream

    /** Check [[https://fs2.io/guide.html#statefully-transforming-streams]]
      * and [[https://blog.leifbattermann.de/2017/10/08/error-and-state-handling-with-monad-transformers-in-scala/]]
      * if it's hard to understand what's going on
      *  */
    private def go(
        state: Fs2State,
        s: fs2.Stream[F, (Long, BlockInfo)]
    ): fs2.Pull[F, Vector[BlockInfo], Unit] =
      s.pull.uncons.flatMap {
        case Some((chunk, streamTail)) =>
          chunk
            .foldLeftM[G, Fs2State](state) {
              case (Fs2State(_, currentRank), (rank, info)) if currentRank == -1 =>
                Fs2State(Vector(info), rank).pure[G]
              case (Fs2State(acc, currentRank), (rank, info)) if rank == currentRank =>
                Fs2State(acc :+ info, currentRank).pure[G]
              case (Fs2State(acc, currentRank), (rank, info)) if rank > currentRank =>
                put(acc) >> Fs2State(Vector(info), rank).pure[G]
              case (Fs2State(acc, currentRank), (rank, info)) =>
                error(
                  new IllegalArgumentException(
                    s"Ranks must increase monotonically, got prev rank: $currentRank, prev block: ${msg(
                      acc.last
                    )}, next rank: ${rank}, next block: ${msg(info)}"
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

    private def put(infos: Vector[BlockInfo]) =
      StateT.modify[ErrorOr, Vector[Vector[BlockInfo]]](_ :+ infos)

    private def error(e: Throwable) =
      StateT.liftF[ErrorOr, Vector[Vector[BlockInfo]], Fs2State](e.asLeft[Fs2State])

    private def msg(info: BlockInfo): String =
      Base16.encode(info.getSummary.blockHash.toByteArray).take(10)
  }

  private[storage] def create[F[_]: Sync](readXa: Transactor[F], writeXa: Transactor[F])(
      implicit
      met: Metrics[F]
  ): F[DagStorage[F] with DagRepresentation[F] with FinalityStorage[F]] =
    for {
      dagStorage <- Sync[F].delay(
                     new SQLiteDagStorage[F](readXa, writeXa)
                       with MeteredDagStorage[F]
                       with MeteredDagRepresentation[F]
                       with FinalityStorage[F] {
                       override implicit val m: Metrics[F] = met
                       override implicit val ms: Source =
                         Metrics.Source(DagStorageMetricsSource, "sqlite")
                       override implicit val a: Apply[F] = Sync[F]
                     }
                   )
    } yield dagStorage: DagStorage[F] with DagRepresentation[F] with FinalityStorage[F]
}
