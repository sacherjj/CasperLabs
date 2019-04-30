package io.casperlabs.casper.helper

import java.nio.file.Path

import cats.data.EitherT
import cats.effect.Concurrent
import cats.effect.concurrent.{Ref, Semaphore}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Defer, Id, Monad}
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

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.Random

class HashSetCasperTestNode[F[_]](
    name: String,
    val local: Node,
    tle: TransportLayerTestImpl[F],
    val genesis: BlockMessage,
    val transforms: Seq[TransformEntry],
    sk: Array[Byte],
    logicalTime: LogicalTime[F],
    implicit val errorHandlerEff: ErrorHandler[F],
    storageSize: Long,
    val blockDagDir: Path,
    val blockStoreDir: Path,
    blockProcessingLock: Semaphore[F],
    faultToleranceThreshold: Float = 0f,
    shardId: String = "casperlabs"
)(
    implicit
    concurrentF: Concurrent[F],
    val blockStore: BlockStore[F],
    val blockDagStorage: BlockDagStorage[F],
    val metricEff: Metrics[F],
    val casperState: Cell[F, CasperState]
) {

  implicit val logEff             = new LogStub[F]
  implicit val timeEff            = logicalTime
  implicit val connectionsCell    = Cell.unsafe[F, Connections](Connect.Connections.empty)
  implicit val transportLayerEff  = tle
  implicit val cliqueOracleEffect = SafetyOracle.cliqueOracle[F]
  implicit val rpConfAsk          = createRPConfAsk[F](local)

  val bonds = genesis.body
    .flatMap(_.state.map(_.bonds.map(b => b.validator.toByteArray -> b.stake).toMap))
    .getOrElse(Map.empty)

  implicit val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[F](bonds)

  val defaultTimeout = FiniteDuration(1000, MILLISECONDS)

  val validatorId = ValidatorIdentity(Ed25519.toPublic(sk), sk, "ed25519")

  val approvedBlock = ApprovedBlock(candidate = Some(ApprovedBlockCandidate(block = Some(genesis))))

  implicit val labF =
    LastApprovedBlock.unsafe[F](Some(ApprovedBlockWithTransforms(approvedBlock, transforms)))
  val postGenesisStateHash = ProtoUtil.postStateHash(genesis)

  implicit val casperEff = new MultiParentCasperImpl[F](
    new MultiParentCasperImpl.StatelessExecutor(shardId),
    new MultiParentCasperImpl.Broadcaster(),
    Some(validatorId),
    genesis,
    shardId,
    blockProcessingLock,
    faultToleranceThreshold = faultToleranceThreshold
  )

  implicit val multiparentCasperRef = MultiParentCasperRef.unsafe[F](Some(casperEff))

  val handlerInternal =
    new ApprovedBlockReceivedHandler(casperEff, approvedBlock, Some(validatorId))
  val casperPacketHandler =
    new CasperPacketHandlerImpl[F](
      Ref.unsafe[F, CasperPacketHandlerInternal[F]](handlerInternal),
      Some(validatorId)
    )
  implicit val packetHandlerEff = PacketHandler.pf[F](
    casperPacketHandler.handle
  )

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

  def receive(): F[Unit] = tle.receive(p => handle[F](p, defaultTimeout), kp(().pure[F]))

  def tearDown(): F[Unit] =
    tearDownNode().map { _ =>
      blockStoreDir.recursivelyDelete()
      blockDagDir.recursivelyDelete()
    }

  def tearDownNode(): F[Unit] =
    for {
      _ <- blockStore.close()
      _ <- blockDagStorage.close()
    } yield ()
}

object HashSetCasperTestNode {
  type Effect[A] = EitherT[Task, CommError, A]

