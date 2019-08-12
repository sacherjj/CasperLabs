package io.casperlabs.casper.helper

import java.nio.file.Path

import cats.Monad
import cats.effect.concurrent.{Ref, Semaphore}
import cats.effect.{Concurrent, Timer}
import cats.implicits._
import cats.temp.par.Par
import io.casperlabs.blockstorage._
import io.casperlabs.casper
import io.casperlabs.casper._
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.deploybuffer.{DeployBuffer, MockDeployBuffer}
import io.casperlabs.casper.protocol.{ApprovedBlock, ApprovedBlockCandidate}
import io.casperlabs.casper.util.comm.CasperPacketHandler.{
  ApprovedBlockReceivedHandler,
  CasperPacketHandlerImpl,
  CasperPacketHandlerInternal
}
import io.casperlabs.casper.util.comm.TransportLayerTestImpl
import io.casperlabs.casper.validation.ValidationImpl
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CommError.ErrorHandler
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.comm.rp.Connect
import io.casperlabs.comm.rp.Connect._
import io.casperlabs.comm.rp.HandleMessages.handle
import io.casperlabs.crypto.Keys.PrivateKey
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.p2p.effects.PacketHandler
import io.casperlabs.shared.Log.NOPLog
import io.casperlabs.shared.{Cell, Log, Time}

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}

class TransportLayerCasperTestNode[F[_]](
    local: Node,
    tle: TransportLayerTestImpl[F],
    genesis: Block,
    transforms: Seq[TransformEntry],
    sk: PrivateKey,
    dagStorageDir: Path,
    blockStorageDir: Path,
    blockProcessingLock: Semaphore[F],
    faultToleranceThreshold: Float = 0f,
    chainId: String = "casperlabs",
    validateNonces: Boolean = true,
    maybeMakeEE: Option[HashSetCasperTestNode.MakeExecutionEngineService[F]] = None
)(
    implicit
    concurrentF: Concurrent[F],
    blockStorage: BlockStorage[F],
    dagStorage: DagStorage[F],
    val errorHandlerEff: ErrorHandler[F],
    val timeEff: Time[F],
    metricEff: Metrics[F],
    casperState: Cell[F, CasperState]
) extends HashSetCasperTestNode[F](
      local,
      sk,
      genesis,
      dagStorageDir,
      blockStorageDir,
      validateNonces,
      maybeMakeEE
    )(concurrentF, blockStorage, dagStorage, metricEff, casperState) {

  implicit val logEff: LogStub[F] = new LogStub[F](local.host, printEnabled = false)
  implicit val deployBufferEff: DeployBuffer[F] =
    MockDeployBuffer.unsafeCreate[F]()(Concurrent[F], new NOPLog[F])
  implicit val connectionsCell   = Cell.unsafe[F, Connections](Connect.Connections.empty)
  implicit val transportLayerEff = tle
  implicit val rpConfAsk         = createRPConfAsk[F](local)

  implicit val safetyOracleEff: FinalityDetector[F] = new FinalityDetectorInstancesImpl[F]

  val defaultTimeout = FiniteDuration(1000, MILLISECONDS)

  val approvedBlock = ApprovedBlock(
    candidate = Some(ApprovedBlockCandidate(block = Some(LegacyConversions.fromBlock(genesis))))
  )

  implicit val labF =
    LastApprovedBlock.unsafe[F](Some(ApprovedBlockWithTransforms(approvedBlock, transforms)))

  implicit val raiseInvalidBlock = casper.validation.raiseValidateErrorThroughApplicativeError[F]
  implicit val validation        = HashSetCasperTestNode.makeValidation[F]

  implicit val casperEff: MultiParentCasperImpl[F] =
    new MultiParentCasperImpl[F](
      new MultiParentCasperImpl.StatelessExecutor(chainId),
      MultiParentCasperImpl.Broadcaster.fromTransportLayer(),
      Some(validatorId),
      genesis,
      chainId,
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

  def receive(): F[Unit] = tle.receive(p => handle[F](p, defaultTimeout), kp(().pure[F]))

  def clearMessages(): F[Unit] =
    transportLayerEff.clear(local)
}

trait TransportLayerCasperTestNodeFactory extends HashSetCasperTestNodeFactory {

  type TestNode[F[_]] = TransportLayerCasperTestNode[F]

  import HashSetCasperTestNode.peerNode

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
  ): F[TransportLayerCasperTestNode[F]] = {
    val name     = "standalone"
    val identity = peerNode(name, 40400)
    val tle =
      new TransportLayerTestImpl[F](identity, Map.empty[Node, Ref[F, mutable.Queue[Protocol]]])
    val logicalTime        = new LogicalTime[F]
    implicit val log       = new Log.NOPLog[F]()
    implicit val metricEff = new Metrics.MetricsNOP[F]

    initStorage(genesis) flatMap {
      case (dagStorageDir, blockStorageDir, dagStorage, blockStorage) =>
        for {
          blockProcessingLock <- Semaphore[F](1)
          casperState         <- Cell.mvarCell[F, CasperState](CasperState())
          node = new TransportLayerCasperTestNode[F](
            identity,
            tle,
            genesis,
            transforms,
            sk,
            dagStorageDir,
            blockStorageDir,
            blockProcessingLock,
            faultToleranceThreshold
          )(
            concurrentF,
            blockStorage,
            dagStorage,
            errorHandler,
            logicalTime,
            metricEff,
            casperState
          )
          _ <- node.initialize
        } yield node
    }
  }

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
  ): F[IndexedSeq[TransportLayerCasperTestNode[F]]] = {
    val n     = sks.length
    val names = (0 to n - 1).map(i => s"node-$i")
    val peers = names.map(peerNode(_, 40400))
    val msgQueues = peers
      .map(_ -> new mutable.Queue[Protocol]())
      .toMap
      .mapValues(Ref.unsafe[F, mutable.Queue[Protocol]])

    val nodesF =
      peers
        .zip(sks)
        .toList
        .traverse {
          case (peer, sk) =>
            val tle                = new TransportLayerTestImpl[F](peer, msgQueues)
            val logicalTime        = new LogicalTime[F]
            implicit val log       = new Log.NOPLog[F]()
            implicit val metricEff = new Metrics.MetricsNOP[F]

            initStorage(genesis) flatMap {
              case (dagStorageDir, blockStorageDir, dagStorage, blockStorage) =>
                for {
                  semaphore <- Semaphore[F](1)
                  casperState <- Cell.mvarCell[F, CasperState](
                                  CasperState()
                                )
                  node = new TransportLayerCasperTestNode[F](
                    peer,
                    tle,
                    genesis,
                    transforms,
                    sk,
                    dagStorageDir,
                    blockStorageDir,
                    semaphore,
                    faultToleranceThreshold,
                    validateNonces = validateNonces,
                    maybeMakeEE = maybeMakeEE
                  )(
                    concurrentF,
                    blockStorage,
                    dagStorage,
                    errorHandler,
                    logicalTime,
                    metricEff,
                    casperState
                  )
                } yield node
            }
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
