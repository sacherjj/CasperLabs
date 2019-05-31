package io.casperlabs.casper.helper

import java.nio.file.Path

import cats._
import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.mtl.DefaultApplicativeAsk
import cats.temp.par.Par
import com.google.protobuf.ByteString
import eu.timepit.refined.auto._
import io.casperlabs.blockstorage._
import io.casperlabs.casper.consensus
import io.casperlabs.casper._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.discovery.{Node, NodeDiscovery, NodeIdentifier}
import io.casperlabs.comm.gossiping._
import io.casperlabs.crypto.Keys.PrivateKey
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.{Cell, Log, Time}
import monix.tail.Iterant

import scala.collection.immutable.Queue

class GossipServiceCasperTestNode[F[_]](
    local: Node,
    genesis: consensus.Block,
    sk: PrivateKey,
    blockDagDir: Path,
    blockStoreDir: Path,
    blockProcessingLock: Semaphore[F],
    faultToleranceThreshold: Float = 0f,
    chainId: String = "casperlabs",
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
    casperState: Cell[F, CasperState],
    val logEff: LogStub[F]
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

  // `addBlock` called in many ways:
  // - test proposes a block on the node that created it
  // - test tries to give a block created by node A to node B without gossiping
  // - the download manager tries to validate a block
  implicit val casperEff: MultiParentCasperImpl[F] =
    new MultiParentCasperImpl[F](
      new MultiParentCasperImpl.StatelessExecutor(chainId),
      MultiParentCasperImpl.Broadcaster.fromGossipServices(Some(validatorId), relaying),
      Some(validatorId),
      genesis,
      chainId,
      blockProcessingLock,
      faultToleranceThreshold = faultToleranceThreshold
    )

  /** Allow RPC calls intended for this node to be processed and enqueue responses. */
  def receive(): F[Unit] = gossipService.receive()

  /** Forget RPC calls intended for this node. */
  def clearMessages(): F[Unit] = gossipService.clearMessages()

  override def tearDownNode() =
    gossipService.shutdown >> super.tearDownNode()
}

trait GossipServiceCasperTestNodeFactory extends HashSetCasperTestNodeFactory {

  type TestNode[F[_]] = GossipServiceCasperTestNode[F]

  import GossipServiceCasperTestNodeFactory._
  import HashSetCasperTestNode.peerNode

