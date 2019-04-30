package io.casperlabs.casper.util.execengine

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub.mock
import io.casperlabs.casper.{InvalidPostStateHash, InvalidPreStateHash, Validate}
import io.casperlabs.ipc._
import io.casperlabs.models.SmartContractEngineError
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

class ExecEngineUtilTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockDagStorageFixture {

  implicit val logEff = new LogStub[Task]

  implicit val executionEngineService: ExecutionEngineService[Task] =
    HashSetCasperTestNode.simpleEEApi[Task](Map.empty)

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

      for {
        genesis                                     <- createBlock[Task](Seq.empty, deploys = genesisDeploysCost)
        b1                                          <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysCost)
        b2                                          <- createBlock[Task](Seq(b1.blockHash), deploys = b2DeploysCost)
        b3                                          <- createBlock[Task](Seq(b2.blockHash), deploys = b3DeploysCost)
        dag1                                        <- blockDagStorage.getRepresentation
        blockCheckpoint                             <- computeBlockCheckpoint(genesis, genesis, dag1)
        (postGenStateHash, postGenProcessedDeploys) = blockCheckpoint
        _                                           <- injectPostStateHash[Task](0, genesis, postGenStateHash, postGenProcessedDeploys)
        dag2                                        <- blockDagStorage.getRepresentation
        blockCheckpointB1                           <- computeBlockCheckpoint(b1, genesis, dag2)
        (postB1StateHash, postB1ProcessedDeploys)   = blockCheckpointB1
        _                                           <- injectPostStateHash[Task](1, b1, postB1StateHash, postB1ProcessedDeploys)
        dag3                                        <- blockDagStorage.getRepresentation
        blockCheckpointB2 <- computeBlockCheckpoint(
                              b2,
                              genesis,
                              dag3
                            )
        (postB2StateHash, postB2ProcessedDeploys) = blockCheckpointB2
        _                                         <- injectPostStateHash[Task](2, b2, postB2StateHash, postB2ProcessedDeploys)

