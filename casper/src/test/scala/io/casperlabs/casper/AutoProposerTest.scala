package io.casperlabs.casper

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import cats.data.NonEmptyList
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.Validator
import io.casperlabs.casper.MultiParentCasperImpl.Broadcaster
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.consensus._
import io.casperlabs.mempool.DeployBuffer
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.{ArbitraryConsensus, Weight}
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.storage.deploy.{
  DeployStorage,
  DeployStorageReader,
  DeployStorageWriter,
  MockDeployStorage
}
import monix.eval.Task
import monix.execution.Scheduler
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest._

import scala.concurrent.duration._

class AutoProposerTest extends FlatSpec with Matchers with ArbitraryConsensus {

  import AutoProposerTest._

  implicit val cc = ConsensusConfig()

  def sampleDeployData = sample(arbitrary[consensus.Deploy])

  behavior of "AutoProposer"

  val waitForCheck = Timer[Task].sleep(10 * DefaultCheckInterval)

  it should "propose if more than acc-count deploys are accumulated within acc-interval" in TestFixture(
    accInterval = 5.seconds,
    accCount = 2
  ) { _ => implicit casperRef => implicit deployStorage => implicit deployBuffer =>
    for {
      casper <- MockMultiParentCasper[Task]
      _      <- deployBuffer.addDeploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 0
      _      <- deployBuffer.addDeploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 1
    } yield ()
  }

  it should "propose if less than acc-count deploys are accumulated after acc-interval" in TestFixture(
    accInterval = 250.millis,
    accCount = 10
  ) { _ => implicit casperRef => implicit deployStorage => implicit deployBuffer =>
    for {
      casper <- MockMultiParentCasper[Task]
      _      <- deployBuffer.addDeploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 0
      _      <- Timer[Task].sleep(1.second)
      _      = casper.proposalCount shouldBe 1
    } yield ()
  }

  it should "not propose if none of the thresholds are reached" in TestFixture(
    accInterval = 1.second,
    accCount = 2
  ) { _ => implicit casperRef => implicit deployStorage => implicit deployBuffer =>
    for {
      casper <- MockMultiParentCasper[Task]
      _      <- deployBuffer.addDeploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 0
    } yield ()
  }

  it should "propose a ballot if none of the thresholds are reached for a long time" in TestFixture(
    ballotInterval = 1.seconds,
    accInterval = 10.seconds,
    accCount = 10
  ) { _ => implicit casperRef => implicit deployStorage => _ =>
    for {
      casper <- MockMultiParentCasper[Task]
      _      <- Timer[Task].sleep(1500.millis)
      _      = casper.ballotCount shouldBe 1
    } yield ()
  }

  it should "not propose a ballot if a block has been proposed" in TestFixture(
    ballotInterval = 1.seconds,
    accInterval = 500.millis,
    accCount = 10
  ) { _ => implicit casperRef => implicit deployStorage => implicit deployBuffer =>
    for {
      casper <- MockMultiParentCasper[Task]
      _      <- deployBuffer.addDeploy(sampleDeployData)
      _      <- Timer[Task].sleep(1500.millis)
      _      = casper.proposalCount shouldBe 1
      _      = casper.ballotCount shouldBe 0
    } yield ()
  }

  it should "not propose if there are no new deploys" in TestFixture(
    accInterval = 1.second,
    accCount = 1
  ) { _ => implicit casperRef => implicit deployStorage => implicit deployBuffer =>
    for {
      casper <- MockMultiParentCasper[Task]
      d1     = sampleDeployData
      _      <- deployBuffer.addDeploy(d1)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 1
      _      <- deployBuffer.addDeploy(d1)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 1
      d2     = sampleDeployData
      _      <- deployBuffer.addDeploy(d2)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 2
    } yield ()
  }

  it should "not stop if the proposal fails" in TestFixture(
    accInterval = 1.second,
    accCount = 1
  ) { _ => implicit casperRef => implicit deployStorage => implicit deployBuffer =>
    val defectiveCasper = new MockMultiParentCasper[Task]() {
      override def createMessage(canCreateBallot: Boolean): Task[CreateBlockStatus] =
        throw new RuntimeException("Oh no!")
    }
    for {
      _      <- MultiParentCasperRef[Task].set(defectiveCasper)
      _      <- deployBuffer.addDeploy(sampleDeployData)
      _      <- waitForCheck
      casper <- MockMultiParentCasper[Task]
      _      <- deployBuffer.addDeploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 1
    } yield ()
  }

}

