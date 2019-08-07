package io.casperlabs.blockstorage

import java.nio.file.Path

import cats.data.EitherT
import io.casperlabs.casper.consensus.Block
import io.casperlabs.crypto.codec.Base16

sealed abstract class StorageError extends Exception

final case class CheckpointsDoNotStartFromZero(sortedCheckpoints: List[Path]) extends StorageError
final case class CheckpointsAreNotConsecutive(sortedCheckpoints: List[Path])  extends StorageError
final case class TopoSortLengthIsTooBig(length: Long)                         extends StorageError
final case class BlockValidatorIsMalformed(block: Block)                      extends StorageError
final case class CheckpointDoesNotExist(offset: Long)                         extends StorageError
final case object LatestMessagesLogIsMalformed                                extends StorageError

object StorageError {
  type StorageErr[A]        = Either[StorageError, A]
  type StorageErrT[F[_], A] = EitherT[F, StorageError, A]

  def errorMessage(ce: StorageError): String =
    ce match {
      case CheckpointsDoNotStartFromZero(sortedCheckpoints) =>
        s"Checkpoints do not start from block number 0: ${sortedCheckpoints.mkString(",")}"
      case CheckpointsAreNotConsecutive(sortedCheckpoints) =>
        s"Checkpoints are not consecutive: ${sortedCheckpoints.mkString(",")}"
      case TopoSortLengthIsTooBig(length) =>
        s"Topological sorting of length $length was requested while maximal length is ${Int.MaxValue}"
      case BlockValidatorIsMalformed(block) =>
        s"Block ${Base16.encode(block.blockHash.toByteArray)} validator is malformed: ${Base16
          .encode(block.getHeader.validatorPublicKey.toByteArray)}"
      case CheckpointDoesNotExist(offset) =>
        s"Requested a block with block number $offset, but there is no checkpoint for it"
      case LatestMessagesLogIsMalformed =>
        "Latest messages log is malformed"
    }

  implicit class StorageErrorToMessage(storageError: StorageError) {
    val message: String = StorageError.errorMessage(storageError)
  }
}
