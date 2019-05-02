package io.casperlabs.casper.helper

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.{Concurrent, Timer}
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import cats.{~>, Applicative, ApplicativeError, Defer, Id, Monad, Parallel}
import cats.temp.par.Par
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage._
import io.casperlabs.casper._
import io.casperlabs.casper.helper.BlockDagStorageTestFixture.mapSize
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.comm.CasperPacketHandler.{
  ApprovedBlockReceivedHandler,
  CasperPacketHandlerImpl,
  CasperPacketHandlerInternal
}
import io.casperlabs.casper.util.comm.TransportLayerTestImpl
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.rp.Connect
import io.casperlabs.comm.rp.Connect._
import io.casperlabs.comm.rp.HandleMessages.handle
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.ipc
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.p2p.effects.PacketHandler
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.shared.{Cell, Log}
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler
import monix.eval.instances.CatsParallelForTask

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.Random

/** Base class for test nodes with fields used by tests exposed as public. */
abstract class HashSetCasperTestNode[F[_]](
    val local: Node,
    sk: Array[Byte],
    val genesis: BlockMessage,
    val blockDagDir: Path,
    val blockStoreDir: Path
)(
    implicit
    concurrentF: Concurrent[F],
    val blockStore: BlockStore[F],
    val blockDagStorage: BlockDagStorage[F],
    val metricEff: Metrics[F],
    val casperState: Cell[F, CasperState]
) {
  implicit val logEff: LogStub[F] = new LogStub[F]()

  implicit val casperEff: MultiParentCasperImpl[F] with HashSetCasperTestNode.AddBlockProxy[F]

  val validatorId = ValidatorIdentity(Ed25519.toPublic(sk), sk, "ed25519")

  val bonds = genesis.body
    .flatMap(_.state.map(_.bonds.map(b => b.validator.toByteArray -> b.stake).toMap))
    .getOrElse(Map.empty)

  implicit val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[F](bonds)

  /** Handle one message. */
  def receive(): F[Unit]

  /** Forget messages not yet delivered. */
  def clearMessages(): F[Unit]

  /** Put the genesis in the store. */
  def initialize(): F[Unit] =
    // pre-population removed from internals of Casper
    blockStore.put(genesis.blockHash, genesis, Seq.empty) *>
      blockDagStorage.getRepresentation.flatMap { dag =>
        ExecEngineUtil
          .validateBlockCheckpoint[F](
            genesis,
            dag
          )
          .void
      }

  /** Close and delete storage. */
  def tearDown(): F[Unit] =
    tearDownNode().map { _ =>
      blockStoreDir.recursivelyDelete()
      blockDagDir.recursivelyDelete()
    }

  /** Close storage. */
  def tearDownNode(): F[Unit] =
    for {
      _ <- blockStore.close()
      _ <- blockDagStorage.close()
    } yield ()
}

trait HashSetCasperTestNodeFactory {
  import HashSetCasperTestNode._

  type TestNode[F[_]] <: HashSetCasperTestNode[F]

  def standaloneF[F[_]](
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      sk: Array[Byte],
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
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      sk: Array[Byte],
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
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit errorHandler: ErrorHandler[F],
      concurrentF: Concurrent[F],
      parF: Par[F],
      timerF: Timer[F]
  ): F[IndexedSeq[TestNode[F]]]

  def networkEff(
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  ): Effect[IndexedSeq[TestNode[Effect]]] =
    networkF[Effect](sks, genesis, transforms, storageSize, faultToleranceThreshold)(
      ApplicativeError_[Effect, CommError],
      Concurrent[Effect],
      Par[Effect],
      Timer[Effect]
    )

  protected def initStorage[F[_]: Concurrent: Log: Metrics](genesis: BlockMessage) = {
    val blockDagDir   = BlockDagStorageTestFixture.blockDagStorageDir
    val blockStoreDir = BlockDagStorageTestFixture.blockStorageDir
    val env           = Context.env(blockStoreDir, BlockDagStorageTestFixture.mapSize)
    for {
      blockStore <- FileLMDBIndexBlockStore.create[F](env, blockStoreDir).map(_.right.get)
      blockDagStorage <- BlockDagFileStorage.createEmptyFromGenesis[F](
                          BlockDagFileStorage.Config(blockDagDir),
                          genesis
                        )(Concurrent[F], Log[F], blockStore, Metrics[F])
    } yield (blockDagDir, blockStoreDir, blockDagStorage, blockStore)
  }
}

