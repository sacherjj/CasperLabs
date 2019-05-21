package io.casperlabs.blockstorage

import cats.effect.Concurrent
import cats.Monad
import cats.implicits._
import cats.effect.concurrent.{Ref, Semaphore}
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block
import io.casperlabs.crypto.hash.Blake2b256

final class IndexedBlockDagStorage[F[_]: Monad](
    lock: Semaphore[F],
    underlying: BlockDagStorage[F],
    idToBlocksRef: Ref[F, Map[Long, Block]],
    currentIdRef: Ref[F, Long]
) extends BlockDagStorage[F] {

  def getRepresentation: F[BlockDagRepresentation[F]] =
    for {
      _      <- lock.acquire
      result <- underlying.getRepresentation
      _      <- lock.release
    } yield result

  def insert(block: Block): F[BlockDagRepresentation[F]] =
    for {
      _          <- lock.acquire
      _          <- underlying.insert(block)
      _          <- lock.release
      updatedDag <- getRepresentation
    } yield updatedDag

  def insertIndexed(block: Block): F[Block] =
    for {
      _         <- lock.acquire
      header    = block.header.get
      currentId <- currentIdRef.get
      nextId    = currentId + 1L
      dag       <- underlying.getRepresentation
      nextCreatorSeqNum <- dag
                            .latestMessage(block.getHeader.validatorPublicKey)
                            .map(_.fold(-1)(_.seqNum) + 1)
      modifiedBlock = block
        .withHeader(
          header
            .withValidatorBlockSeqNum(nextCreatorSeqNum)
            .withRank(nextId)
        )
      _ <- underlying.insert(modifiedBlock)
      _ <- idToBlocksRef.update(_.updated(nextId, modifiedBlock))
      _ <- currentIdRef.set(nextId)
      _ <- lock.release
    } yield modifiedBlock

  def inject(index: Int, block: Block): F[Unit] =
    for {
      _ <- lock.acquire
      _ <- idToBlocksRef.update(_.updated(index, block))
      _ <- underlying.insert(block)
      _ <- lock.release
    } yield ()

  def checkpoint(): F[Unit] = underlying.checkpoint()

  def clear(): F[Unit] =
    for {
      _ <- lock.acquire
      _ <- underlying.clear()
      _ <- idToBlocksRef.set(Map.empty)
      _ <- currentIdRef.set(-1)
      _ <- lock.release
    } yield ()

  def close(): F[Unit] = underlying.close()

  def lookupById(id: Int): F[Option[Block]] =
    for {
      idToBlocks <- idToBlocksRef.get
    } yield idToBlocks.get(id)

  def lookupByIdUnsafe(id: Int): F[Block] =
    for {
      idToBlocks <- idToBlocksRef.get
    } yield idToBlocks(id)
}

object IndexedBlockDagStorage {
  def apply[F[_]](implicit B: IndexedBlockDagStorage[F]): IndexedBlockDagStorage[F] = B

  def create[F[_]: Concurrent](underlying: BlockDagStorage[F]): F[IndexedBlockDagStorage[F]] =
    for {
      semaphore  <- Semaphore[F](1)
      idToBlocks <- Ref.of[F, Map[Long, Block]](Map.empty)
      currentId  <- Ref.of[F, Long](-1L)
    } yield
      new IndexedBlockDagStorage[F](
        semaphore,
        underlying,
        idToBlocks,
        currentId
      )
}