object AutoProposerTest {
  import Scheduler.Implicits.global
  import io.casperlabs.storage.dag.DagRepresentation
  implicit val log         = Log.NOPLog[Task]
  implicit val metrics     = new Metrics.MetricsNOP[Task]()
  implicit val broadcaster = Broadcaster.noop[Task]

  implicit val time = new Time[Task] {
    val timer                                       = implicitly[Timer[Task]]
    def currentMillis: Task[Long]                   = timer.clock.realTime(MILLISECONDS)
    def nanoTime: Task[Long]                        = timer.clock.monotonic(NANOSECONDS)
    def sleep(duration: FiniteDuration): Task[Unit] = timer.sleep(duration)
  }

  val DefaultCheckInterval = 25.millis

  object TestFixture {
    def apply(
        checkInterval: FiniteDuration = DefaultCheckInterval,
        ballotInterval: FiniteDuration = 1.minute, // Long enough that it won't affect tests unless set.
        accInterval: FiniteDuration,
        accCount: Int
    )(
        f: AutoProposer[Task] => MultiParentCasperRef[Task] => DeployStorage[Task] => DeployBuffer[
          Task
        ] => Task[Unit]
    ): Unit = {
      val resources = for {
        implicit0(deployStorage: DeployStorage[Task]) <- Resource.liftF(
                                                          MockDeployStorage.create[Task]()
                                                        )
        implicit0(emptyRef: MultiParentCasperRef[Task]) = MultiParentCasperRef.unsafe[Task]()
        blockApiLock                                    <- Resource.liftF(Semaphore[Task](1))
        deployBuffer                                    = DeployBuffer.create[Task]("casperlabs", 1.minute)
        proposer <- AutoProposer[Task](
                     checkInterval = checkInterval,
                     ballotInterval = ballotInterval,
                     accInterval = accInterval,
                     accCount = accCount,
                     blockApiLock = blockApiLock
                   )
      } yield (proposer, emptyRef, deployStorage, deployBuffer)

      val test = resources.use {
        case (proposer, casperRef, deployStorage, deployBuffer) =>
          f(proposer)(casperRef)(deployStorage)(deployBuffer)
      }

      test.runSyncUnsafe(5.seconds)
    }
  }

  object MockMultiParentCasper {
    def apply[F[_]: Sync: MultiParentCasperRef: DeployStorage] =
      for {
        c <- Sync[F].delay(new MockMultiParentCasper[F]())
        _ <- MultiParentCasperRef[F].set(c)
      } yield c
  }

  class MockMultiParentCasper[F[_]: Sync: DeployStorage] extends MultiParentCasper[F] {

    @volatile var proposalCount = 0
    @volatile var ballotCount   = 0

    override def createMessage(canCreateBallot: Boolean): F[CreateBlockStatus] =
      for {
        pending <- DeployStorageReader[F].readPending
        _       <- DeployStorageWriter[F].markAsProcessed(pending).whenA(pending.nonEmpty)
        _       <- Sync[F].delay(proposalCount += 1)
        // Doesn't matter what we return in this test.
        result <- if (pending.nonEmpty)
                   Created(Block()).pure[F]
                 else if (canCreateBallot)
                   Sync[F].delay(ballotCount += 1).as(Created(Block()))
                 else NoNewDeploys.pure[F]
      } yield result

    override def addBlock(block: Block): F[BlockStatus] = (Valid: BlockStatus).pure[F]
    override def contains(block: Block): F[Boolean]     = ???
    override def estimator(
        dag: DagRepresentation[F],
        lfbHash: ByteString,
        lm: Map[Validator, Set[ByteString]],
        equivocators: Set[Validator]
    ): F[NonEmptyList[ByteString]]            = ???
    override def dag: F[DagRepresentation[F]] = ???
    override def lastFinalizedBlock: F[Block] = ???
  }
}