  def standaloneF[F[_]](
      genesis: consensus.Block,
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
  ): F[GossipServiceCasperTestNode[F]] = {
    val name               = "standalone"
    val identity           = peerNode(name, 40400)
    val logicalTime        = new LogicalTime[F]
    implicit val log       = new LogStub[F](printEnabled = false)
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
            gossipService = new TestGossipService[F](name),
            validatorToNode = Map.empty // Shouldn't need it.
          )(
            concurrentF,
            blockStore,
            blockDagStorage,
            logicalTime,
            metricEff,
            casperState,
            log
          )
          _ <- node.initialize
        } yield node
    }
  }

  def networkF[F[_]](
      sks: IndexedSeq[PrivateKey],
      genesis: consensus.Block,
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
    val names = (0 to n - 1).map(i => s"node-$i")
    val peers = names.map(peerNode(_, 40400))

    var gossipServices = Map.empty[Node, TestGossipService[F]]

    val validatorToNode = peers
      .zip(sks)
      .map {
        case (node, sk) =>
          ByteString.copyFrom(Ed25519.tryToPublic(sk).get) -> node
      }
      .toMap

    val nodesF = peers
      .zip(sks)
      .toList
      .traverse {
        case (peer, sk) =>
          val logicalTime        = new LogicalTime[F]
          implicit val log       = new LogStub[F](peer.host, printEnabled = false)
          implicit val metricEff = new Metrics.MetricsNOP[F]
          implicit val nodeAsk   = makeNodeAsk(peer)(concurrentF)

          val gossipService = new TestGossipService[F](peer.host)
          gossipServices += peer -> gossipService

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
                  casperState,
                  log
                )
                _ <- gossipService.init(
                      node.casperEff,
                      blockStore,
                      relaying,
                      connectToGossip
                    )
              } yield node
          }
      }

    for {
      nodes <- nodesF
      _     <- nodes.traverse(_.initialize())
    } yield nodes.toVector
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
  class TestGossipService[F[_]: Concurrent: Timer: Par: Log](host: String)
      extends GossipService[F] {

    implicit val metrics = new Metrics.MetricsNOP[F]

    /** Exercise the full underlying stack. It's what we are testing here, via the MultiParentCasper tests. */
    var underlying: GossipServiceServer[F] = _
    var shutdown: F[Unit]                  = ().pure[F]

    /** Casper is created a bit later then the TestGossipService instance. */
    def init(
        casper: MultiParentCasperImpl[F],
        blockStore: BlockStore[F],
        relaying: Relaying[F],
        connectToGossip: GossipService.Connector[F]
    ): F[Unit] = {
      def isInDag(blockHash: ByteString): F[Boolean] =
        for {
          dag  <- casper.blockDag
          cont <- dag.contains(blockHash)
        } yield cont

      for {
        downloadManagerR <- DownloadManagerImpl[F](
                             maxParallelDownloads = 10,
                             connectToGossip = connectToGossip,
                             backend = new DownloadManagerImpl.Backend[F] {
                               override def hasBlock(blockHash: ByteString): F[Boolean] =
                                 isInDag(blockHash)

                               override def validateBlock(block: consensus.Block): F[Unit] =
                                 // Casper can only validate, store, but won't gossip because the Broadcaster we give it
                                 // will assume the DownloadManager will do that.
                                 // Doing this log here as it's evidently happened if we are here, and the tests expect it.
                                 Log[F].info(
                                   s"Requested missing block ${PrettyPrinter.buildString(block.blockHash)} Now validating."
                                 ) *>
                                   casper
                                     .addBlock(block) flatMap {
                                   case Valid =>
                                     Log[F].debug(s"Validated and stored block ${PrettyPrinter
                                       .buildString(block.blockHash)}")

                                   case AdmissibleEquivocation =>
                                     Log[F].debug(
                                       s"Detected AdmissibleEquivocation on block ${PrettyPrinter
                                         .buildString(block.blockHash)} Carry on down downloading children."
                                     )

                                   case other =>
                                     Log[F].debug(s"Received invalid block ${PrettyPrinter
                                       .buildString(block.blockHash)}: $other") *>
                                       Sync[F].raiseError(
                                         new RuntimeException(s"Non-valid status: $other")
                                       )
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
                           ).allocated

        (downloadManager, downloadManagerShutdown) = downloadManagerR

        synchronizer = new SynchronizerImpl[F](
          connectToGossip = connectToGossip,
          backend = new SynchronizerImpl.Backend[F] {
            override def tips: F[List[ByteString]] =
              for {
                dag       <- casper.blockDag
                tipHashes <- casper.estimator(dag)
              } yield tipHashes.toList

            override def justifications: F[List[ByteString]] =
              for {
                dag    <- casper.blockDag
                latest <- dag.latestMessageHashes
              } yield latest.values.toList

            override def validate(blockSummary: consensus.BlockSummary): F[Unit] =
              // TODO: Presently the Validation only works on full blocks.
              Log[F].debug(
                s"Trying to validate block summary ${PrettyPrinter.buildString(blockSummary.blockHash)}"
              )

            override def notInDag(blockHash: ByteString): F[Boolean] =
              isInDag(blockHash).map(!_)
          },
          maxPossibleDepth = Int.MaxValue,
          minBlockCountToCheckBranchingFactor = Int.MaxValue,
          maxBranchingFactor = 2.0,
          maxDepthAncestorsRequest = 1, // Just so we don't see the full DAG being synced all the time. We should have justifications for early stop.
          maxInitialBlockCount = Int.MaxValue,
          isInitialRef = Ref.unsafe[F, Boolean](false)
        )

        server <- GossipServiceServer[F](
                   backend = new GossipServiceServer.Backend[F] {
                     override def hasBlock(blockHash: ByteString): F[Boolean] =
                       isInDag(blockHash)

                     override def getBlockSummary(
                         blockHash: ByteString
                     ): F[Option[consensus.BlockSummary]] =
                       Log[F].debug(
                         s"Retrieving block summary ${PrettyPrinter.buildString(blockHash)} from storage."
                       ) *> blockStore.getBlockSummary(blockHash)

                     override def getBlock(blockHash: ByteString): F[Option[consensus.Block]] =
                       Log[F].debug(
                         s"Retrieving block ${PrettyPrinter.buildString(blockHash)} from storage."
                       ) *>
                         blockStore
                           .get(blockHash)
                           .map(_.map(mwt => mwt.getBlockMessage))
                   },
                   synchronizer = synchronizer,
                   downloadManager = downloadManager,
                   consensus = new GossipServiceServer.Consensus[F] {
                     override def onPending(dag: Vector[consensus.BlockSummary]) =
                       // The EquivocationDetector treats equivocations with children differently,
                       // so let Casper know about the DAG dependencies up front.
                       Log[F].debug(s"Feeding ${dag.size} pending blocks to Casper.") *>
                         dag.traverse { summary =>
                           val partialBlock = consensus
                             .Block()
                             .withBlockHash(summary.blockHash)
                             .withHeader(summary.getHeader)

                           casper.addMissingDependencies(partialBlock)
                         }.void

                     override def onDownloaded(blockHash: ByteString) =
                       // Calling `addBlock` during validation has already stored the block.
                       Log[F].debug(s"Download ready for ${PrettyPrinter.buildString(blockHash)}")

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
        shutdown = downloadManagerShutdown
      }
    }

    /** The tests assume we are using the TransportLayer and messages are fire-and-forget.
      * The main use of `receives and `clearMessages` is to pass over blocks and to simulate
      * dropping the block on the receiver end.
      * The RPC works differently when it comes to pulling dependencies, it doesn't ask one by one,
      * and because the tests assume to know how many exactly messages get passed and calls `receive`
      * so many times. But we can preserve most of the spirit of the test by returning `true` here
      * (they assume broadcast) and not execute the underlying call if `clearMessages` is called;
      * but we can let the other methods work out on their own without suspension. We just have to
      * make sure that if the test calls `receive` then all the async calls finish before we return
      * to do any assertions. */
    val notificationQueue = Ref.unsafe[F, Queue[F[Unit]]](Queue.empty)

    /** With the TransportLayer this would mean the target node receives the full block and adds it.
      * We have to allow `newBlocks` to return for the original block to be able to finish adding,
      * so maybe we can return `true`, and call the underlying service later. But then we have to
      * allow it to play out all async actions, such as downloading blocks, syncing the DAG, etc. */
    def receive(): F[Unit] = {
      // It can be tricky to line up the semantics of the notifications between the TransportLayer
      // and the GossipService. At least in one test the queue was longer and the node wasn't processing
      // the message the test was expecting it to, it was still reacting to an earlier one. To circumvent
      // these tests failures process all enqueued messages.
      def receiveOne =
        for {
          maybeNotification <- notificationQueue.modify { q =>
                                q dequeueOption match {
                                  case Some((notificaton, rest)) =>
                                    rest -> Some(notificaton)
                                  case None =>
                                    q -> None
                                }
                              }
          _ <- maybeNotification.fold(().pure[F])(identity)
        } yield maybeNotification.nonEmpty

      def loop(): F[Unit] =
        receiveOne flatMap {
          case false => ().pure[F]
          case true  => loop()
        }
      loop()
    }

    /** With the TransportLayer this would mean the target node won't process a message.
      * For us it could mean that it receives the `newBlocks` notification but after that
      * we don't let it play out the async operations, for example by returning errors for
      * all requests it started. */
    def clearMessages(): F[Unit] =
      for {
        q <- notificationQueue.get
        _ <- Log[F].debug(s"Forgetting ${q.size} notifications.")
        _ <- notificationQueue.set(Queue.empty)
      } yield ()

    override def newBlocks(request: NewBlocksRequest): F[NewBlocksResponse] =
      Log[F].info(
        s"Received notification about block ${PrettyPrinter.buildString(request.blockHashes.head)}"
      ) *>
        notificationQueue
          .update { q =>
            // Using `newBlocksSynchronous` so we finish as soon as it's done.
            q enqueue underlying
              .newBlocksSynchronous(request, skipRelaying = false)
              .void
          }
          .as(NewBlocksResponse(isNew = true))

    override def getBlockChunked(request: GetBlockChunkedRequest): Iterant[F, Chunk] =
      Iterant
        .liftF(
          Log[F].info(
            s"Received request for block ${PrettyPrinter.buildString(request.blockHash)} Response sent."
          )
        )
        .flatMap { _ =>
          underlying.getBlockChunked(request)
        }

    override def streamAncestorBlockSummaries(
        request: StreamAncestorBlockSummariesRequest
    ): Iterant[F, consensus.BlockSummary] =
      Iterant
        .liftF(Log[F].info(s"Recevied request for ancestors of ${request.targetBlockHashes
          .map(PrettyPrinter.buildString(_))}"))
        .flatMap { _ =>
          underlying.streamAncestorBlockSummaries(request)
        }

    // The following methods are not tested in these suites.

    override def addApproval(request: AddApprovalRequest): F[Unit] = ???
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
