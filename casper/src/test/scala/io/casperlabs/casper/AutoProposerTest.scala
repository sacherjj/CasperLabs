package io.casperlabs.casper

import cats.effect._
import cats.effect.concurrent._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.deploybuffer.{DeployBuffer, MockDeployBuffer}
import io.casperlabs.comm.gossiping.ArbitraryConsensus
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.{Log, Time}
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

  it should "propose if more than max-count deploys are accumulated within max-interval" in TestFixture(
    maxInterval = 5.seconds,
    maxCount = 2
  ) { _ => implicit casperRef => implicit deployBuffer =>
    for {
      casper <- MockMultiParentCasper[Task]
      _      <- casper.deploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 0
      _      <- casper.deploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 1
    } yield ()
  }

  it should "propose if less than max-count deploys are accumulated after max-interval" in TestFixture(
    maxInterval = 250.millis,
    maxCount = 10
  ) { _ => implicit casperRef => implicit deployBuffer =>
    for {
      casper <- MockMultiParentCasper[Task]
      _      <- casper.deploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 0
      _      <- Timer[Task].sleep(1.second)
      _      = casper.proposalCount shouldBe 1
    } yield ()
  }

  it should "not propose if none of the thresholds are reached" in TestFixture(
    maxInterval = 1.second,
    maxCount = 2
  ) { _ => implicit casperRef => implicit deployBuffer =>
    for {
      casper <- MockMultiParentCasper[Task]
      _      <- casper.deploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 0
    } yield ()
  }

  it should "not propose if there are no new deploys" in TestFixture(
    maxInterval = 1.second,
    maxCount = 1
  ) { _ => implicit casperRef => implicit deployBuffer =>
    for {
      casper <- MockMultiParentCasper[Task]
      d1     = sampleDeployData
      _      <- casper.deploy(d1)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 1
      _      <- casper.deploy(d1)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 1
      d2     = sampleDeployData
      _      <- casper.deploy(d2)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 2
    } yield ()
  }

  it should "not stop if the proposal fails" in TestFixture(
    maxInterval = 1.second,
    maxCount = 1
  ) { _ => implicit casperRef => implicit deployBuffer =>
    val defectiveCasper = new MockMultiParentCasper[Task]() {
      override def createBlock: Task[CreateBlockStatus] =
        throw new RuntimeException("Oh no!")
    }
    for {
      _      <- MultiParentCasperRef[Task].set(defectiveCasper)
      _      <- defectiveCasper.deploy(sampleDeployData)
      _      <- waitForCheck
      casper <- MockMultiParentCasper[Task]
      _      <- casper.deploy(sampleDeployData)
      _      <- waitForCheck
      _      = casper.proposalCount shouldBe 1
    } yield ()
  }

}

object AutoProposerTest {
  import Scheduler.Implicits.global
  import io.casperlabs.blockstorage.DagRepresentation
  implicit val log     = new Log.NOPLog[Task]()
  implicit val metrics = new Metrics.MetricsNOP[Task]()

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
        maxInterval: FiniteDuration,
        maxCount: Int
    )(
        f: AutoProposer[Task] => MultiParentCasperRef[Task] => DeployBuffer[Task] => Task[Unit]
    ): Unit = {
      val resources = for {
        implicit0(deployBuffer: DeployBuffer[Task]) <- Resource.liftF(
                                                        MockDeployBuffer.create[Task]()
                                                      )
        implicit0(emptyRef: MultiParentCasperRef[Task]) = MultiParentCasperRef.unsafe[Task]()
        blockApiLock                                    <- Resource.liftF(Semaphore[Task](1))
        proposer <- AutoProposer[Task](
                     checkInterval = checkInterval,
                     maxInterval = maxInterval,
                     maxCount = maxCount,
                     blockApiLock = blockApiLock
                   )
      } yield (proposer, emptyRef, deployBuffer)

      val test = resources.use {
        case (proposer, casperRef, deployBuffer) => f(proposer)(casperRef)(deployBuffer)
      }

      test.runSyncUnsafe(5.seconds)
    }
  }

  object MockMultiParentCasper {
    def apply[F[_]: Sync: MultiParentCasperRef: DeployBuffer] =
      for {
        c <- Sync[F].delay(new MockMultiParentCasper[F]())
        _ <- MultiParentCasperRef[F].set(c)
      } yield c
  }

  class MockMultiParentCasper[F[_]: Sync: DeployBuffer] extends MultiParentCasper[F] {

    @volatile var proposalCount = 0

    override def deploy(deployData: Deploy): F[Either[Throwable, Unit]] =
      DeployBuffer[F].addAsPending(List(deployData)) >> Sync[F].delay {
        Right(())
      }

    override def createBlock: F[CreateBlockStatus] =
      for {
        pending <- DeployBuffer[F].readPending
        _       <- DeployBuffer[F].markAsProcessed(pending).whenA(pending.nonEmpty)
        _       <- Sync[F].delay(proposalCount += 1)
      } yield {
        // Doesn't matter what we return in this test.
        if (pending.nonEmpty) Created(Block()) else NoNewDeploys
      }

    override def addBlock(block: Block): F[BlockStatus]                           = ???
    override def contains(block: Block): F[Boolean]                               = ???
    override def estimator(dag: DagRepresentation[F]): F[IndexedSeq[ByteString]]  = ???
    override def dag: F[DagRepresentation[F]]                                     = ???
    override def fetchDependencies: F[Unit]                                       = ???
    override def normalizedInitialFault(weights: Map[ByteString, Long]): F[Float] = ???
    override def lastFinalizedBlock: F[Block]                                     = ???
    override def faultToleranceThreshold                                          = 0f
  }
}
