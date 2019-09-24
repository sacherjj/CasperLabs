package io.casperlabs.casper.helper

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.{Concurrent, Sync, Timer}
import cats.implicits._
import cats.temp.par.Par
import cats.{~>, Applicative, ApplicativeError, Defer, Id, Monad, Parallel}
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage._
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.state.{BigInt => _, Unit => _, _}
import io.casperlabs.casper.consensus.{state, Block, Bond}
import io.casperlabs.casper.deploybuffer.{DeployBuffer, MockDeployBuffer}
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.{PrivateKey, PublicKey}
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.ipc
import io.casperlabs.casper.consensus.state.{BigInt => _, Unit => _, _}
import io.casperlabs.casper.finality.singlesweep.FinalityDetector
import io.casperlabs.casper.validation.Validation
import io.casperlabs.ipc.DeployResult.Value.ExecutionResult
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.shared.{Cell, Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.eval.instances.CatsParallelForTask
import monix.execution.Scheduler

import scala.collection.mutable.{Map => MutMap}
import scala.util.Random
import io.casperlabs.crypto.Keys
import io.casperlabs.casper.validation.ValidationImpl

/** Base class for test nodes with fields used by tests exposed as public. */
abstract class HashSetCasperTestNode[F[_]](
    val local: Node,
    sk: PrivateKey,
    val genesis: Block,
    val dagStorageDir: Path,
    val blockStorageDir: Path,
    maybeMakeEE: Option[HashSetCasperTestNode.MakeExecutionEngineService[F]]
)(
    implicit
    concurrentF: Concurrent[F],
    val blockStorage: BlockStorage[F],
    val dagStorage: DagStorage[F],
    val metricEff: Metrics[F],
    val casperState: Cell[F, CasperState]
) {
  implicit val logEff: LogStub[F]

  implicit val casperEff: MultiParentCasperImpl[F]
  implicit val deployBufferEff: DeployBuffer[F]
  implicit val lastFinalizedBlockHashContainer: LastFinalizedBlockHashContainer[F] =
    NoOpsLastFinalizedBlockHashContainer.create[F](genesis.blockHash)
  implicit val safetyOracleEff: FinalityDetector[F]

  val validatorId = ValidatorIdentity(Ed25519.tryToPublic(sk).get, sk, Ed25519)

  val bonds = genesis.getHeader.getState.bonds
    .map(b => PublicKey(b.validatorPublicKey.toByteArray) -> b.stake)
    .toMap

  implicit val casperSmartContractsApi =
    maybeMakeEE.map(_(bonds)) getOrElse
      HashSetCasperTestNode.simpleEEApi[F](bonds)

  /** Handle one message. */
  def receive(): F[Unit]

  /** Forget messages not yet delivered. */
  def clearMessages(): F[Unit]

  /** Put the genesis in the store. */
  def initialize(): F[Unit] =
    // pre-population removed from internals of Casper
    blockStorage.put(genesis.blockHash, genesis, Seq.empty) *>
      dagStorage.getRepresentation.flatMap { dag =>
        ExecutionEngineServiceStub
          .validateBlockCheckpoint[F](
            genesis,
            dag
          )
          .void
      }

  /** Close and delete storage. */
  def tearDown(): F[Unit] =
    tearDownNode().map { _ =>
      blockStorageDir.recursivelyDelete()
      dagStorageDir.recursivelyDelete()
    }

  /** Close storage. */
  def tearDownNode(): F[Unit] =
    for {
      _ <- blockStorage.close()
      _ <- dagStorage.close()
    } yield ()
}

trait HashSetCasperTestNodeFactory {
  import HashSetCasperTestNode._

  type TestNode[F[_]] <: HashSetCasperTestNode[F]

  def standaloneF[F[_]](
      genesis: Block,
      transforms: Seq[TransformEntry],
      sk: PrivateKey,
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit
      concurrentF: Concurrent[F],
      parF: Par[F],
      timerF: Timer[F]
  ): F[TestNode[F]]

  def standaloneEff(
      genesis: Block,
      transforms: Seq[TransformEntry],
      sk: PrivateKey,
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit scheduler: Scheduler
  ): TestNode[Task] =
    standaloneF[Task](genesis, transforms, sk, storageSize, faultToleranceThreshold)(
      Concurrent[Task],
      Par[Task],
      Timer[Task]
    ).unsafeRunSync

  def networkF[F[_]](
      sks: IndexedSeq[PrivateKey],
      genesis: Block,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f,
      maybeMakeEE: Option[HashSetCasperTestNode.MakeExecutionEngineService[F]] = None
  )(
      implicit
      concurrentF: Concurrent[F],
      parF: Par[F],
      timerF: Timer[F]
  ): F[IndexedSeq[TestNode[F]]]

  def networkEff(
      sks: IndexedSeq[PrivateKey],
      genesis: Block,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f,
      maybeMakeEE: Option[MakeExecutionEngineService[Task]] = None
  ): Task[IndexedSeq[TestNode[Task]]] =
    networkF[Task](
      sks,
      genesis,
      transforms,
      storageSize,
      faultToleranceThreshold,
      maybeMakeEE
    )(
      Concurrent[Task],
      Par[Task],
      Timer[Task]
    )

  protected def initStorage[F[_]: Concurrent: Log: Metrics](genesis: Block) = {
    val dagStorageDir   = DagStorageTestFixture.dagStorageDir
    val blockStorageDir = DagStorageTestFixture.blockStorageDir
    val env             = Context.env(blockStorageDir, DagStorageTestFixture.mapSize)
    for {
      blockStorage <- FileLMDBIndexBlockStorage.create[F](env, blockStorageDir).map(_.right.get)
      dagStorage <- FileDagStorage.createEmptyFromGenesis[F](
                     FileDagStorage.Config(dagStorageDir),
                     genesis
                   )(Concurrent[F], Log[F], blockStorage, Metrics[F])
    } yield (dagStorageDir, blockStorageDir, dagStorage, blockStorage)
  }
}

object HashSetCasperTestNode {
  type Bonds                            = Map[Keys.PublicKey, Long]
  type MakeExecutionEngineService[F[_]] = Bonds => ExecutionEngineService[F]

  def randomBytes(length: Int): Array[Byte] = Array.fill(length)(Random.nextInt(256).toByte)

  def peerNode(name: String, port: Int): Node =
    Node(ByteString.copyFrom(name.getBytes), name, port, port)

  //TODO: Give a better implementation for use in testing; this one is too simplistic.
  def simpleEEApi[F[_]: Defer: Applicative](
      initialBonds: Map[PublicKey, Long],
      generateConflict: Boolean = false
  ): ExecutionEngineService[F] =
    new ExecutionEngineService[F] {
      import ipc.{Bond => _, _}

      private val zero  = Array.fill(32)(0.toByte)
      private val bonds = initialBonds.map(p => Bond(ByteString.copyFrom(p._1), p._2)).toSeq

      private def getExecutionEffect(deploy: ipc.DeployItem) = {
        // The real execution engine will get the keys from what the code changes, which will include
        // changes to the account nonce for example, but not the deploy timestamp. Make sure the `key`
        // here isn't more specific to a deploy then the real thing would be.
        val code = deploy.getSession.payload match {
          case ipc.DeployPayload.Payload.DeployCode(ipc.DeployCode(code, _)) =>
            code
          case _ => sys.error("Expected DeployPayload.Code")
        }
        val key = Key(
          Key.Value.Hash(Key.Hash(code))
        )
        val (op, transform) = if (!generateConflict) {
          Op(Op.OpInstance.Read(ReadOp())) ->
            Transform(Transform.TransformInstance.Identity(TransformIdentity()))
        } else {
          Op(Op.OpInstance.Write(WriteOp())) ->
            Transform(
              Transform.TransformInstance.Write(
                TransformWrite(
                  state.Value(state.Value.Value.IntValue(0)).some
                )
              )
            )
        }
        val transforEntry = TransformEntry(Some(key), Some(transform))
        val opEntry       = OpEntry(Some(key), Some(op))
        ExecutionEffect(Seq(opEntry), Seq(transforEntry))
      }

      override def emptyStateHash: ByteString = ByteString.EMPTY

      override def exec(
          prestate: ByteString,
          blocktime: Long,
          deploys: Seq[ipc.DeployItem],
          protocolVersion: ProtocolVersion
      ): F[Either[Throwable, Seq[DeployResult]]] =
        //This function returns the same `DeployResult` for all deploys,
        //regardless of their wasm code. It pretends to have run all the deploys,
        //but it doesn't really; it just returns the same result no matter what.
        deploys
          .map(getExecutionEffect(_))
          .map(
            effect =>
              DeployResult(
                ExecutionResult(ipc.DeployResult.ExecutionResult(Some(effect), None, 10))
              )
          )
          .asRight[Throwable]
          .pure[F]

      override def runGenesis(
          genesisConfig: ipc.ChainSpec.GenesisConfig
      ): F[Either[Throwable, GenesisResult]] =
        commit(emptyStateHash, Seq.empty).map {
          _.map(cr => GenesisResult(cr.postStateHash).withEffect(ExecutionEffect()))
        }

      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry]
      ): F[Either[Throwable, ExecutionEngineService.CommitResult]] = {
        //This function increments the prestate by interpreting as an integer and adding 1.
        //The purpose of this is simply to have the output post-state be different
        //than the input pre-state. `effects` is not used.
        val arr    = if (prestate.isEmpty) zero.clone() else prestate.toByteArray
        val n      = BigInt(arr)
        val newArr = pad((n + 1).toByteArray, 32)

        val pk = ByteString.copyFrom(newArr)

        ExecutionEngineService.CommitResult(pk, bonds).asRight[Throwable].pure[F]
      }

      override def query(
          state: ByteString,
          baseKey: Key,
          path: Seq[String]
      ): F[Either[Throwable, Value]] =
        Applicative[F].pure[Either[Throwable, Value]](
          Left(new Exception("Method `query` not implemented on this instance!"))
        )

      override def verifyWasm(contracts: ValidateRequest): F[Either[String, Unit]] =
        ().asRight[String].pure[F]
    }

  private def pad(x: Array[Byte], length: Int): Array[Byte] =
    if (x.length < length) Array.fill(length - x.length)(0.toByte) ++ x
    else x

  def makeValidation[F[_]: MonadThrowable: FunctorRaise[?[_], InvalidBlock]: Log: Time]
      : Validation[F] =
    new ValidationImpl[F] {
      // Tests are not signing the deploys.
      override def deploySignature(d: consensus.Deploy): F[Boolean] = true.pure[F]
    }
}
