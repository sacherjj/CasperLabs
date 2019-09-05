package io.casperlabs.casper

import cats.{Applicative, Monad}
import cats.implicits._
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.DagRepresentation
import io.casperlabs.casper.EquivocationRecord.SequenceNumber
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.util.{DoublyLinkedDag, ProtoUtil}
import io.casperlabs.shared.{Log, LogSource}

// This is the sequence number of the equivocator's base block
final case class EquivocationRecord(
    equivocator: Validator,
    equivocationBaseBlockSeqNum: SequenceNumber,
    equivocationDetectedBlockHashes: Set[BlockHash]
)

object EquivocationRecord {
  type SequenceNumber = Int
}

object EquivocationDetector {

  private implicit val logSource: LogSource = LogSource(this.getClass)

  def checkEquivocations[F[_]: Monad: Log: FunctorRaise[?[_], InvalidBlock]](
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash],
      block: Block,
      dag: DagRepresentation[F]
  ): F[Unit] =
    for {
      maybeLatestMessageOfCreatorHash <- dag.latestMessageHash(block.getHeader.validatorPublicKey)
      maybeCreatorJustification       = creatorJustificationHash(block)
      isNotEquivocation               = maybeCreatorJustification == maybeLatestMessageOfCreatorHash
      _ <- if (isNotEquivocation) {
            Applicative[F].unit
          } else if (requestedAsDependency(block, blockBufferDependencyDag)) {
            FunctorRaise[F, InvalidBlock].raise[Unit](AdmissibleEquivocation)
          } else {
            for {
              validator <- PrettyPrinter.buildString(block.getHeader.validatorPublicKey).pure[F]
              creatorJustificationHash = PrettyPrinter.buildString(
                maybeCreatorJustification.getOrElse(ByteString.EMPTY)
              )
              latestMessageOfCreator = PrettyPrinter.buildString(
                maybeLatestMessageOfCreatorHash.getOrElse(ByteString.EMPTY)
              )
              _ <- Log[F].warn(
                    s"Ignorable equivocation: validator is $validator, creator justification is $creatorJustificationHash, latest message of creator is $latestMessageOfCreator"
                  )
              _ <- FunctorRaise[F, InvalidBlock].raise[Unit](IgnorableEquivocation)
            } yield ()
          }
    } yield ()

  private def requestedAsDependency(
      block: Block,
      blockBufferDependencyDag: DoublyLinkedDag[BlockHash]
  ): Boolean =
    blockBufferDependencyDag.parentToChildAdjacencyList.contains(block.blockHash)

  private def creatorJustificationHash(block: Block): Option[BlockHash] =
    ProtoUtil.creatorJustification(block).map(_.latestBlockHash)
}
