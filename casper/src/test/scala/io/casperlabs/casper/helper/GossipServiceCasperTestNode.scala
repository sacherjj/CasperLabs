package io.casperlabs.casper.helper

import java.nio.file.Path

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.temp.par.Par
import cats.mtl.DefaultApplicativeAsk
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage._
import io.casperlabs.casper._
import io.casperlabs.casper.helper.BlockDagStorageTestFixture.mapSize
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib._
import io.casperlabs.catscontrib.effect.implicits._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping._
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.shared.{Cell, Log, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.Random

class GossipServiceCasperTestNode[F[_]](
    local: Node,
    genesis: BlockMessage,
    sk: Array[Byte],
    blockDagDir: Path,
    blockStoreDir: Path,
    blockProcessingLock: Semaphore[F],
    faultToleranceThreshold: Float = 0f,
    shardId: String = "casperlabs",
    relaying: Relaying[F]
)(
    implicit
    concurrentF: Concurrent[F],
    blockStore: BlockStore[F],
    blockDagStorage: BlockDagStorage[F],
    timeEff: Time[F],
    metricEff: Metrics[F],
    casperState: Cell[F, CasperState]
) extends HashSetCasperTestNode[F](
      local,
      sk,
      genesis,
      blockDagDir,
      blockStoreDir
    )(concurrentF, blockStore, blockDagStorage, metricEff, casperState) {

  implicit val cliqueOracleEffect = SafetyOracle.cliqueOracle[F]

  //val defaultTimeout = FiniteDuration(1000, MILLISECONDS)

  implicit val casperEff: MultiParentCasperImpl[F] = new MultiParentCasperImpl[F](
    new MultiParentCasperImpl.StatelessExecutor(shardId),
    MultiParentCasperImpl.Broadcaster.fromGossipServices(Some(validatorId), relaying),
    Some(validatorId),
    genesis,
    shardId,
    blockProcessingLock,
    faultToleranceThreshold = faultToleranceThreshold
  )

  /** Allow RPC calls intended for this node to be processed and enqueue responses. */
  def receive(): F[Unit] =
    ???

  /** Forget RPC calls intended for this node. */
  def clearMessages(): F[Unit] =
    ???
}

trait GossipServiceCasperTestNodeFactory extends HashSetCasperTestNodeFactory {

  type TestNode[F[_]] = GossipServiceCasperTestNode[F]

  import HashSetCasperTestNode.peerNode
  import GossipServiceCasperTestNodeFactory._

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
      parF: Par[F]
  ): F[GossipServiceCasperTestNode[F]] = {
    val name               = "standalone"
    val identity           = peerNode(name, 40400)
    val logicalTime        = new LogicalTime[F]
    implicit val log       = new Log.NOPLog[F]()
    implicit val metricEff = new Metrics.MetricsNOP[F]
    implicit val nodeAsk   = makeNodeAsk(identity)(concurrentF)

    val blockDagDir   = BlockDagStorageTestFixture.blockDagStorageDir
    val blockStoreDir = BlockDagStorageTestFixture.blockStorageDir
    val env           = Context.env(blockStoreDir, BlockDagStorageTestFixture.mapSize)
    for {
      blockStore <- FileLMDBIndexBlockStore.create[F](env, blockStoreDir).map(_.right.get)
      blockDagStorage <- BlockDagFileStorage.createEmptyFromGenesis[F](
                          BlockDagFileStorage.Config(blockDagDir),
                          genesis
                        )(concurrentF, log, blockStore, metricEff)
      blockProcessingLock <- Semaphore[F](1)
      casperState         <- Cell.mvarCell[F, CasperState](CasperState())
      node = new GossipServiceCasperTestNode[F](
        identity,
        genesis,
        sk,
        blockDagDir,
        blockStoreDir,
        blockProcessingLock,
        faultToleranceThreshold,
        // Standalone, so nobody to relay to.
        relaying = RelayingImpl(
          new NoPeersNodeDiscovery[F](),
          connectToGossip = _ => ???,
          relayFactor = 0,
          relaySaturation = 0
        )
      )(
        concurrentF,
        blockStore,
        blockDagStorage,
        logicalTime,
        metricEff,
        casperState
      )
      _ <- node.initialize
    } yield node
  }

  def networkF[F[_]](
      sks: IndexedSeq[Array[Byte]],
      genesis: BlockMessage,
      transforms: Seq[TransformEntry],
      storageSize: Long = 1024L * 1024 * 10,
      faultToleranceThreshold: Float = 0f
  )(
      implicit errorHandler: ErrorHandler[F],
      concurrentF: Concurrent[F],
      parF: Par[F]
  ): F[IndexedSeq[GossipServiceCasperTestNode[F]]] =
    ???
}

object GossipServiceCasperTestNodeFactory {
  class NoPeersNodeDiscovery[F[_]: Applicative] extends NodeDiscovery[F] {
    def discover: F[Unit]                           = ???
    def lookup(id: NodeIdentifier): F[Option[Node]] = ???
    def alivePeersAscendingDistance: F[List[Node]]  = List.empty.pure[F]
  }

  def makeNodeAsk[F[_]](node: Node)(implicit ev: Applicative[F]) =
    new DefaultApplicativeAsk[F, Node] {
      val applicative: Applicative[F] = ev
      def ask: F[Node]                = node.pure[F]
    }
}
