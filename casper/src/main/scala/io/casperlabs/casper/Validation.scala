package io.casperlabs.casper

import io.casperlabs.blockstorage.BlockDagRepresentation
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.casper.protocol.ApprovedBlock
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.crypto.Keys.PublicKeyBS
import io.casperlabs.ipc

trait Validation[F[_]] {

  def neglectedInvalidBlock(block: Block, invalidBlockTracker: Set[BlockHash]): F[Unit]

  def blockSummaryPreGenesis(block: Block, dag: BlockDagRepresentation[F], chainId: String): F[Unit]

  def blockSender(b: Block, genesis: Block, dag: BlockDagRepresentation[F]): F[Boolean]

  def blockSummary(
      block: Block,
      genesis: Block,
      dag: BlockDagRepresentation[F],
      shardId: String,
      lastFinalizedBlockHash: BlockHash
  ): F[Unit]

  def version(b: Block, m: Long => ipc.ProtocolVersion): F[Boolean]

  def parents(
      b: Block,
      lastFinalizedBlockHash: BlockHash,
      dag: BlockDagRepresentation[F]
  ): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]]

  def blockSignature(b: Block): F[Boolean]

  def approvedBlock(a: ApprovedBlock, requiredValidators: Set[PublicKeyBS]): F[Boolean]

  def deployHash(d: consensus.Deploy): F[Boolean]

  def deploySignature(d: consensus.Deploy): F[Boolean]

  def signature(d: Array[Byte], sig: protocol.Signature): Boolean

  def formatOfFields(b: Block, treatAsGenesis: Boolean = false): F[Boolean]

  def bondsCache(b: Block, computedBonds: Seq[Bond]): F[Unit]

  def transactions(
      block: Block,
      dag: BlockDagRepresentation[F],
      preStateHash: StateHash,
      effects: Seq[ipc.TransformEntry]
  ): F[Unit]
}

object Validation {
  def apply[F[_]](implicit ev: Validation[F]) = ev
}
