package io.casperlabs.casper.util.rholang

import java.nio.file.{Files, Paths}

import cats.mtl.implicits._
import cats.{Applicative, Id, Monad}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{
  BlockDagRepresentation,
  BlockDagStorage,
  BlockStore,
  IndexedBlockDagStorage
}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.rholang.InterpreterUtil._
import io.casperlabs.casper.util.rholang.Resources.mkRuntimeManager
import io.casperlabs.casper.util.rholang.RuntimeManager.StateHash
import io.casperlabs.catscontrib.ToAbstractContext
import io.casperlabs.ipc.ExecutionEffect
import io.casperlabs.models.InternalProcessedDeploy
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Time
import io.casperlabs.smartcontracts.GrpcExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

class InterpreterUtilTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockDagStorageFixture {
  val storageSize      = 1024L * 1024
  val storageDirectory = Files.createTempDirectory("casper-interp-util-test")

  implicit val logEff = new LogStub[Task]
  implicit val absId  = ToAbstractContext.idToAbstractContext

  private val runtimeDir = Files.createTempDirectory(s"interpreter-util-test")

  private val socket = Paths.get(runtimeDir.toString, ".casper-node.sock")
  implicit val executionEngineService: GrpcExecutionEngineService[Task] =
    new GrpcExecutionEngineService[Task](
      socket,
      4 * 1024 * 1024
    )

  "computeBlockCheckpoint" should "compute the final post-state of a chain properly" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val genesisDeploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val genesisDeploysCost =
        genesisDeploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b1Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b1DeploysCost = b1Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b2Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b2DeploysCost = b2Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b3Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b3DeploysCost = b3Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      /*
       * DAG Looks like this:
       *
       *          b3
       *           |
       *          b2
       *           |
       *          b1
       *           |
       *         genesis
       */

