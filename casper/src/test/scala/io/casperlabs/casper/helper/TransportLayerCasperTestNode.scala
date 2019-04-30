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

import HashSetCasperTestNode.Effect

class TransportLayerCasperTestNode[F[_]](
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
) extends HashSetCasperTestNode[F] {

  implicit val logEff: LogStub[F] = new LogStub[F]()
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

  implicit val casperEff: MultiParentCasperImpl[F] = new MultiParentCasperImpl[F](
    new MultiParentCasperImpl.StatelessExecutor(shardId),
    MultiParentCasperImpl.Broadcaster.fromTransportLayer(),
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

  def clearMessages(): F[Unit] =
    transportLayerEff.clear(local)

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

trait TransportLayerCasperTestNodeFactory extends HashSetCasperTestNodeFactory {

  type TestNode[F[_]] = TransportLayerCasperTestNode[F]

  import HashSetCasperTestNode.peerNode

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
  ): F[TransportLayerCasperTestNode[F]] = {
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
      node = new TransportLayerCasperTestNode[F](
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

  def networkF[F[_]](
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit errorHandler: ErrorHandler[F],
      concurrentF: Concurrent[F]
  ): F[IndexedSeq[TransportLayerCasperTestNode[F]]] = {
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
              node = new TransportLayerCasperTestNode[F](
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
}