  def standaloneF[F[_]](
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      sk: Array[Byte],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit
      errorHandler: ErrorHandler[F],
      concurrentF: Concurrent[F]
  ): F[HashSetCasperTestNode[F]] = {
    val name     = "standalone"
    val identity = peerNode(name, 40400)
    val tle =
      new TransportLayerTestImpl[F](identity, Map.empty[Node, Ref[F, mutable.Queue[Protocol]]])
    val logicalTime: LogicalTime[F] = new LogicalTime[F]
    implicit val log                = new Log.NOPLog[F]()
    implicit val metricEff          = new Metrics.MetricsNOP[F]

    val blockDagDir   = BlockDagStorageTestFixture.blockDagStorageDir
    val blockStoreDir = BlockDagStorageTestFixture.blockStorageDir
    val env           = Context.env(blockStoreDir, mapSize)
    for {
      blockStore <- FileLMDBIndexBlockStore.create[F](env, blockStoreDir).map(_.right.get)
      blockDagStorage <- BlockDagFileStorage.createEmptyFromGenesis[F](
                          BlockDagFileStorage.Config(blockDagDir),
                          genesis
                        )(Concurrent[F], Log[F], blockStore, metricEff)
      blockProcessingLock <- Semaphore[F](1)
      casperState         <- Cell.mvarCell[F, CasperState](CasperState())
      node = new HashSetCasperTestNode[F](
        name,
        identity,
        tle,
        genesis,
        transforms,
        sk,
        logicalTime,
        errorHandler,
        storageSize,
        blockDagDir,
        blockStoreDir,
        blockProcessingLock,
        faultToleranceThreshold
      )(
        concurrentF,
        blockStore,
        blockDagStorage,
        metricEff,
        casperState
      )
      result <- node.initialize.map(_ => node)
    } yield result
  }

  def standaloneEff(
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      sk: Array[Byte],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit scheduler: Scheduler
  ): HashSetCasperTestNode[Effect] =
    standaloneF[Effect](genesis, transforms, sk, storageSize, faultToleranceThreshold)(
      ApplicativeError_[Effect, CommError],
      Concurrent[Effect]
    ).value.unsafeRunSync.right.get

  def networkF[F[_]](
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit errorHandler: ErrorHandler[F],
      concurrentF: Concurrent[F]
  ): F[IndexedSeq[HashSetCasperTestNode[F]]] = {
    val n     = sks.length
    val names = (1 to n).map(i => s"node-$i")
    val peers = names.map(peerNode(_, 40400))
    val msgQueues = peers
      .map(_ -> new mutable.Queue[Protocol]())
      .toMap
      .mapValues(Ref.unsafe[F, mutable.Queue[Protocol]])
    val logicalTime: LogicalTime[F] = new LogicalTime[F]

    val nodesF =
      names
        .zip(peers)
        .zip(sks)
        .toList
        .traverse {
          case ((n, p), sk) =>
            val tle                = new TransportLayerTestImpl[F](p, msgQueues)
            implicit val log       = new Log.NOPLog[F]()
            implicit val metricEff = new Metrics.MetricsNOP[F]

            val blockDagDir   = BlockDagStorageTestFixture.blockDagStorageDir
            val blockStoreDir = BlockDagStorageTestFixture.blockStorageDir
            val env           = Context.env(blockStoreDir, mapSize)
            for {
              blockStore <- FileLMDBIndexBlockStore.create[F](env, blockStoreDir).map(_.right.get)
              blockDagStorage <- BlockDagFileStorage.createEmptyFromGenesis[F](
                                  BlockDagFileStorage.Config(blockDagDir),
                                  genesis
                                )(Concurrent[F], Log[F], blockStore, metricEff)
              semaphore <- Semaphore[F](1)
              casperState <- Cell.mvarCell[F, CasperState](
                              CasperState()
                            )
              node = new HashSetCasperTestNode[F](
                n,
                p,
                tle,
                genesis,
                transforms,
                sk,
                logicalTime,
                errorHandler,
                storageSize,
                blockDagDir,
                blockStoreDir,
                semaphore,
                faultToleranceThreshold
              )(
                concurrentF,
                blockStore,
                blockDagStorage,
                metricEff,
                casperState
              )
            } yield node
        }
        .map(_.toVector)

    import Connections._
    //make sure all nodes know about each other
    for {
      nodes <- nodesF
      pairs = for {
        n <- nodes
        m <- nodes
        if n.local != m.local
      } yield (n, m)
      _ <- nodes.traverse(_.initialize).void
      _ <- pairs.foldLeft(().pure[F]) {
            case (f, (n, m)) =>
              f.flatMap(
                _ =>
                  n.connectionsCell.flatModify(
                    _.addConn[F](m.local)(Monad[F], n.logEff, n.metricEff)
                  )
              )
          }
    } yield nodes
  }

  def networkEff(
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  ): Effect[IndexedSeq[HashSetCasperTestNode[Effect]]] =
    networkF[Effect](sks, genesis, transforms, storageSize, faultToleranceThreshold)(
      ApplicativeError_[Effect, CommError],
      Concurrent[Effect]
    )

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

  val errorHandler = ApplicativeError_.applicativeError[Id, CommError](appErrId)

  def randomBytes(length: Int): Array[Byte] = Array.fill(length)(Random.nextInt(256).toByte)

  def peerNode(name: String, port: Int): Node =
    Node(ByteString.copyFrom(name.getBytes), "host", port, port)

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
