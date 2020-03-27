package io.casperlabs.casper.util.execengine

import cats.data.NonEmptyList
import cats.Applicative
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.casper
import io.casperlabs.casper.consensus.state.{Unit => _, _}
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.validation.{NCBValidationImpl, Validation}
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.ipc._
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.models.{cltype, Message, SmartContractEngineError}
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag._
import io.casperlabs.models.BlockImplicits._

import scala.concurrent.duration.FiniteDuration
import scala.util.Either

object ExecutionEngineServiceStub {
  type Bonds = Map[PublicKey, Long]

  import ExecEngineUtil.{MergeResult, TransformMap}

  implicit def functorRaiseInvalidBlock[F[_]: Sync] =
    casper.validation.raiseValidateErrorThroughApplicativeError[F]

  def merge[F[_]: MonadThrowable: BlockStorage: Metrics](
      candidateParentBlocks: List[Block],
      dag: DagRepresentation[F]
  ): F[MergeResult[TransformMap, Block]] =
    NonEmptyList.fromList(candidateParentBlocks) map { blocks =>
      MonadThrowable[F]
        .fromTry(blocks.traverse(Message.fromBlock(_)))
        .flatMap(ExecEngineUtil.merge[F](_, dag).map(x => x: MergeResult[TransformMap, Block]))
    } getOrElse {
      MergeResult.empty[TransformMap, Block].pure[F]
    }

  def validateBlockCheckpoint[F[_]: Sync: Log: BlockStorage: ExecutionEngineService: CasperLabsProtocol](
      b: Block,
      dag: DagRepresentation[F]
  ): F[Either[Throwable, StateHash]] = {
    implicit val time = new Time[F] {
      override def currentMillis: F[Long]                   = 0L.pure[F]
      override def nanoTime: F[Long]                        = 0L.pure[F]
      override def sleep(duration: FiniteDuration): F[Unit] = Sync[F].unit
    }
    implicit val metrics    = new MetricsNOP[F]
    implicit val validation = new NCBValidationImpl[F]
    (for {
      parents <- ProtoUtil.unsafeGetParents[F](b)
      merged  <- ExecutionEngineServiceStub.merge[F](parents, dag)
      preStateHash <- ExecEngineUtil
                       .computePrestate[F](merged, rank = b.mainRank, upgrades = Nil)
      preStateBonds = merged.parents.headOption.getOrElse(b).getHeader.getState.bonds
      effects <- ExecEngineUtil
                  .effectsForBlock[F](b, preStateHash)
                  .map(
                    be =>
                      // This is a little hack needed for tests.
                      // Genesis block doesn't have any effects
                      // but is sent using exec + commit which won't call EE.commit
                      // if there are no effects.
                      if (ExecEngineUtil.isGenesisLike(b))
                        Validation.BlockEffects(Map(0 -> Seq.empty))
                      else be
                  )
      _ <- Validation[F].transactions(b, preStateHash, preStateBonds, effects)
    } yield ProtoUtil.postStateHash(b)).attempt
  }

  def mock[F[_]](
      runGenesisWithChainSpecFunc: (
          RunGenesisRequest
      ) => F[Either[Throwable, GenesisResult]],
      upgradeFunc: (
          ByteString,
          ChainSpec.UpgradePoint,
          ProtocolVersion
      ) => F[Either[Throwable, UpgradeResult]],
      execFunc: (
          ByteString,
          Long,
          Seq[DeployItem],
          ProtocolVersion
      ) => F[Either[Throwable, Seq[DeployResult]]],
      commitFunc: (
          ByteString,
          Seq[TransformEntry]
      ) => F[Either[Throwable, ExecutionEngineService.CommitResult]],
      queryFunc: (ByteString, Key, Seq[String]) => F[Either[Throwable, cltype.StoredValue]]
  ): ExecutionEngineService[F] = new ExecutionEngineService[F] {
    override def emptyStateHash: ByteString = ByteString.EMPTY
    override def runGenesis(
        genesisConfig: RunGenesisRequest
    ): F[Either[Throwable, GenesisResult]] =
      runGenesisWithChainSpecFunc(genesisConfig)

    override def upgrade(
        prestate: ByteString,
        upgrade: ChainSpec.UpgradePoint,
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, UpgradeResult]] =
      upgradeFunc(prestate, upgrade, protocolVersion)

