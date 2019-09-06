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
import io.casperlabs.comm.CommError.ErrorHandler
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
    val validateNonces: Boolean,
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
    maybeMakeEE.map(_(bonds, validateNonces)) getOrElse
      HashSetCasperTestNode.simpleEEApi[F](bonds, validateNonces)

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
      errorHandler: ErrorHandler[F],
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
  ): TestNode[Effect] =
    standaloneF[Effect](genesis, transforms, sk, storageSize, faultToleranceThreshold)(
      ApplicativeError_[Effect, CommError],
      Concurrent[Effect],
      Par[Effect],
      Timer[Effect]
    ).value.unsafeRunSync.right.get

  def networkF[F[_]](
      sks: IndexedSeq[PrivateKey],
      genesis: Block,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f,
      validateNonces: Boolean = true,
      maybeMakeEE: Option[HashSetCasperTestNode.MakeExecutionEngineService[F]] = None
  )(
      implicit errorHandler: ErrorHandler[F],
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
      validateNonces: Boolean = true,
      maybeMakeEE: Option[MakeExecutionEngineService[Effect]] = None
  ): Effect[IndexedSeq[TestNode[Effect]]] =
    networkF[Effect](
      sks,
      genesis,
      transforms,
      storageSize,
      faultToleranceThreshold,
      validateNonces,
      maybeMakeEE
    )(
      ApplicativeError_[Effect, CommError],
      Concurrent[Effect],
      Par[Effect],
      Timer[Effect]
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
  type Effect[A]                        = EitherT[Task, CommError, A]
  type Bonds                            = Map[Keys.PublicKey, Long]
  type MakeExecutionEngineService[F[_]] = (Bonds, Boolean) => ExecutionEngineService[F]

  val appErrId = new ApplicativeError[Id, CommError] {
    def ap[A, B](ff: Id[A => B])(fa: Id[A]): Id[B] = Applicative[Id].ap[A, B](ff)(fa)
    def pure[A](x: A): Id[A]                       = Applicative[Id].pure[A](x)
    def raiseError[A](e: CommError): Id[A] = {
      val errString = e match {
        case UnknownCommError(msg)                => s"UnknownCommError($msg)"
        case DatagramSizeError(size)              => s"DatagramSizeError($size)"
        case DatagramFramingError(ex)             => s"DatagramFramingError($ex)"
        case DatagramException(ex)                => s"DatagramException($ex)"
        case HeaderNotAvailable                   => "HeaderNotAvailable"
        case ProtocolException(th)                => s"ProtocolException($th)"
        case UnknownProtocolError(msg)            => s"UnknownProtocolError($msg)"
        case PublicKeyNotAvailable(node)          => s"PublicKeyNotAvailable($node)"
        case ParseError(msg)                      => s"ParseError($msg)"
        case EncryptionHandshakeIncorrectlySigned => "EncryptionHandshakeIncorrectlySigned"
        case BootstrapNotProvided                 => "BootstrapNotProvided"
        case PeerNodeNotFound(peer)               => s"PeerNodeNotFound($peer)"
        case PeerUnavailable(peer)                => s"PeerUnavailable($peer)"
        case MalformedMessage(pm)                 => s"MalformedMessage($pm)"
        case CouldNotConnectToBootstrap           => "CouldNotConnectToBootstrap"
        case InternalCommunicationError(msg)      => s"InternalCommunicationError($msg)"
        case TimeOut                              => "TimeOut"
        case _                                    => e.toString
      }

      throw new Exception(errString)
    }

    def handleErrorWith[A](fa: Id[A])(f: (CommError) => Id[A]): Id[A] = fa
  }

  implicit val syncEffectInstance = cats.effect.Sync.catsEitherTSync[Task, CommError]

  implicit val parEffectInstance: Par[Effect] = Par.fromParallel(CatsParallelForEffect)

  // We could try figuring this out for a type as follows:
  // type EffectPar[A] = EitherT[Task.Par, CommError, A]
  object CatsParallelForEffect extends Parallel[Effect, Task.Par] {
    override def applicative: Applicative[Task.Par] = CatsParallelForTask.applicative
    override def monad: Monad[Effect]               = Concurrent[Effect]

    override val sequential: Task.Par ~> Effect = new (Task.Par ~> Effect) {
      def apply[A](fa: Task.Par[A]): Effect[A] = {
        val task = Task.Par.unwrap(fa)
        EitherT.liftF(task)
      }
    }
    override val parallel: Effect ~> Task.Par = new (Effect ~> Task.Par) {
      def apply[A](fa: Effect[A]): Task.Par[A] = {
        val task = fa.value.flatMap {
          case Left(ce) => Task.raiseError(new RuntimeException(ce.toString))
          case Right(a) => Task.pure(a)
        }
        Task.Par.apply(task)
      }
    }
  }

  val errorHandler = ApplicativeError_.applicativeError[Id, CommError](appErrId)

  def randomBytes(length: Int): Array[Byte] = Array.fill(length)(Random.nextInt(256).toByte)

  def peerNode(name: String, port: Int): Node =
    Node(ByteString.copyFrom(name.getBytes), name, port, port)

  //TODO: Give a better implementation for use in testing; this one is too simplistic.
  def simpleEEApi[F[_]: Defer: Applicative](
      initialBonds: Map[PublicKey, Long],
      validateNonces: Boolean = true,
      generateConflict: Boolean = false
  ): ExecutionEngineService[F] =
    new ExecutionEngineService[F] {
      import ipc.{Bond => _, _}

      // NOTE: Some tests would benefit from tacking this per pre-state-hash,
      // but when I tried to do that a great many more failed.
      private val accountNonceTracker: MutMap[ByteString, Long] =
        MutMap.empty.withDefaultValue(0)

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

      // Validate that account's nonces increment monotonically by 1.
      // Assumes that any account address already exists in the GlobalState with nonce = 0.
      private def validateNonce(deploy: ipc.DeployItem): Int = synchronized {
        if (!validateNonces) {
          0
        } else {
          val deployAccount = deploy.address
          val deployNonce   = deploy.nonce
          val oldNonce      = accountNonceTracker(deployAccount)
          val expected      = oldNonce + 1
          val sign          = math.signum(deployNonce - expected)
          if (sign == 0) accountNonceTracker(deployAccount) = deployNonce
          sign.toInt
        }
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
          .map { d =>
            validateNonce(d) match {
              case 0 =>
                DeployResult(
                  ExecutionResult(
                    ipc.DeployResult.ExecutionResult(Some(getExecutionEffect(d)), None, 10)
                  )
                )
              case 1 =>
                DeployResult(DeployResult.Value.InvalidNonce(DeployResult.InvalidNonce(d.nonce)))
              case -1 =>
                DeployResult(
                  DeployResult.Value.PreconditionFailure(
                    DeployResult.PreconditionFailure("Nonce was less then expected.")
                  )
                )
            }
          }
          .asRight[Throwable]
          .pure[F]

      override def runGenesis(
          deploys: Seq[ipc.DeployItem],
          protocolVersion: ProtocolVersion
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
