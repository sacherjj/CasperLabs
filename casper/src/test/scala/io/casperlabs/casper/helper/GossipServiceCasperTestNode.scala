package io.casperlabs.casper.helper

import java.nio.file.Path

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.temp.par.Par
import cats.mtl.DefaultApplicativeAsk
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.blockstorage._
import io.casperlabs.casper._
import io.casperlabs.casper.consensus
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
import monix.tail.Iterant

import scala.collection.mutable
import scala.concurrent.duration.{Duration, FiniteDuration, MILLISECONDS}
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
    relaying: Relaying[F],
    gossipService: GossipServiceCasperTestNodeFactory.TestGossipService[F],
    validatorToNode: Map[ByteString, Node]
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

  val ownValidatorKey = validatorId match {
    case ValidatorIdentity(key, _, _) => ByteString.copyFrom(key)
  }

  implicit val casperEff: MultiParentCasperImpl[F] = new MultiParentCasperImpl[F](
    new MultiParentCasperImpl.StatelessExecutor(shardId),
    MultiParentCasperImpl.Broadcaster.fromGossipServices(Some(validatorId), relaying),
    Some(validatorId),
    genesis,
    shardId,
    blockProcessingLock,
    faultToleranceThreshold = faultToleranceThreshold
  ) {
    override def addBlock(block: BlockMessage): F[BlockStatus] =
      if (block.sender == ownValidatorKey) {
        // The test is adding something this node created.
        super.addBlock(block)
      } else {
        // The test is adding something it created in another node's name,
        // i.e. it expects that dependencies will be requested.
        val sender  = validatorToNode(block.sender)
        val request = NewBlocksRequest(sender.some, List(block.blockHash))
        gossipService.newBlocks(request).map { response =>
          if (response.isNew) Processing else Valid
        }
      }
  }

  /** Allow RPC calls intended for this node to be processed and enqueue responses. */
  def receive(): F[Unit] = gossipService.receive()

  /** Forget RPC calls intended for this node. */
  def clearMessages(): F[Unit] = gossipService.clearMessages()
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
      parF: Par[F],
      timerF: Timer[F]
  ): F[GossipServiceCasperTestNode[F]] = {
    val name               = "standalone"
    val identity           = peerNode(name, 40400)
    val logicalTime        = new LogicalTime[F]
    implicit val log       = new Log.NOPLog[F]()
    implicit val metricEff = new Metrics.MetricsNOP[F]
    implicit val nodeAsk   = makeNodeAsk(identity)(concurrentF)

    // Standalone, so nobody to relay to.
    val relaying = RelayingImpl(
      new TestNodeDiscovery[F](Nil),
      connectToGossip = _ => ???,
      relayFactor = 0,
      relaySaturation = 0
    )

    initStorage(genesis) flatMap {
      case (blockDagDir, blockStoreDir, blockDagStorage, blockStore) =>
        for {
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
            relaying = relaying,
            gossipService = new TestGossipService[F](),
            validatorToNode = Map.empty // Shouldn't need it.
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
      parF: Par[F],
      timerF: Timer[F]
  ): F[IndexedSeq[GossipServiceCasperTestNode[F]]] = {
    val n     = sks.length
    val names = (1 to n).map(i => s"node-$i")
    val peers = names.map(peerNode(_, 40400))

    var gossipServices = peers.map { peer =>
      peer -> new TestGossipService[F]()
    }.toMap

    val validatorToNode = peers
      .zip(sks)
      .map {
        case (node, sk) =>
          ByteString.copyFrom(Ed25519.toPublic(sk)) -> node
      }
      .toMap

    peers
      .zip(sks)
      .toList
      .traverse {
        case (peer, sk) =>
          val logicalTime          = new LogicalTime[F]
          implicit val log: Log[F] = new Log.NOPLog[F]()
          implicit val metricEff   = new Metrics.MetricsNOP[F]
          implicit val nodeAsk     = makeNodeAsk(peer)(concurrentF)

          // Simulate the broadcast semantics.
          val nodeDiscovery = new TestNodeDiscovery[F](peers.filterNot(_ == peer).toList)

          val connectToGossip: GossipService.Connector[F] =
            peer => gossipServices(peer).asInstanceOf[GossipService[F]].pure[F]

          val relaying = RelayingImpl(
            nodeDiscovery,
            connectToGossip = connectToGossip,
            relayFactor = peers.size - 1,
            relaySaturation = 100
          )

          initStorage(genesis) flatMap {
            case (blockDagDir, blockStoreDir, blockDagStorage, blockStore) =>
              for {
                semaphore <- Semaphore[F](1)
                casperState <- Cell.mvarCell[F, CasperState](
                                CasperState()
                              )
                gossipService = gossipServices(peer)
                node = new GossipServiceCasperTestNode[F](
                  peer,
                  genesis,
                  sk,
                  blockDagDir,
                  blockStoreDir,
                  semaphore,
                  faultToleranceThreshold,
                  relaying = relaying,
                  gossipService = gossipService,
                  validatorToNode = validatorToNode
                )(
                  concurrentF,
                  blockStore,
                  blockDagStorage,
                  logicalTime,
                  metricEff,
                  casperState
                )
                _ <- gossipService.init(node.casperEff, blockStore, relaying, connectToGossip)
              } yield node
          }
      } map (_.toIndexedSeq)

  }
}

object GossipServiceCasperTestNodeFactory {
  class TestNodeDiscovery[F[_]: Applicative](peers: List[Node]) extends NodeDiscovery[F] {
    def discover: F[Unit]                           = ???
    def lookup(id: NodeIdentifier): F[Option[Node]] = ???
    def alivePeersAscendingDistance: F[List[Node]]  = peers.pure[F]
  }

  def makeNodeAsk[F[_]](node: Node)(implicit ev: Applicative[F]) =
    new DefaultApplicativeAsk[F, Node] {
      val applicative: Applicative[F] = ev
      def ask: F[Node]                = node.pure[F]
    }

  /** Accumulate messages until receive is called by the test. */
  class TestGossipService[F[_]: Concurrent: Timer: Par]() extends GossipService[F] {

    /** Exercise the full underlying stack. It's what we are testing here, via the MultiParentCasper tests. */
    var underlying: GossipServiceServer[F] = _

    /** Casper is created a bit later then the TestGossipService instance. */
    def init(
        casper: MultiParentCasperImpl[F],
        blockStore: BlockStore[F],
        relaying: Relaying[F],
        connectToGossip: GossipService.Connector[F]
    )(implicit log: Log[F]): F[Unit] = {

      val resources = for {
        downloadManager <- DownloadManagerImpl[F](
                            maxParallelDownloads = 10,
                            connectToGossip = connectToGossip,
                            backend = new DownloadManagerImpl.Backend[F] {
                              override def hasBlock(blockHash: ByteString): F[Boolean] =
                                blockStore.contains(blockHash)

                              override def validateBlock(block: consensus.Block): F[Unit] =
                                // Casper can only validate, store, but won't gossip because the Broadcaster we give it
                                // will assume the DownloadManager will do that.
                                casper.addBlock(LegacyConversions.fromBlock(block)) map {
                                  case Valid =>
                                  case other => new RuntimeException(s"Non-valid status: $other")
                                }

                              override def storeBlock(block: consensus.Block): F[Unit] =
                                // Validation has already stored it.
                                ().pure[F]

                              override def storeBlockSummary(
                                  summary: consensus.BlockSummary
                              ): F[Unit] =
                                // No means to store summaries separately yet.
                                ().pure[F]
                            },
                            relaying = relaying,
                            retriesConf = DownloadManagerImpl.RetriesConf.noRetries
                          )
      } yield downloadManager

      resources.use {
        case downloadManager =>
          val synchronizer = new SynchronizerImpl[F](
            connectToGossip = connectToGossip,
            backend = new SynchronizerImpl.Backend[F] {
              override def tips: F[List[ByteString]] =
                for {
                  dag       <- casper.blockDag
                  tipHashes <- casper.estimator(dag)
                } yield tipHashes.toList

              override def justifications: F[List[ByteString]] =
                // TODO: Presently there's no way to ask.
                List.empty.pure[F]

              override def validate(blockSummary: consensus.BlockSummary): F[Unit] =
                // TODO: Presently the Validation only works on full blocks.
                ().pure[F]

              override def notInDag(blockHash: ByteString): F[Boolean] =
                blockStore.contains(blockHash).map(!_)
            },
            maxPossibleDepth = Int.MaxValue,
            minBlockCountToCheckBranchingFactor = Int.MaxValue,
            maxBranchingFactor = 2.0,
            maxDepthAncestorsRequest = Int.MaxValue
          )

          for {
            server <- GossipServiceServer[F](
                       backend = new GossipServiceServer.Backend[F] {
                         override def hasBlock(blockHash: ByteString): F[Boolean] =
                           blockStore.contains(blockHash)

                         override def getBlockSummary(
                             blockHash: ByteString
                         ): F[Option[consensus.BlockSummary]] =
                           blockStore
                             .get(blockHash)
                             .map(
                               _.map(mwt => LegacyConversions.toBlockSummary(mwt.getBlockMessage))
                             )

                         override def getBlock(blockHash: ByteString): F[Option[consensus.Block]] =
                           blockStore
                             .get(blockHash)
                             .map(_.map(mwt => LegacyConversions.toBlock(mwt.getBlockMessage)))
                       },
                       synchronizer = synchronizer,
                       downloadManager = downloadManager,
                       consensus = new GossipServiceServer.Consensus[F] {
                         override def onPending(dag: Vector[consensus.BlockSummary]) =
                           ().pure[F]
                         override def onDownloaded(blockHash: ByteString) =
                           // The validation already did what it had to.
                           ().pure[F]
                         override def listTips =
                           ???
                       },
                       // Not testing the genesis ceremony.
                       genesisApprover = new GenesisApprover[F] {
                         override def getCandidate = ???
                         override def addApproval(
                             blockHash: ByteString,
                             approval: consensus.Approval
                         )                          = ???
                         override def awaitApproval = ???
                       },
                       maxChunkSize = 1024 * 1024,
                       maxParallelBlockDownloads = 10
                     )
          } yield {
            underlying = server
          }
      }
    }

    /** With the TransportLayer this would mean the target node receives the full block and adds it.
      * We have to allow `newBlocks` to return for the original block to be able to finish adding,
      * so maybe we can return `true`, and call the underlying service later. But then we have to
      * allow it to play out all async actions, such as downloading blocks, syncing the DAG, etc. */
    def receive(): F[Unit] = ().pure[F]

    /** With the TransportLayer this would mean the target node won't process a message.
      * For us it could mean that it receives the `newBlocks` notification but after that
      * we don't let it play out the async operations, for example by returning errors for
      * all requests it started. */
    def clearMessages(): F[Unit] = ().pure[F]

    override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
      underlying.getBlockChunked(request)

    override def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
      underlying.newBlocks(request)

    override def streamAncestorBlockSummaries(
        request: StreamAncestorBlockSummariesRequest
    ): Iterant[F, consensus.BlockSummary] =
      underlying.streamAncestorBlockSummaries(request)

    // The following methods are not tested in these suites.

    override def addApproval(request: AddApprovalRequest): F[Empty] = ???
    override def getGenesisCandidate(
        request: GetGenesisCandidateRequest
    ): F[consensus.GenesisCandidate] = ???
    override def streamDagTipBlockSummaries(
        request: StreamDagTipBlockSummariesRequest
    ): Iterant[F, consensus.BlockSummary] = ???
    override def streamBlockSummaries(
        request: StreamBlockSummariesRequest
    ): Iterant[F, consensus.BlockSummary] = ???
  }
}
