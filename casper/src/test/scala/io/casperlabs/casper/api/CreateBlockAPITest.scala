package io.casperlabs.casper.api

import cats.Monad
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper
import io.casperlabs.casper._
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.info.{BlockInfo, DeployInfo}
import io.casperlabs.casper.consensus.info.BlockInfo.Status.Stats
import io.casperlabs.casper.consensus.info.DeployInfo.ProcessingResult
import io.casperlabs.casper.helper.{GossipServiceCasperTestNodeFactory, HashSetCasperTestNode}
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.util._
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.Weight
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.Time
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.dag.DagRepresentation
import io.casperlabs.storage.deploy.DeployStorageReader
import io.casperlabs.casper.scalatestcontrib._
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{FlatSpec, Inspectors, Matchers}

import scala.concurrent.duration._

@silent("deprecated")
class CreateBlockAPITest
    extends FlatSpec
    with Matchers
    with Inspectors
    with GossipServiceCasperTestNodeFactory {
  import HashSetCasperTest._

  implicit val scheduler: Scheduler = Scheduler.fixedPool("create-block-api-test", 4)
  implicit val metrics              = new Metrics.MetricsNOP[Task]
  implicit val raiseValidateErr =
    casper.validation.raiseValidateErrorThroughApplicativeError[Task]
  implicit val logEff = new LogStub[Task]

  implicit val validation: Validation[Task] = HashSetCasperTestNode.makeValidation[Task]

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
    val casper = new SleepingMultiParentCasperImpl[Task](node.casperEff)
    val deploys = List(
      "@0!(0) | for(_ <- @0){ @1!(1) }",
      "for(_ <- @1){ @2!(2) }"
    ).map { deploy =>
      ProtoUtil
        .basicDeploy(
          0,
          ByteString.copyFromUtf8(System.currentTimeMillis().toString),
          ByteString.copyFromUtf8(deploy)
        )
    }

    implicit val logEff       = new LogStub[Task]
    implicit val blockStorage = node.blockStorage
    implicit val safetyOracle = node.safetyOracleEff

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[(Either[Throwable, ByteString], Either[Throwable, ByteString])] =
      for {
        t1 <- (BlockAPI.deploy[Task](deploys.head) *> BlockAPI
               .propose[Task](blockApiLock)).start
        _ <- Time[Task].sleep(2.second)
        t2 <- (BlockAPI.deploy[Task](deploys.last) *> BlockAPI
               .propose[Task](blockApiLock)).start //should fail because other not done
        r1 <- t1.join.attempt
        r2 <- t2.join.attempt
      } yield (r1, r2)

    try {
      val (response1, response2) = (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(casper)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync

      response1.isRight shouldBe true
      response2.isRight shouldBe false
      response2.left.get.getMessage should include("ABORTED")
    } finally {
      node.tearDown()
    }
  }

  "propose" should "handle concurrent calls and not create equivocations" in {
    val threadCount        = 3
    implicit val scheduler = Scheduler.fixedPool("propose-threads", threadCount)
    // Similarly to integration tests, start multiple concurrent processes that
    // try to deploy and immediately propose. The proposals should be rejected
    // when another one is already running, but eventually all deploys should be
    // taken and put into blocks. The blocks we created should form a simple
    // chain, there shouldn't be any forks in it, which we should see from the
    // fact that each rank is occupied by a single block.
    val node = standaloneEff(genesis, transforms, validatorKeys.head)

    implicit val bs = node.blockStorage
    implicit val fd = node.safetyOracleEff

    def deployAndPropose(
        blockApiLock: Semaphore[Task]
    )(implicit casperRef: MultiParentCasperRef[Task]) =
      for {
        d <- ProtoUtil.basicDeploy[Task]()
        _ <- BlockAPI.deploy[Task](d)
        _ <- BlockAPI.propose[Task](blockApiLock)
      } yield ()

    val test: Task[Unit] = for {
      casperRef    <- MultiParentCasperRef.of[Task]
      _            <- casperRef.set(node.casperEff)
      blockApiLock <- Semaphore[Task](1)
      // Create blocks in waves.
      results <- Task.sequence {
                  List.fill(5) {
                    Task.gatherUnordered {
                      List.fill(threadCount) {
                        deployAndPropose(blockApiLock)(casperRef).attempt
                      }
                    }
                  }
                }
      // See how many blocks were created.
      dag    <- node.dagStorage.getRepresentation
      blocks <- dag.topoSortTail(Int.MaxValue).compile.toList.map(_.flatten)
    } yield {
      // Genesis plus as many as we created (most likely the number of waves we have)
      val successCount = results.flatten.count(_.isRight)
      blocks should have size (1L + successCount.toLong)

      // Check that we have no forks.
      val blocksPerRank = blocks.groupBy(_.getSummary.getHeader.rank)
      blocksPerRank should have size (blocks.size.toLong)
      forAll(blocksPerRank.values)(_ should have size 1)

      // Check that adding the block hasn't failed for an unexpected reason.
      results.flatten.foreach {
        case Right(_) =>
        case Left(ex) =>
          // Either the lock couldn't be acquired or all deploys were stolen.
          ex.getMessage should (include("ABORTED") or include("OUT_OF_RANGE"))
      }
    }

    try {
      test.unsafeRunSync
    } finally {
      node.tearDown()
    }
  }

  "deploy" should "reject replayed deploys" in {
    // Create the node with low fault tolerance threshold so it finalizes the blocks as soon as they are made.
    val node =
      standaloneEff(genesis, transforms, validatorKeys.head, faultToleranceThreshold = -2.0f)

    implicit val logEff       = new LogStub[Task]
    implicit val blockStorage = node.blockStorage
    implicit val safetyOracle = node.safetyOracleEff

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[Unit] =
      for {
        d <- ProtoUtil.basicDeploy[Task]()
        _ <- BlockAPI.deploy[Task](d)
        _ <- BlockAPI.propose[Task](blockApiLock)
        _ <- BlockAPI.deploy[Task](d)
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync
    } catch {
      case ex: io.grpc.StatusRuntimeException =>
        ex.getMessage should include("already contains")
    } finally {
      node.tearDown()
    }
  }

  "getDeployInfo" should "return DeployInfo for specified deployHash" in {
    // Create the node with low fault tolerance threshold so it finalizes the blocks as soon as they are made.
    val node =
      standaloneEff(genesis, transforms, validatorKeys.head, faultToleranceThreshold = -2.0f)
    val v1 = generateValidator("V1")

    implicit val logEff        = new LogStub[Task]
    implicit val blockStorage  = node.blockStorage
    implicit val deployStorage = node.deployStorage
    implicit val safetyOracle  = node.safetyOracleEff

    val deploy = ProtoUtil.basicDeploy(
      0,
      ByteString.EMPTY,
      v1
    )

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[Unit] =
      for {
        _         <- BlockAPI.deploy[Task](deploy)
        blockHash <- BlockAPI.propose[Task](blockApiLock)
        deployInfo <- BlockAPI.getDeployInfo[Task](
                       Base16.encode(deploy.deployHash.toByteArray),
                       DeployInfo.View.FULL
                     )
        block <- blockStorage
                  .get(blockHash)
                  .map(_.get.blockMessage.get)
        summary         = BlockSummary(block.blockHash, block.header, block.signature)
        processedDeploy = block.getBody.deploys.head
        expectBlockInfo = BlockInfo()
          .withSummary(summary)
          .withStatus(
            BlockInfo
              .Status()
              .withStats(
                Stats(
                  block.serializedSize,
                  block.getBody.deploys.count(_.isError),
                  block.getBody.deploys.map(_.cost).sum
                )
              )
          )
        expectProcessingResult = ProcessingResult(
          blockInfo = expectBlockInfo.some,
          cost = processedDeploy.cost,
          isError = processedDeploy.isError,
          errorMessage = processedDeploy.errorMessage
        )
        _ = deployInfo.processingResults.head shouldBe expectProcessingResult
        _ = deployInfo.deploy shouldBe deploy.some
        result <- BlockAPI
                   .getDeployInfo[Task](
                     Base16.encode(ByteString.copyFromUtf8("NOT_EXIST").toByteArray),
                     DeployInfo.View.BASIC
                   )
                   .attempt
        _ = result.left.get.getMessage should include("NOT_FOUND: Deploy")
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync
    } finally {
      node.tearDown()
    }
  }

  "getBlockDeploys" should "return return all ProcessedDeploys in a block" in {
    val node =
      standaloneEff(genesis, transforms, validatorKeys.head, faultToleranceThreshold = -2.0f)
    val v1 = generateValidator("V1")

    implicit val logEff        = new LogStub[Task]
    implicit val blockStorage  = node.blockStorage
    implicit val deployStorage = node.deployStorage
    implicit val safetyOracle  = node.safetyOracleEff

    def mkDeploy(code: String) = ProtoUtil.basicDeploy(0, ByteString.copyFromUtf8(code), v1)

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[Unit] =
      for {
        _         <- BlockAPI.deploy[Task](mkDeploy("a"))
        _         <- BlockAPI.deploy[Task](mkDeploy("b"))
        blockHash <- BlockAPI.propose[Task](blockApiLock)
        deploys <- BlockAPI.getBlockDeploys[Task](
                    Base16.encode(blockHash.toByteArray),
                    DeployInfo.View.FULL
                  )
        block <- blockStorage
                  .get(blockHash)
                  .map(_.get.blockMessage.get)
        _ = deploys shouldBe block.getBody.deploys
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync
    } finally {
      node.tearDown()
    }
  }

  "getDeployInfos" should "return a list of DeployInfo for the list of deploys" in {
    // Create the node with low fault tolerance threshold so it finalizes the blocks as soon as they are made.
    val node =
      standaloneEff(genesis, transforms, validatorKeys.head, faultToleranceThreshold = -2.0f)
    val v1 = generateValidator("V1")

    implicit val logEff        = new LogStub[Task]
    implicit val blockStorage  = node.blockStorage
    implicit val deployStorage = node.deployStorage
    implicit val safetyOracle  = node.safetyOracleEff

    val deploys = (1L to 10L)
      .map(
        t =>
          ProtoUtil.basicDeploy(
            t,
            ByteString.EMPTY,
            v1
          )
      )
      .toList

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[Unit] =
      for {
        _ <- deploys.toList.traverse(d => {
              BlockAPI.deploy[Task](d) *> BlockAPI.propose[Task](blockApiLock)
            })
        deployInfos <- DeployStorageReader[Task].getDeployInfos(deploys)
        _ <- deployInfos.traverse(
              deployInfo =>
                DeployStorageReader[Task]
                  .getDeployInfo(deployInfo.getDeploy.deployHash) shouldBeF deployInfo.some
            )
        result <- DeployStorageReader[Task]
                   .getDeployInfos(
                     List.empty[Deploy]
                   )
        _ = result shouldBe List.empty[DeployInfo]
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync
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
  def estimator(
      dag: DagRepresentation[F],
      latestMessagesHashes: Map[Validator, Set[ByteString]],
      equivocators: Set[Validator]
  ): F[List[BlockHash]] =
    underlying.estimator(dag, latestMessagesHashes, equivocators)
  def dag: F[DagRepresentation[F]] = underlying.dag
  def lastFinalizedBlock: F[Block] = underlying.lastFinalizedBlock
  def faultToleranceThreshold      = underlying.faultToleranceThreshold

  override def createBlock: F[CreateBlockStatus] =
    for {
      result <- underlying.createBlock
      _      <- implicitly[Time[F]].sleep(5.seconds)
    } yield result

}
