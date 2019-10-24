package io.casperlabs.casper.validation

import io.casperlabs.casper.consensus
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.{state, Block, BlockSummary, Bond}
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.CasperLabsProtocolVersions
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.casper.equivocations.EquivocationsTracker
import io.casperlabs.catscontrib.Fs2Compiler
import io.casperlabs.ipc
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation

import scala.concurrent.duration.FiniteDuration

trait Validation[F[_]] {

  def neglectedInvalidBlock(block: Block, invalidBlockTracker: Set[BlockHash]): F[Unit]

  def blockSender(block: BlockSummary)(implicit bs: BlockStorage[F]): F[Boolean]

  def blockSummary(summary: BlockSummary, chainName: String)(
      implicit versions: CasperLabsProtocolVersions[F]
  ): F[Unit]

  def version(b: BlockSummary, m: Long => F[state.ProtocolVersion]): F[Boolean]

  def parents(
      b: Block,
      lastFinalizedBlockHash: BlockHash,
      dag: DagRepresentation[F],
      equivocationsTracker: EquivocationsTracker
  )(implicit bs: BlockStorage[F]): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]]

  def blockSignature(b: BlockSummary): F[Boolean]

  def deployHash(d: consensus.Deploy): F[Boolean]

  def deployHeader(d: consensus.Deploy): F[List[Errors.DeployHeaderError]]

  def deploySignature(d: consensus.Deploy): F[Boolean]

  def signature(d: Array[Byte], sig: consensus.Signature, key: PublicKey): Boolean

  def formatOfFields(b: BlockSummary, treatAsGenesis: Boolean = false): F[Boolean]

  def bondsCache(b: Block, computedBonds: Seq[Bond]): F[Unit]

  def transactions(
      block: Block,
      preStateHash: StateHash,
      effects: Seq[ipc.TransformEntry]
  )(implicit ee: ExecutionEngineService[F]): F[Unit]

  def blockFull(
      block: Block,
      dag: DagRepresentation[F],
      chainName: String,
      maybeGenesis: Option[Block]
  )(
      implicit bs: BlockStorage[F],
      versions: CasperLabsProtocolVersions[F],
      compiler: Fs2Compiler[F]
  ): F[Unit]

  def preTimestamp(b: Block): F[Option[FiniteDuration]]
}

object Validation {
  def apply[F[_]](implicit ev: Validation[F]) = ev
}
