package io.casperlabs.casper.highway

import cats._
import cats.implicits._
import io.casperlabs.casper.consensus.Block
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block.BlockStorageWriter

/** A stateless class to encapsulate the steps to validate, execute and store a block. */
class MessageExecutor[F[_]: MonadThrowable: BlockStorageWriter] {

  /** Validate, execute and persist an incoming block.
    * The blocks made by the MessageProducer don't have to be passed here.
    */
  def validateAndAdd(block: Block, isBookingBlock: Boolean): F[Unit] =
    for {
      // TODO (CON-621): Validate and execute the block, store it in the block store and DAG store.
      // Pass the `isBookingBlock` flag as well. For now just store the incoming blocks so they
      // are available in the DAG when we produce messages on top of them.
      _ <- BlockStorageWriter[F].put(BlockMsgWithTransform().withBlockMessage(block))
      _ = isBookingBlock
    } yield ()

}
