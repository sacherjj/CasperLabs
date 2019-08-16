package io.casperlabs.casper.api

import cats.Monad
import cats.data.EitherT
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.DagRepresentation
import io.casperlabs.casper
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.helper.{HashSetCasperTestNode, TransportLayerCasperTestNodeFactory}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.protocol.DeployServiceResponse
import io.casperlabs.casper.util._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.Time
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

@silent("deprecated")
class CreateBlockAPITest extends FlatSpec with Matchers with TransportLayerCasperTestNodeFactory {
  import HashSetCasperTest._
  import HashSetCasperTestNode.Effect

  implicit val scheduler: Scheduler = Scheduler.fixedPool("create-block-api-test", 4)
  implicit val metrics              = new Metrics.MetricsNOP[Task]
  implicit val raiseValidateErr =
    casper.validation.raiseValidateErrorThroughApplicativeError[Effect]
  implicit val logEff = new LogStub[Effect]

  implicit val validation = HashSetCasperTestNode.makeValidation[Effect]

  private val (validatorKeys, validators)                      = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
  private val bonds                                            = createBonds(validators)
  private val BlockMsgWithTransform(Some(genesis), transforms) = createGenesis(bonds)

  "createBlock" should "not allow simultaneous calls" in {
    implicit val scheduler = Scheduler.fixedPool("three-threads", 3)
    implicit val time = new Time[Task] {
      private val timer                               = Task.timer
      def currentMillis: Task[Long]                   = timer.clock.realTime(MILLISECONDS)
      def nanoTime: Task[Long]                        = timer.clock.monotonic(NANOSECONDS)
      def sleep(duration: FiniteDuration): Task[Unit] = timer.sleep(duration)
    }
    val node   = standaloneEff(genesis, transforms, validatorKeys.head)
    val casper = new SleepingMultiParentCasperImpl[Effect](node.casperEff)
    val deploys = List(
      "@0!(0) | for(_ <- @0){ @1!(1) }",
      "for(_ <- @1){ @2!(2) }"
    ).zipWithIndex.map {
      case (deploy, nonce) =>
        ProtoUtil
          .basicDeploy(
            System.currentTimeMillis(),
            ByteString.copyFromUtf8(deploy),
            nonce.toLong + 1
          )
    }

    implicit val logEff       = new LogStub[Effect]
    implicit val blockStorage = node.blockStorage
    implicit val safetyOracle = node.safetyOracleEff

    def testProgram(blockApiLock: Semaphore[Effect])(
        implicit casperRef: MultiParentCasperRef[Effect]
    ): Effect[(DeployServiceResponse, DeployServiceResponse)] = EitherT.liftF(
      for {
        t1 <- (BlockAPI.deploy[Effect](deploys.head) *> BlockAPI
               .createBlock[Effect](blockApiLock)).value.start
        _ <- Time[Task].sleep(2.second)
        t2 <- (BlockAPI.deploy[Effect](deploys.last) *> BlockAPI
               .createBlock[Effect](blockApiLock)).value.start //should fail because other not done
        r1 <- t1.join
        r2 <- t2.join
      } yield (r1.right.get, r2.right.get)
    )

    try {
      val (response1, response2) = (for {
        casperRef    <- MultiParentCasperRef.of[Effect]
        _            <- casperRef.set(casper)
        blockApiLock <- Semaphore[Effect](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).value.unsafeRunSync.right.get

      response1.success shouldBe true
      response2.success shouldBe false
      response2.message shouldBe "Error: There is another propose in progress."
    } finally {
      node.tearDown()
    }
  }

  "deploy" should "reject replayed deploys" in {
    // Create the node with low fault tolerance threshold so it finalizes the blocks as soon as they are made.
    val node =
      standaloneEff(genesis, transforms, validatorKeys.head, faultToleranceThreshold = -2.0f)

    implicit val logEff       = new LogStub[Effect]
    implicit val blockStorage = node.blockStorage
    implicit val safetyOracle = node.safetyOracleEff

    def testProgram(blockApiLock: Semaphore[Effect])(
        implicit casperRef: MultiParentCasperRef[Effect]
    ): Effect[Unit] =
      for {
        d <- ProtoUtil.basicDeploy[Effect](1L)
        _ <- BlockAPI.deploy[Effect](d)
        _ <- BlockAPI.createBlock[Effect](blockApiLock)
        _ <- BlockAPI.deploy[Effect](d)
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Effect]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Effect](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).value.unsafeRunSync
    } catch {
      case ex: io.grpc.StatusRuntimeException =>
        ex.getMessage should include("already contains")
    } finally {
      node.tearDown()
    }
  }
}

private class SleepingMultiParentCasperImpl[F[_]: Monad: Time](underlying: MultiParentCasper[F])
    extends MultiParentCasper[F] {
  def addBlock(b: Block): F[BlockStatus]            = underlying.addBlock(b)
  def contains(b: Block): F[Boolean]                = underlying.contains(b)
  def deploy(d: Deploy): F[Either[Throwable, Unit]] = underlying.deploy(d)
  def estimator(dag: DagRepresentation[F]): F[IndexedSeq[BlockHash]] =
    underlying.estimator(dag)
  def dag: F[DagRepresentation[F]] = underlying.dag
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] =
    underlying.normalizedInitialFault(weights)
  def lastFinalizedBlock: F[Block] = underlying.lastFinalizedBlock
  def fetchDependencies: F[Unit]   = underlying.fetchDependencies
  def faultToleranceThreshold      = underlying.faultToleranceThreshold

  override def createBlock: F[CreateBlockStatus] =
    for {
      result <- underlying.createBlock
      _      <- implicitly[Time[F]].sleep(5.seconds)
    } yield result

}