        dag4 <- blockDagStorage.getRepresentation
        blockCheckpointB4 <- computeBlockCheckpoint(
                              b3,
                              genesis,
                              dag4
                            )
        (postb3StateHash, _) = blockCheckpointB4
//          b3PostState          = runtimeManager.storageRepr(postb3StateHash).get
//
//          _      = b3PostState.contains("@{1}!(1)") should be(true)
//          _      = b3PostState.contains("@{1}!(15)") should be(true)
//          result = b3PostState.contains("@{7}!(7)") should be(true)
      } yield true
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
      for {
        genesis                                     <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1                                          <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2                                          <- createBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        b3                                          <- createBlock[Task](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
        dag1                                        <- blockDagStorage.getRepresentation
        blockCheckpoint                             <- computeBlockCheckpoint(genesis, genesis, dag1)
        (postGenStateHash, postGenProcessedDeploys) = blockCheckpoint
        _                                           <- injectPostStateHash[Task](0, genesis, postGenStateHash, postGenProcessedDeploys)
        dag2                                        <- blockDagStorage.getRepresentation
        blockCheckpointB1 <- computeBlockCheckpoint(
                              b1,
                              genesis,
                              dag2
                            )
        (postB1StateHash, postB1ProcessedDeploys) = blockCheckpointB1
        _                                         <- injectPostStateHash[Task](1, b1, postB1StateHash, postB1ProcessedDeploys)
        dag3                                      <- blockDagStorage.getRepresentation
        blockCheckpointB2 <- computeBlockCheckpoint(
                              b2,
                              genesis,
                              dag3
                            )
        (postB2StateHash, postB2ProcessedDeploys) = blockCheckpointB2
        _                                         <- injectPostStateHash[Task](2, b2, postB2StateHash, postB2ProcessedDeploys)
        updatedGenesis                            <- blockDagStorage.lookupByIdUnsafe(0)
        dag4                                      <- blockDagStorage.getRepresentation
        blockCheckpointB3 <- computeBlockCheckpoint(
                              b3,
                              updatedGenesis,
                              dag4
                            )
        (postb3StateHash, _) = blockCheckpointB3
//          b3PostState          = runtimeManager.storageRepr(postb3StateHash).get
//
//          _      = b3PostState.contains("@{1}!(15)") should be(true)
//          _      = b3PostState.contains("@{5}!(5)") should be(true)
//          result = b3PostState.contains("@{6}!(6)") should be(true)
      } yield true
  }

  val registry = """ """.stripMargin

  val other = """ """.stripMargin

  def prepareDeploys(v: Vector[ByteString], c: Long) = {
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

      def step(index: Int, genesis: BlockMessage) =
        for {
          b1  <- blockDagStorage.lookupByIdUnsafe(index)
          dag <- blockDagStorage.getRepresentation
          computeBlockCheckpointResult <- computeBlockCheckpoint(
                                           b1,
                                           genesis,
                                           dag
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
        postState <- ExecEngineUtil.validateBlockCheckpoint[Task](
                      b3,
                      dag
                    )
      } yield pendingUntilFixed(postState shouldBe 'right)
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

      def step(index: Int, genesis: BlockMessage) =
        for {
          b1  <- blockDagStorage.lookupByIdUnsafe(index)
          dag <- blockDagStorage.getRepresentation
          computeBlockCheckpointResult <- computeBlockCheckpoint(
                                           b1,
                                           genesis,
                                           dag
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
                                         dag1
                                       )
        (postGenStateHash, postGenProcessedDeploys) = computeBlockCheckpointResult
        _                                           <- injectPostStateHash[Task](0, genesis, postGenStateHash, postGenProcessedDeploys)
        _                                           <- step(1, genesis)
        _                                           <- step(2, genesis)
        _                                           <- step(3, genesis)
        _                                           <- step(4, genesis)

        dag2 <- blockDagStorage.getRepresentation
        postState <- ExecEngineUtil.validateBlockCheckpoint[Task](
                      b5,
                      dag2
                    )
      } yield pendingUntilFixed(postState shouldBe 'right)
  }

  def computeSingleProcessedDeploy(
      dag: BlockDagRepresentation[Task],
      deploy: Seq[DeployData],
      protocolVersion: ProtocolVersion = ProtocolVersion(1)
  )(
      implicit blockStore: BlockStore[Task],
      executionEngineService: ExecutionEngineService[Task]
  ): Task[Seq[ProcessedDeploy]] =
    for {
      computeResult <- ExecEngineUtil
                        .computeDeploysCheckpoint[Task](
                          Seq.empty,
                          deploy,
                          dag,
                          protocolVersion
                        )
      DeploysCheckpoint(_, _, result, _, _) = computeResult
    } yield result

  "computeDeploysCheckpoint" should "aggregate the result of deploying multiple programs within the block" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        // reference costs
        // deploy each Rholang program separately and record its cost
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
        for {
          dag           <- blockDagStorage.getRepresentation
          proc1         <- computeSingleProcessedDeploy(dag, Seq(deploy1))
          proc2         <- computeSingleProcessedDeploy(dag, Seq(deploy2))
          proc3         <- computeSingleProcessedDeploy(dag, Seq(deploy3))
          singleResults = proc1 ++ proc2 ++ proc3
          batchDeploy   = Seq(deploy1, deploy2, deploy3)
          batchResult   <- computeSingleProcessedDeploy(dag, batchDeploy)
        } yield batchResult should contain theSameElementsAs singleResults
  }

  it should "keep track of different deploys with identical effects (NODE-376)" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        // Create multiple identical deploys.
        val startTime = System.currentTimeMillis
        val deploys = List.range(0, 10).map { i =>
          ProtoUtil.sourceDeploy(
            ByteString.copyFromUtf8("Doesn't matter what this is."),
            startTime + i,
            Integer.MAX_VALUE
          )
        }
        for {
          dag <- blockDagStorage.getRepresentation
          checkpoint <- ExecEngineUtil.computeDeploysCheckpoint(
                         parents = Seq.empty,
                         deploys = deploys,
                         dag = dag,
                         ProtocolVersion(1)
                       )
        } yield {
          val processedDeploys = checkpoint.deploysForBlock.map(_.getDeploy)
          processedDeploys should contain theSameElementsInOrderAs deploys
      }
  }

  "validateBlockCheckpoint" should "return InvalidPreStateHash when preStateHash of block is not correct" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val contract = ByteString.copyFromUtf8(registry)

      val genesisDeploysWithCost = prepareDeploys(Vector.empty, 1)
      val b1DeploysWithCost      = prepareDeploys(Vector(contract), 2)
      val b2DeploysWithCost      = prepareDeploys(Vector(contract), 1)
      val b3DeploysWithCost      = prepareDeploys(Vector.empty, 5)
      val invalidHash            = ByteString.copyFromUtf8("invalid")

      for {
        genesis <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        // set wrong preStateHash for b3
        b3 <- createBlock[Task](
               Seq(b1.blockHash, b2.blockHash),
               deploys = b3DeploysWithCost,
               preStateHash = invalidHash
             )
        dag <- blockDagStorage.getRepresentation
        postState <- ExecEngineUtil.validateBlockCheckpoint[Task](
                      b3,
                      dag
                    )
      } yield postState shouldBe Left(Validate.ValidateErrorWrapper(InvalidPreStateHash))
  }

  "validateBlockCheckpoint" should "return InvalidPostStateHash when postStateHash of block is not correct" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val deploys = Vector(ByteString.EMPTY)
        .map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val processedDeploys = deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))
      val invalidHash      = ByteString.copyFromUtf8("invalid")
      for {
        genesis <- createBlock[Task](
                    Seq.empty,
                    deploys = processedDeploys,
                    postStateHash = invalidHash
                  )
        dag <- blockDagStorage.getRepresentation
        validateResult <- ExecEngineUtil.validateBlockCheckpoint[Task](
                           genesis,
                           dag
                         )
      } yield validateResult shouldBe Left(Validate.ValidateErrorWrapper(InvalidPostStateHash))
  }

  it should "return a checkpoint with the right hash for a valid block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val deploys =
        Vector(
          ByteString.EMPTY
        ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))

      for {
        dag1 <- blockDagStorage.getRepresentation
        deploysCheckpoint <- ExecEngineUtil.computeDeploysCheckpoint[Task](
                              Seq.empty,
                              deploys,
                              dag1,
                              ProtocolVersion(1)
                            )
        DeploysCheckpoint(preStateHash, computedPostStateHash, processedDeploys, _, _) = deploysCheckpoint
        block <- createBlock[Task](
                  Seq.empty,
                  deploys = processedDeploys,
                  postStateHash = computedPostStateHash,
                  preStateHash = preStateHash
                )
        dag2 <- blockDagStorage.getRepresentation

        validateResult <- ExecEngineUtil.validateBlockCheckpoint[Task](
                           block,
                           dag2
                         )
        Right(postStateHash) = validateResult
      } yield postStateHash should be(computedPostStateHash)
  }

  "findMultiParentsBlockHashesForReplay" should "filter out duplicate ancestors of main parent block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val genesisDeploysWithCost = prepareDeploys(Vector.empty, 1L)
      val b1DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1L)
      val b2DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1L)
      val b3DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1L)

      /*
       * DAG Looks like this:
       *
       *           b3
       *          /  \
       *        b1    b2
       *         \    /
       *         genesis
       */

      def step(index: Int, genesis: BlockMessage) =
        for {
          b1  <- blockDagStorage.lookupByIdUnsafe(index)
          dag <- blockDagStorage.getRepresentation
          computeBlockCheckpointResult <- computeBlockCheckpoint(
                                           b1,
                                           genesis,
                                           dag
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
        blockHashes <- ExecEngineUtil.blocksToApply(
                        Seq(b1, b2),
                        dag
                      )
        _ = withClue("Main parent hasn't been filtered out: ") { blockHashes.size shouldBe (1) }

      } yield ()
  }

  "computeDeploysCheckpoint" should "throw exception when EE Service Failed" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val failedExecEEService: ExecutionEngineService[Task] =
        mock[Task](
          (_, _, _) => new Throwable("failed when exec deploys").asLeft.pure[Task],
          (_, _) => new Throwable("failed when commit transform").asLeft.pure[Task],
          (_, _, _) => new SmartContractEngineError("unimplemented").asLeft.pure[Task],
          _ => Seq.empty[Bond].pure[Task],
          _ => Task.unit,
          _ => ().asRight[String].pure[Task]
        )

      val failedCommitEEService: ExecutionEngineService[Task] =
        mock[Task](
          (_, deploys, _) =>
            Task.now {
              def getExecutionEffect(deploy: Deploy) = {
                val key =
                  Key(Key.KeyInstance.Hash(KeyHash(ByteString.copyFromUtf8(deploy.toProtoString))))
                val transform     = Transform(Transform.TransformInstance.Identity(TransformIdentity()))
                val op            = Op(Op.OpInstance.Noop(io.casperlabs.ipc.NoOp()))
                val transforEntry = TransformEntry(Some(key), Some(transform))
                val opEntry       = OpEntry(Some(key), Some(op))
                ExecutionEffect(Seq(opEntry), Seq(transforEntry))
              }
              deploys
                .map(d => DeployResult(10, DeployResult.Result.Effects(getExecutionEffect(d))))
                .asRight[Throwable]
            },
          (_, _) => new Throwable("failed when commit transform").asLeft.pure[Task],
          (_, _, _) => new SmartContractEngineError("unimplemented").asLeft.pure[Task],
          _ => Seq.empty[Bond].pure[Task],
          _ => Task.unit,
          _ => ().asRight[String].pure[Task]
        )

      val genesisDeploysWithCost = prepareDeploys(Vector.empty, 1L)
      val b1DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1L)
      val b2DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1L)
      val b3DeploysWithCost      = prepareDeploys(Vector(ByteString.EMPTY), 1L)

      /*
       * DAG Looks like this:
       *
       *           b3
       *          /  \
       *        b1    b2
       *         \    /
       *         genesis
       */

      def step(index: Int, genesis: BlockMessage)(
          implicit executionEngineService: ExecutionEngineService[Task]
      ) =
        for {
          b1  <- blockDagStorage.lookupByIdUnsafe(index)
          dag <- blockDagStorage.getRepresentation
          computeBlockCheckpointResult <- computeBlockCheckpoint(
                                           b1,
                                           genesis,
                                           dag
                                         )
          (postB1StateHash, postB1ProcessedDeploys) = computeBlockCheckpointResult
          result <- injectPostStateHash[Task](
                     index,
                     b1,
                     postB1StateHash,
                     postB1ProcessedDeploys
                   )
        } yield postB1StateHash

      for {
        genesis <- createBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        b3      <- createBlock[Task](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
        _       <- step(0, genesis)
        _       <- step(1, genesis)
        r1 <- step(2, genesis)(failedExecEEService).onErrorHandleWith { ex =>
               Task.now {
                 ex.getMessage should startWith("failed when exec")
                 ByteString.copyFromUtf8("succeed")
               }
             }
        _ = r1 should be(ByteString.copyFromUtf8("succeed"))
        r2 <- step(2, genesis)(failedCommitEEService).onErrorHandleWith { ex =>
               Task.now {
                 ex.getMessage should startWith("failed when commit")
                 ByteString.copyFromUtf8("succeed")
               }
             }
        _ = r1 should be(ByteString.copyFromUtf8("succeed"))
      } yield ()
  }

}