object HashSetCasperTestNode {
  type Effect[A] = EitherT[Task, CommError, A]

  /** Currently the tests call `addBlock` directly, but with the GossipService tests
    * we want that to happen via gossiping, if the node isn't the creator. Yet this
    * is currently also the only method that can do validation, so there has to be a
    * back door to run blocks created by other nodes. */
  trait AddBlockProxy[F[_]] { self: MultiParentCasper[F] =>
    // This should point at the original `addBlock`,
    // allowing the tests to override it but also call the original.
    def superAddBlock(blockMessage: BlockMessage): F[BlockStatus] =
      addBlock(blockMessage)
  }

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
      initialBonds: Map[Array[Byte], Long]
  ): ExecutionEngineService[F] =
    new ExecutionEngineService[F] {
      import ipc._
      private val zero  = Array.fill(32)(0.toByte)
      private var bonds = initialBonds.map(p => Bond(ByteString.copyFrom(p._1), p._2)).toSeq

      private def getExecutionEffect(deploy: Deploy) = {
        // The real execution engine will get the keys from what the code changes, which will include
        // changes to the account nonce for example, but not the deploy timestamp. Make sure the `key`
        // here isn't more specific to a deploy then the real thing would be.
        val key           = Key(Key.KeyInstance.Hash(KeyHash(deploy.session.fold(ByteString.EMPTY)(_.code))))
        val transform     = Transform(Transform.TransformInstance.Identity(TransformIdentity()))
        val op            = Op(Op.OpInstance.Read(ReadOp()))
        val transforEntry = TransformEntry(Some(key), Some(transform))
        val opEntry       = OpEntry(Some(key), Some(op))
        ExecutionEffect(Seq(opEntry), Seq(transforEntry))
      }

      override def emptyStateHash: ByteString = ByteString.EMPTY

      override def exec(
          prestate: ByteString,
          deploys: Seq[Deploy],
          protocolVersion: ipc.ProtocolVersion
      ): F[Either[Throwable, Seq[DeployResult]]] =
        //This function returns the same `DeployResult` for all deploys,
        //regardless of their wasm code. It pretends to have run all the deploys,
        //but it doesn't really; it just returns the same result no matter what.
        deploys
          .map(d => DeployResult(10, DeployResult.Result.Effects(getExecutionEffect(d))))
          .asRight[Throwable]
          .pure[F]

      override def commit(
          prestate: ByteString,
          effects: Seq[TransformEntry]
      ): F[Either[Throwable, ByteString]] = {
        //This function increments the prestate by interpreting as an integer and adding 1.
        //The purpose of this is simply to have the output post-state be different
        //than the input pre-state. `effects` is not used.
        val arr    = if (prestate.isEmpty) zero.clone() else prestate.toByteArray
        val n      = BigInt(arr)
        val newArr = pad((n + 1).toByteArray, 32)

        ByteString.copyFrom(newArr).asRight[Throwable].pure[F]
      }

      override def query(
          state: ByteString,
          baseKey: Key,
          path: Seq[String]
      ): F[Either[Throwable, Value]] =
        Applicative[F].pure[Either[Throwable, Value]](
          Left(new Exception("Method `query` not implemented on this instance!"))
        )
      override def computeBonds(hash: ByteString)(implicit log: Log[F]): F[Seq[Bond]] =
        bonds.pure[F]
      override def setBonds(newBonds: Map[Array[Byte], Long]): F[Unit] =
        Defer[F].defer(Applicative[F].unit.map { _ =>
          bonds = newBonds.map {
            case (validator, weight) => Bond(ByteString.copyFrom(validator), weight)
          }.toSeq
        })
      override def verifyWasm(contracts: ValidateRequest): F[Either[String, Unit]] =
        ().asRight[String].pure[F]
    }

  private def pad(x: Array[Byte], length: Int): Array[Byte] =
    if (x.length < length) Array.fill(length - x.length)(0.toByte) ++ x
    else x
}