      mkRuntimeManager("interpreter-util-test").use { runtimeManager =>
        for {
          genesis                                     <- createBlock[Task](Seq.empty, deploys = genesisDeploysCost)
          b1                                          <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysCost)
          b2                                          <- createBlock[Task](Seq(b1.blockHash), deploys = b2DeploysCost)
          b3                                          <- createBlock[Task](Seq(b2.blockHash), deploys = b3DeploysCost)
          dag1                                        <- blockDagStorage.getRepresentation
          blockCheckpoint                             <- computeBlockCheckpoint(genesis, genesis, dag1, runtimeManager)
          (postGenStateHash, postGenProcessedDeploys) = blockCheckpoint
          _                                           <- injectPostStateHash[Task](0, genesis, postGenStateHash, postGenProcessedDeploys)
          dag2                                        <- blockDagStorage.getRepresentation
          blockCheckpointB1                           <- computeBlockCheckpoint(b1, genesis, dag2, runtimeManager)
          (postB1StateHash, postB1ProcessedDeploys)   = blockCheckpointB1
          _                                           <- injectPostStateHash[Task](1, b1, postB1StateHash, postB1ProcessedDeploys)
          dag3                                        <- blockDagStorage.getRepresentation
          blockCheckpointB2 <- computeBlockCheckpoint(
                                b2,
                                genesis,
                                dag3,
                                runtimeManager
                              )
          (postB2StateHash, postB2ProcessedDeploys) = blockCheckpointB2
          _                                         <- injectPostStateHash[Task](2, b2, postB2StateHash, postB2ProcessedDeploys)

          dag4 <- blockDagStorage.getRepresentation
          blockCheckpointB4 <- computeBlockCheckpoint(
                                b3,
                                genesis,
                                dag4,
                                runtimeManager
                              )
          (postb3StateHash, _) = blockCheckpointB4
//          b3PostState          = runtimeManager.storageRepr(postb3StateHash).get
//
//          _      = b3PostState.contains("@{1}!(1)") should be(true)
//          _      = b3PostState.contains("@{1}!(15)") should be(true)
//          result = b3PostState.contains("@{7}!(7)") should be(true)
        } yield true
      }
  }

  it should "merge histories in case of multiple parents" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val genesisDeploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val genesisDeploysWithCost =
        genesisDeploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b1Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b1DeploysWithCost =
        b1Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(2))

      val b2Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b2DeploysWithCost =
        b2Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b3Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val b3DeploysWithCost =
        b3Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(5))

      /*
       * DAG Looks like this:
       *
       *           b3
       *          /  \
       *        b1    b2
       *         \    /
       *         genesis
       */
      mkRuntimeManager("interpreter-util-test").use { runtimeManager =>
        for {
          genesis                                     <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
          b1                                          <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
          b2                                          <- createBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
          b3                                          <- createBlock[Task](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
          dag1                                        <- blockDagStorage.getRepresentation
          blockCheckpoint                             <- computeBlockCheckpoint(genesis, genesis, dag1, runtimeManager)
          (postGenStateHash, postGenProcessedDeploys) = blockCheckpoint
          _                                           <- injectPostStateHash[Task](0, genesis, postGenStateHash, postGenProcessedDeploys)
          dag2                                        <- blockDagStorage.getRepresentation
          blockCheckpointB1 <- computeBlockCheckpoint(
                                b1,
                                genesis,
                                dag2,
                                runtimeManager
                              )
          (postB1StateHash, postB1ProcessedDeploys) = blockCheckpointB1
          _                                         <- injectPostStateHash[Task](1, b1, postB1StateHash, postB1ProcessedDeploys)
          dag3                                      <- blockDagStorage.getRepresentation
          blockCheckpointB2 <- computeBlockCheckpoint(
                                b2,
                                genesis,
                                dag3,
                                runtimeManager
                              )
          (postB2StateHash, postB2ProcessedDeploys) = blockCheckpointB2
          _                                         <- injectPostStateHash[Task](2, b2, postB2StateHash, postB2ProcessedDeploys)
          updatedGenesis                            <- blockDagStorage.lookupByIdUnsafe(0)
          dag4                                      <- blockDagStorage.getRepresentation
          blockCheckpointB3 <- computeBlockCheckpoint(
                                b3,
                                updatedGenesis,
                                dag4,
                                runtimeManager
                              )
          (postb3StateHash, _) = blockCheckpointB3
//          b3PostState          = runtimeManager.storageRepr(postb3StateHash).get
//
//          _      = b3PostState.contains("@{1}!(15)") should be(true)
//          _      = b3PostState.contains("@{5}!(5)") should be(true)
//          result = b3PostState.contains("@{6}!(6)") should be(true)
        } yield true
      }
  }

  val registry =
    """
  """.stripMargin

  val other =
    """
  """.stripMargin

  def prepareDeploys(v: Vector[ByteString], c: Double) = {
    val genesisDeploys =
      v.map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
    genesisDeploys.map(d => ProcessedDeploy().withDeploy(d).withCost(c))
  }

  it should "merge histories in case of multiple parents with complex contract" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val contract = ByteString.copyFromUtf8(registry)

      val genesisDeploysWithCost = prepareDeploys(Vector.empty, 1)
      val b1DeploysWithCost      = prepareDeploys(Vector(contract), 2)
      val b2DeploysWithCost      = prepareDeploys(Vector(contract), 1)
      val b3DeploysWithCost      = prepareDeploys(Vector.empty, 5)

      /*
       * DAG Looks like this:
       *
       *           b3
       *          /  \
       *        b1    b2
       *         \    /
       *         genesis
       */

      mkRuntimeManager("interpreter-util-test")
        .use { runtimeManager =>
          def step(index: Int, genesis: BlockMessage) =
            for {
              b1  <- blockDagStorage.lookupByIdUnsafe(index)
              dag <- blockDagStorage.getRepresentation
              computeBlockCheckpointResult <- computeBlockCheckpoint(
                                               b1,
                                               genesis,
                                               dag,
                                               runtimeManager
                                             )
              (postB1StateHash, postB1ProcessedDeploys) = computeBlockCheckpointResult
              result <- injectPostStateHash[Task](
                         index,
                         b1,
                         postB1StateHash,
                         postB1ProcessedDeploys
                       )
            } yield result
          for {
            genesis   <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
            b1        <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
            b2        <- createBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
            b3        <- createBlock[Task](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
            _         <- step(0, genesis)
            _         <- step(1, genesis)
            _         <- step(2, genesis)
            dag       <- blockDagStorage.getRepresentation
            postState <- validateBlockCheckpoint[Task](b3, dag, runtimeManager)
            // Result should be validated post-state-hash.
            result = postState should matchPattern { case Right(Some(_)) => }
          } yield result
        }
  }

  it should "merge histories in case of multiple parents (uneven histories)" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val contract = ByteString.copyFromUtf8(registry)

      val genesisDeploysWithCost = prepareDeploys(Vector(contract), 1)

      val b1DeploysWithCost = prepareDeploys(Vector(contract), 2)

      val b2DeploysWithCost = prepareDeploys(Vector(contract), 1)

      val b3DeploysWithCost = prepareDeploys(Vector(contract), 5)

      val b4DeploysWithCost = prepareDeploys(Vector(contract), 5)

      val b5DeploysWithCost = prepareDeploys(Vector(contract), 5)

      /*
       * DAG Looks like this:
       *
       *           b5
       *          /  \
       *         |    |
       *         |    b4
       *         |    |
       *        b2    b3
       *         \    /
       *          \  /
       *           |
       *           b1
       *           |
       *         genesis
       */

      mkRuntimeManager("interpreter-util-test")
        .use { runtimeManager =>
          def step(index: Int, genesis: BlockMessage) =
            for {
              b1  <- blockDagStorage.lookupByIdUnsafe(index)
              dag <- blockDagStorage.getRepresentation
              computeBlockCheckpointResult <- computeBlockCheckpoint(
                                               b1,
                                               genesis,
                                               dag,
                                               runtimeManager
                                             )
              (postB1StateHash, postB1ProcessedDeploys) = computeBlockCheckpointResult
              result <- injectPostStateHash[Task](
                         index,
                         b1,
                         postB1StateHash,
                         postB1ProcessedDeploys
                       )
            } yield result

          for {
            genesis <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
            b1      <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
            b2      <- createBlock[Task](Seq(b1.blockHash), deploys = b2DeploysWithCost)
            b3      <- createBlock[Task](Seq(b1.blockHash), deploys = b3DeploysWithCost)
            b4      <- createBlock[Task](Seq(b3.blockHash), deploys = b4DeploysWithCost)
            b5      <- createBlock[Task](Seq(b2.blockHash, b4.blockHash), deploys = b5DeploysWithCost)
            dag1    <- blockDagStorage.getRepresentation
            computeBlockCheckpointResult <- computeBlockCheckpoint(
                                             genesis,
                                             genesis,
                                             dag1,
                                             runtimeManager
                                           )
            (postGenStateHash, postGenProcessedDeploys) = computeBlockCheckpointResult
            _                                           <- injectPostStateHash[Task](0, genesis, postGenStateHash, postGenProcessedDeploys)
            _                                           <- step(1, genesis)
            _                                           <- step(2, genesis)
            _                                           <- step(3, genesis)
            _                                           <- step(4, genesis)

            dag2      <- blockDagStorage.getRepresentation
            postState <- validateBlockCheckpoint[Task](b5, dag2, runtimeManager)
            // Result should be validated post-state-hash.
            result = postState should matchPattern { case Right(Some(_)) => }
          } yield result
        }
  }

  def computeSingleProcessedDeploy(
      runtimeManager: RuntimeManager[Task],
      dag: BlockDagRepresentation[Task],
      deploy: Deploy*
  )(implicit blockStore: BlockStore[Task]): Task[Seq[InternalProcessedDeploy]] =
    for {
      executionResults <- Task.traverse(deploy) { d =>
                           runtimeManager
                             .sendDeploy(ProtoUtil.deployDataToEEDeploy(d.getRaw))
                             .flatMap {
                               case Right(effect) => Task.now(d -> effect)
                               // FIXME: The `computeDeploysCheckpoint` should allow passing in
                               // negative results but it needs to change in MultiParentCasperImpl as well.
                               case Left(ex) => Task.raiseError(ex)
                             }
                         }
      computeResult <- computeDeploysCheckpoint[Task](
                        Seq.empty,
                        executionResults,
                        dag,
                        runtimeManager
                      )
      Right((_, _, result)) = computeResult
    } yield result

  "computeDeploysCheckpoint" should "aggregate the result of deploying multiple programs within the block" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        //reference costs
        //deploy each Rholang program separately and record its cost
        val deploy1 = ProtoUtil.sourceDeploy(
          ByteString.copyFromUtf8("@1!(Nil)"),
          System.currentTimeMillis(),
          Integer.MAX_VALUE
        )
        val deploy2 =
          ProtoUtil.sourceDeploy(
            ByteString.copyFromUtf8("@3!([1,2,3,4])"),
            System.currentTimeMillis(),
            Integer.MAX_VALUE
          )
        val deploy3 =
          ProtoUtil.sourceDeploy(
            ByteString.copyFromUtf8("for(@x <- @0) { @4!(x.toByteArray()) }"),
            System.currentTimeMillis(),
            Integer.MAX_VALUE
          )
        mkRuntimeManager("interpreter-util-test").use { runtimeManager =>
          for {
            dag           <- blockDagStorage.getRepresentation
            proc1         <- computeSingleProcessedDeploy(runtimeManager, dag, deploy1)
            proc2         <- computeSingleProcessedDeploy(runtimeManager, dag, deploy2)
            proc3         <- computeSingleProcessedDeploy(runtimeManager, dag, deploy3)
            singleResults = proc1 ++ proc2 ++ proc3
            batchDeploy   = Seq(deploy1, deploy2, deploy3)
            batchResult   <- computeSingleProcessedDeploy(runtimeManager, dag, batchDeploy: _*)
          } yield batchResult should contain theSameElementsAs singleResults
      }
  }

  it should "return result of deploying even if one of the programs withing the deployment throws an error" in
    withStorage { implicit blockStore => implicit blockDagStorage =>
      //deploy each Rholang program separately and record its cost
      val deploy1 =
        ProtoUtil.sourceDeploy(
          ByteString.EMPTY,
          System.currentTimeMillis(),
          Integer.MAX_VALUE
        )
      val deploy2 =
        ProtoUtil.sourceDeploy(
          ByteString.EMPTY,
          System.currentTimeMillis(),
          Integer.MAX_VALUE
        )
      mkRuntimeManager("interpreter-util-test").use { runtimeManager =>
        for {
          dag <- blockDagStorage.getRepresentation

          proc1 <- computeSingleProcessedDeploy(runtimeManager, dag, deploy1)
          proc2 <- computeSingleProcessedDeploy(runtimeManager, dag, deploy2)

          singleResults = proc1 ++ proc2

          deployErr = ProtoUtil.sourceDeploy(
            ByteString.copyFromUtf8("@3!(\"a\" + 3)"),
            System.currentTimeMillis(),
            Integer.MAX_VALUE
          )
          batchDeploy = Seq(deploy1, deploy2, deployErr)
          batchResult <- computeSingleProcessedDeploy(runtimeManager, dag, batchDeploy: _*)
        } yield {
          batchResult should have size 3
          batchResult.take(2) should contain theSameElementsAs singleResults
          // FIXME: Currently if a deploy were to throw an error it wouldn't
          // make it into the block at all. We want user errors to be in there.
          pendingUntilFixed {
            batchResult.last.result.isFailed shouldBe true
          }
        }
    }
    }

  "validateBlockCheckpoint" should "not return a checkpoint for an invalid block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val deploys = Vector(ByteString.EMPTY)
        .map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val processedDeploys = deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))
      val invalidHash      = ByteString.copyFromUtf8("invalid")
      mkRuntimeManager("interpreter-util-test").use { runtimeManager =>
        for {
          block            <- createBlock[Task](Seq.empty, deploys = processedDeploys, tsHash = invalidHash)
          dag              <- blockDagStorage.getRepresentation
          validateResult   <- validateBlockCheckpoint[Task](block, dag, runtimeManager)
          Right(stateHash) = validateResult
        } yield stateHash should be(None)
      }
  }

  it should "return a checkpoint with the right hash for a valid block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val deploys =
        Vector(
          ByteString.EMPTY
        ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))

      mkRuntimeManager("interpreter-util-test").use { runtimeManager =>
        for {
          dag1 <- blockDagStorage.getRepresentation
          deploysCheckpoint <- computeDeploysCheckpoint[Task](
                                Seq.empty,
                                deploys.map((_, ExecutionEffect())),
                                dag1,
                                runtimeManager
                              )
          Right((preStateHash, computedTsHash, processedDeploys)) = deploysCheckpoint
          block <- createBlock[Task](
                    Seq.empty,
                    deploys = processedDeploys.map(ProcessedDeployUtil.fromInternal),
                    tsHash = computedTsHash
                  )
          dag2 <- blockDagStorage.getRepresentation

          validateResult <- validateBlockCheckpoint[Task](block, dag2, runtimeManager)
          Right(tsHash)  = validateResult
        } yield tsHash should be(Some(computedTsHash))
      }
  }

  it should "pass persistent produce test with causality" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val deploys =
        Vector("""new x, y, delay in {
              contract delay(@n) = {
                if (n < 100) {
                  delay!(n + 1)
                } else {
                  x!!(1)
                }
              } |
              delay!(0) |
              y!(0) |
              for (_ <- x; @0 <- y) { y!(1) } |
              for (_ <- x; @1 <- y) { y!(2) } |
              for (_ <- x; @2 <- y) { y!(3) } |
              for (_ <- x; @3 <- y) { y!(4) } |
              for (_ <- x; @4 <- y) { y!(5) } |
              for (_ <- x; @5 <- y) { y!(6) } |
              for (_ <- x; @6 <- y) { y!(7) } |
              for (_ <- x; @7 <- y) { y!(8) } |
              for (_ <- x; @8 <- y) { y!(9) } |
              for (_ <- x; @9 <- y) { y!(10) } |
              for (_ <- x; @10 <- y) { y!(11) } |
              for (_ <- x; @11 <- y) { y!(12) } |
              for (_ <- x; @12 <- y) { y!(13) } |
              for (_ <- x; @13 <- y) { y!(14) } |
              for (_ <- x; @14 <- y) { Nil }
             }
          """)
          .map(
            s =>
              ProtoUtil.sourceDeploy(
                ByteString.copyFromUtf8(s),
                System.currentTimeMillis(),
                Integer.MAX_VALUE
              )
          )
      mkRuntimeManager("interpreter-util-test").use { runtimeManager =>
        for {
          dag1 <- blockDagStorage.getRepresentation
          deploysCheckpoint <- computeDeploysCheckpoint[Task](
                                Seq.empty,
                                deploys.map((_, ExecutionEffect())),
                                dag1,
                                runtimeManager
                              )
          Right((preStateHash, computedTsHash, processedDeploys)) = deploysCheckpoint
          block <- createBlock[Task](
                    Seq.empty,
                    deploys = processedDeploys.map(ProcessedDeployUtil.fromInternal),
                    tsHash = computedTsHash,
                    preStateHash = preStateHash
                  )
          dag2           <- blockDagStorage.getRepresentation
          validateResult <- validateBlockCheckpoint[Task](block, dag2, runtimeManager)
          Right(tsHash)  = validateResult
        } yield tsHash should be(Some(computedTsHash))
      }
  }

  it should "pass map update test" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      (0 to 10).toList.traverse_ { _ =>
        val deploys =
          Vector(
            """
            | @"mapStore"!({}) |
            | contract @"store"(@value) = {
            |   new key in {
            |     for (@map <- @"mapStore") {
            |       @"mapStore"!(map.set(*key.toByteArray(), value))
            |     }
            |   }
            | }
            |""".stripMargin,
            """
            |@"store"!("1")
          """.stripMargin,
            """
            |@"store"!("2")
          """.stripMargin
          ).map(
            s =>
              ProtoUtil.sourceDeploy(
                ByteString.copyFromUtf8(s),
                System.currentTimeMillis(),
                Integer.MAX_VALUE
              )
          )

        mkRuntimeManager("interpreter-util-test").use { runtimeManager =>
          for {
            dag1 <- blockDagStorage.getRepresentation
            deploysCheckpoint <- computeDeploysCheckpoint[Task](
                                  Seq.empty,
                                  deploys.map((_, ExecutionEffect())),
                                  dag1,
                                  runtimeManager
                                )
            Right((preStateHash, computedTsHash, processedDeploys)) = deploysCheckpoint
            block <- createBlock[Task](
                      Seq.empty,
                      deploys = processedDeploys.map(ProcessedDeployUtil.fromInternal),
                      tsHash = computedTsHash,
                      preStateHash = preStateHash
                    )
            dag2           <- blockDagStorage.getRepresentation
            validateResult <- validateBlockCheckpoint[Task](block, dag2, runtimeManager)
            Right(tsHash)  = validateResult
          } yield tsHash should be(Some(computedTsHash))
        }
      }
  }

  "findMultiParentsBlockHashesForReplay" should "filter out duplicate ancestors of main parent block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val genesisDeploysWithCost = prepareDeploys(Vector.empty, 1.0)
      val b1DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1.0)
      val b2DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1.0)
      val b3DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1.0)

      /*
       * DAG Looks like this:
       *
       *           b3
       *          /  \
       *        b1    b2
       *         \    /
       *         genesis
       */

      mkRuntimeManager("interpreter-util-test")
        .use { runtimeManager =>
          def step(index: Int, genesis: BlockMessage) =
            for {
              b1  <- blockDagStorage.lookupByIdUnsafe(index)
              dag <- blockDagStorage.getRepresentation
              computeBlockCheckpointResult <- computeBlockCheckpoint(
                                               b1,
                                               genesis,
                                               dag,
                                               runtimeManager
                                             )
              (postB1StateHash, postB1ProcessedDeploys) = computeBlockCheckpointResult
              result <- injectPostStateHash[Task](
                         index,
                         b1,
                         postB1StateHash,
                         postB1ProcessedDeploys
                       )
            } yield result
          for {
            genesis <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
            b1      <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
            b2      <- createBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
            b3      <- createBlock[Task](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
            _       <- step(0, genesis)
            _       <- step(1, genesis)
            _       <- step(2, genesis)
            dag     <- blockDagStorage.getRepresentation
            blockHashes <- InterpreterUtil.findMultiParentsBlockHashesForReplay(
                            Seq(b1, b2),
                            dag
                          )
            _ = withClue("Main parent hasn't been filtered out: ") { blockHashes.size shouldBe (1) }

          } yield ()
        }
  }

}
