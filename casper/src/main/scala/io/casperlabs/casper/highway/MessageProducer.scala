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
      target: BlockHash,
      justifications: Map[PublicKeyBS, Set[BlockHash]],
      roundId: Ticks
  ): F[Message.Ballot]
}