    override def exec(
        prestate: ByteString,
        blocktime: Long,
        deploys: Seq[DeployItem],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, Seq[DeployResult]]] =
      execFunc(prestate, blocktime, deploys, protocolVersion)
    override def commit(
        prestate: ByteString,
        effects: Seq[TransformEntry],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, ExecutionEngineService.CommitResult]] = commitFunc(prestate, effects)

    override def query(
        state: ByteString,
        baseKey: Key,
        path: Seq[String],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, cltype.StoredValue]] = queryFunc(state, baseKey, path)
  }

  def failExec[F[_]: Applicative](bonds: Seq[Bond] = Seq.empty): ExecutionEngineService[F] =
    new NoOpExecutionEngineService[F](bonds) {
      override def exec(
          prestate: ByteString,
          blocktime: Long,
          deploys: Seq[DeployItem],
          protocolVersion: ProtocolVersion
      ): F[Either[Throwable, Seq[DeployResult]]] =
        Either
          .left[Throwable, Seq[DeployResult]](
            new RuntimeException("Failed ExecutionEngineService.exec")
          )
          .pure[F]
    }

  def failCommit[F[_]: Applicative](bonds: Seq[Bond] = Seq.empty): ExecutionEngineService[F] =
    new NoOpExecutionEngineService[F](bonds) {
      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry],
          protocolVersion: ProtocolVersion
      ): F[Either[Throwable, ExecutionEngineService.CommitResult]] =
        Either
          .left[Throwable, ExecutionEngineService.CommitResult](
            new RuntimeException("Failed ExecutionEngineService.commit")
          )
          .pure[F]
    }

  def noOpApi[F[_]: Applicative](
      bonds: Seq[Bond] = Seq.empty
  ): ExecutionEngineService[F] =
    new NoOpExecutionEngineService[F](bonds)

  class NoOpExecutionEngineService[F[_]: Applicative](bonds: Seq[Bond] = Seq.empty)
      extends ExecutionEngineService[F] {

    override def emptyStateHash: ByteString = ByteString.EMPTY

    override def runGenesis(
        genesisConfig: RunGenesisRequest
    ): F[Either[Throwable, GenesisResult]] =
      GenesisResult().asRight[Throwable].pure[F]

    override def upgrade(
        prestate: ByteString,
        upgrade: ChainSpec.UpgradePoint,
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, UpgradeResult]] =
      UpgradeResult().asRight[Throwable].pure[F]

    override def exec(
        prestate: ByteString,
        blocktime: Long,
        deploys: Seq[DeployItem],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, Seq[DeployResult]]] =
      Seq
        .fill(deploys.size)(
          DeployResult(
            DeployResult.Value
              .PreconditionFailure(DeployResult.PreconditionFailure("Test precondition failure."))
          )
        )
        .asRight[Throwable]
        .pure[F]

    override def commit(
        prestate: ByteString,
        effects: Seq[TransformEntry],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, ExecutionEngineService.CommitResult]] =
      ExecutionEngineService
        .CommitResult(prestate, bonds)
        .asRight[Throwable]
        .pure[F]

    override def query(
        state: ByteString,
        baseKey: Key,
        path: Seq[String],
        protocolVersion: ProtocolVersion
    ): F[Either[Throwable, cltype.StoredValue]] =
      (cltype.StoredValue
        .CLValue(cltype.CLValue(cltype.CLType.Bool, Vector.empty))
        .asInstanceOf[cltype.StoredValue])
        .asRight[Throwable]
        .pure[F]
  }

}
