package io.casperlabs.casper.validation

import io.casperlabs.blockstorage.BlockDagRepresentation
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.{state, Block, BlockSummary, Bond}
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.{consensus, protocol}
import io.casperlabs.crypto.Keys.PublicKeyBS
import io.casperlabs.ipc

import scala.concurrent.duration.FiniteDuration

trait Validation[F[_]] {

  def neglectedInvalidBlock(block: Block, invalidBlockTracker: Set[BlockHash]): F[Unit]

  def blockSender(block: BlockSummary): F[Boolean]

  def blockSummary(summary: BlockSummary, chainId: String): F[Unit]

  def version(b: BlockSummary, m: Long => state.ProtocolVersion): F[Boolean]

  def parents(
      b: Block,
      lastFinalizedBlockHash: BlockHash,
      dag: BlockDagRepresentation[F]
  ): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]]

  def blockSignature(b: BlockSummary): F[Boolean]

  def approvedBlock(a: ApprovedBlock, requiredValidators: Set[PublicKeyBS]): F[Boolean]

  def deployHash(d: consensus.Deploy): F[Boolean]

  def deploySignature(d: consensus.Deploy): F[Boolean]

  def signature(d: Array[Byte], sig: protocol.Signature): Boolean

  def formatOfFields(b: BlockSummary, treatAsGenesis: Boolean = false): F[Boolean]

  def bondsCache(b: Block, computedBonds: Seq[Bond]): F[Unit]

  def transactions(
      block: Block,
      preStateHash: StateHash,
      effects: Seq[ipc.TransformEntry]
  ): F[Unit]

  def blockFull(
      block: Block,
      dag: BlockDagRepresentation[F],
      chainId: String,
      maybeGenesis: Option[Block]
  ): F[Unit]

  def preTimestamp(b: Block): F[Option[FiniteDuration]]
}

object Validation {
  def apply[F[_]](implicit ev: Validation[F]) = ev
}
