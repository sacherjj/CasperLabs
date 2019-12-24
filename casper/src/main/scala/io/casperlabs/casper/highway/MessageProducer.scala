package io.casperlabs.casper.highway

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.crypto.Keys.PublicKeyBS
import io.casperlabs.models.Message
import io.casperlabs.storage.block.BlockStorage.BlockHash

/** Produce a signed message, persisted message.
  * The producer should the thread safe, so that when it's
  * called from multiple threads to produce ballots in response
  * to different messages it doesn't create an equivocation.
  */
trait MessageProducer[F[_]] {
  def validatorId: PublicKeyBS

  def ballot(
      eraId: BlockHash,
      roundId: Ticks,
      target: BlockHash,
      // For lambda responses we want to limit the justifications to just direct ones.
      justifications: Map[PublicKeyBS, Set[BlockHash]]
  ): F[Message.Ballot]

  /** Pick whatever secondary parents are compatible with the chosen main parent
    * and the justifications selected when the caller started their operation,
    * select deploys from the buffer, and create a (possibly empty) block,
    * persisting it to the block store.
    */
  def block(
      eraId: BlockHash,
      roundId: Ticks,
      mainParent: BlockHash,
      justifications: Map[PublicKeyBS, Set[BlockHash]]
  ): F[Message.Block]
}
