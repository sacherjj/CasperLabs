package io.casperlabs.casper.util.rholang

import java.nio.file.{Files, Paths}

import cats.mtl.implicits._
import cats.{Applicative, Id, Monad}
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore
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
  val storageSize                = 1024L * 1024
  val storageDirectory           = Files.createTempDirectory("casper-interp-util-test")
  val initState: IndexedBlockDag = IndexedBlockDag.empty.copy(currentId = -1)

  implicit val logEff: LogStub[Id] = new LogStub[Task]
  implicit val absId               = ToAbstractContext.idToAbstractContext

  private val runtimeDir = Files.createTempDirectory(s"interpreter-util-test")

  private val socket = Paths.get(runtimeDir.toString, ".casper-node.sock")
  implicit val executionEngineService: GrpcExecutionEngineService =
    new GrpcExecutionEngineService(
      socket,
      4 * 1024 * 1024
    )

  private def computeBlockCheckpoint(
      b: BlockMessage,
      genesis: BlockMessage,
      dag: BlockDag,
      runtimeManager: RuntimeManager[Task]
  ): (StateHash, Seq[ProcessedDeploy]) =
    (ByteString.EMPTY, Seq())

  "computeBlockCheckpoint" should "compute the final post-state of a chain properly" in withIndexedBlockDagStorage {
    implicit blockDagStorage =>
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
        blockCheckpoint                             <- computeBlockCheckpoint(genesis, genesis, dag1, runtimeManager)
        (postGenStateHash, postGenProcessedDeploys) = blockCheckpoint
        _ <- injectPostStateHash(
              blockDagStorage,
              0,
              genesis,
              postGenStateHash,
              postGenProcessedDeploys
            )
        genPostState = runtimeManager.storageRepr(postGenStateHash).get

        _    = genPostState.contains("@{2}!(2)") should be(true)
        _    = genPostState.contains("@{123}!(5)") should be(true)
        dag2 <- blockDagStorage.getRepresentation
        blockCheckpointB1 <- computeBlockCheckpoint(
                              b1,
                              genesis,
                              dag2,
                              runtimeManager
                            )
        (postB1StateHash, postB1ProcessedDeploys) = blockCheckpointB1
        _                                         <- injectPostStateHash(blockDagStorage, 1, b1, postB1StateHash, postB1ProcessedDeploys)
        b1PostState                               = runtimeManager.storageRepr(postB1StateHash).get
        _                                         = b1PostState.contains("@{1}!(1)") should be(true)
        _                                         = b1PostState.contains("@{123}!(5)") should be(true)
        _                                         = b1PostState.contains("@{456}!(10)") should be(true)
        dag3                                      <- blockDagStorage.getRepresentation
        blockCheckpointB2 <- computeBlockCheckpoint(
                              b2,
                              genesis,
                              dag3,
                              runtimeManager
                            )
        (postB2StateHash, postB2ProcessedDeploys) = blockCheckpointB2
        _                                         <- injectPostStateHash(blockDagStorage, 2, b2, postB2StateHash, postB2ProcessedDeploys)

        dag4 <- blockDagStorage.getRepresentation
        blockCheckpointB4 <- computeBlockCheckpoint(
                              b3,
                              genesis,
                              dag4,
                              runtimeManager
                            )
        (postb3StateHash, _) = blockCheckpointB4
        b3PostState          = runtimeManager.storageRepr(postb3StateHash).get

        _      = b3PostState.contains("@{1}!(1)") should be(true)
        _      = b3PostState.contains("@{1}!(15)") should be(true)
        result = b3PostState.contains("@{7}!(7)") should be(true)
      } yield result
  }

  ignore should "merge histories in case of multiple parents" in withIndexedBlockDagStorage {
    implicit blockDagStorage =>
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
        blockCheckpoint                             <- computeBlockCheckpoint(genesis, genesis, dag1, runtimeManager)
        (postGenStateHash, postGenProcessedDeploys) = blockCheckpoint
        _ <- injectPostStateHash(
              blockDagStorage,
              0,
              genesis,
              postGenStateHash,
              postGenProcessedDeploys
            )
        dag2 <- blockDagStorage.getRepresentation
        blockCheckpointB1 <- computeBlockCheckpoint(
                              b1,
                              genesis,
                              dag2,
                              runtimeManager
                            )
        (postB1StateHash, postB1ProcessedDeploys) = blockCheckpointB1
        _                                         <- injectPostStateHash(blockDagStorage, 1, b1, postB1StateHash, postB1ProcessedDeploys)
        dag3                                      <- blockDagStorage.getRepresentation
        blockCheckpointB2 <- computeBlockCheckpoint(
                              b2,
                              genesis,
                              dag3,
                              runtimeManager
                            )
        (postB2StateHash, postB2ProcessedDeploys) = blockCheckpointB2
        _                                         <- injectPostStateHash(blockDagStorage, 2, b2, postB2StateHash, postB2ProcessedDeploys)
        updatedGenesis                            <- blockDagStorage.lookupByIdUnsafe(0)
        dag4                                      <- blockDagStorage.getRepresentation
        blockCheckpointB3 <- computeBlockCheckpoint(
                              b3,
                              updatedGenesis,
                              dag4,
                              runtimeManager
                            )
        (postb3StateHash, _) = blockCheckpointB3
        b3PostState          = runtimeManager.storageRepr(postb3StateHash).get

        _      = b3PostState.contains("") should be(true)
        _      = b3PostState.contains("") should be(true)
        result = b3PostState.contains("") should be(true)
      } yield result
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

  it should "merge histories in case of multiple parents with complex contract" ignore {

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
    def createChain[F[_]: Monad: BlockDagState: Time: BlockStore]: F[BlockMessage] = {
      import cats.implicits._

      for {
        genesis <- createBlock[F](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createBlock[F](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createBlock[F](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        b3      <- createBlock[F](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
      } yield b3
    }

    val chain   = createChain[StateWithChain].runS(initState)
    val genesis = chain.idToBlocks(0)

    val postState = mkRuntimeManager("interpreter-util-test")
      .use { runtimeManager =>
        Task.delay {
          def step(chain: IndexedBlockDag, index: Int, genesis: BlockMessage) = {
            val b1 = chain.idToBlocks(index)
            val (postB1StateHash, postB1ProcessedDeploys) =
              computeBlockCheckpoint(
                b1,
                genesis,
                chain,
                runtimeManager
              )
            injectPostStateHash(chain, index, b1, postB1StateHash, postB1ProcessedDeploys)
          }

          val chainWithUpdatedGen = step(chain, 0, genesis)
          val chainWithUpdatedB1  = step(chainWithUpdatedGen, 1, genesis)
          val chainWithUpdatedB2  = step(chainWithUpdatedB1, 2, genesis)
          val b3                  = chainWithUpdatedB2.idToBlocks(3)
          validateBlockCheckpoint[Id](b3, chain, runtimeManager)
        }
      }
      .runSyncUnsafe(10.seconds)

    postState shouldBe Right(None)
  }

  it should "merge histories in case of multiple parents (uneven histories)" ignore {
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
    def createChain[F[_]: Monad: BlockDagState: Time: BlockStore]: F[BlockMessage] = {
      import cats.implicits._

      for {
        genesis <- createBlock[F](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createBlock[F](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createBlock[F](Seq(b1.blockHash), deploys = b2DeploysWithCost)
        b3      <- createBlock[F](Seq(b1.blockHash), deploys = b3DeploysWithCost)
        b4      <- createBlock[F](Seq(b3.blockHash), deploys = b4DeploysWithCost)
        b5      <- createBlock[F](Seq(b2.blockHash, b4.blockHash), deploys = b5DeploysWithCost)
      } yield b5
    }

    val chain   = createChain[StateWithChain].runS(initState)
    val genesis = chain.idToBlocks(0)

    val postState = mkRuntimeManager("interpreter-util-test")
      .use { runtimeManager =>
        Task.delay {

          def step(chain: IndexedBlockDag, index: Int, genesis: BlockMessage) = {
            val b1 = chain.idToBlocks(index)
            val (postB1StateHash, postB1ProcessedDeploys) =
              computeBlockCheckpoint(
                b1,
                genesis,
                chain,
                runtimeManager
              )
            injectPostStateHash(chain, index, b1, postB1StateHash, postB1ProcessedDeploys)
          }

          val (postGenStateHash, postGenProcessedDeploys) =
            computeBlockCheckpoint(genesis, genesis, chain, runtimeManager)
          val chainWithUpdatedGen =
            injectPostStateHash(chain, 0, genesis, postGenStateHash, postGenProcessedDeploys)

          val chainWithUpdatedB1 = step(chainWithUpdatedGen, 1, genesis)

          val chainWithUpdatedB2 = step(chainWithUpdatedB1, 2, genesis)

          val chainWithUpdatedB3 = step(chainWithUpdatedB2, 3, genesis)

          val chainWithUpdatedB4 = step(chainWithUpdatedB3, 4, genesis)

          validateBlockCheckpoint[Id](chainWithUpdatedB4.idToBlocks(5), chain, runtimeManager)
        }
      }
      .runSyncUnsafe(10.seconds)

    postState shouldBe Right(None)
  }

  def computeSingleProcessedDeploy(
      runtimeManager: RuntimeManager[Task],
      dag: BlockDagRepresentation[Task],
      deploy: Deploy*
  )(implicit blockStore: BlockStore[Task]): Task[Seq[InternalProcessedDeploy]] =
    for {
      computeResult         <- computeDeploysCheckpoint[Task](Seq.empty, deploy, dag, runtimeManager)
      Right((_, _, result)) = computeResult
    } yield result

  "computeDeploysCheckpoint" should "aggregate cost of deploying rholang programs within the block" in withStorage {
    implicit blockStore =>
      implicit blockDagStorage =>
        //reference costs
        //deploy each Rholang program separately and record its cost
        val deploy1 = ProtoUtil.termDeploy(
          mkTerm("@1!(Nil)").toOption.get,
          System.currentTimeMillis(),
          accounting.MAX_VALUE
        )
        val deploy2 =
          ProtoUtil.termDeploy(
            mkTerm("@3!([1,2,3,4])").toOption.get,
            System.currentTimeMillis(),
            accounting.MAX_VALUE
          )
        val deploy3 =
          ProtoUtil.termDeploy(
            mkTerm("for(@x <- @0) { @4!(x.toByteArray()) }").toOption.get,
            System.currentTimeMillis(),
            accounting.MAX_VALUE
          )
        for {
          dag   <- blockDagStorage.getRepresentation
          cost1 <- computeSingleProcessedDeploy(dag, deploy1)
          cost2 <- computeSingleProcessedDeploy(dag, deploy2)
          cost3 <- computeSingleProcessedDeploy(dag, deploy3)

          accCostsSep = cost1 ++ cost2 ++ cost3

          //cost within the block should be the same as sum of deploying all programs separately
          singleDeploy = Seq(deploy1, deploy2, deploy3)
          accCostBatch <- computeSingleProcessedDeploy(dag, singleDeploy: _*)
        } yield accCostBatch should contain theSameElementsAs (accCostsSep)
  }

  ignore should "return cost of deploying even if one of the programs withing the deployment throws an error" in
    pendingUntilFixed { //reference costs
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

        for {
          dag <- blockDagStorage.getRepresentation

          cost1 <- computeSingleProcessedDeploy(dag, deploy1)
          cost2 <- computeSingleProcessedDeploy(dag, deploy2)

          accCostsSep = cost1 ++ cost2

          deployErr = ProtoUtil.termDeploy(
            mkTerm("@3!(\"a\" + 3)").toOption.get,
            System.currentTimeMillis(),
            accounting.MAX_VALUE
          )
          batchDeploy  = Seq(deploy1, deploy2, deployErr)
          accCostBatch <- computeSingleProcessedDeploy(dag, batchDeploy: _*)
        } yield accCostBatch should contain theSameElementsAs (accCostsSep)
      }
    }

  "validateBlockCheckpoint" should "not return a checkpoint for an invalid block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val deploys = Vector(ByteString.EMPTY)
        .map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))
      val processedDeploys = deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))
      val invalidHash      = ByteString.EMPTY
      for {

        block            <- createBlock[Task](Seq.empty, deploys = processedDeploys, tsHash = invalidHash)
        dag              <- blockDagStorage.getRepresentation
        validateResult   <- validateBlockCheckpoint[Task](block, dag, runtimeManager)
        Right(stateHash) = validateResult
      } yield stateHash should be(None)
  }

  it should "return a checkpoint with the right hash for a valid block" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val deploys =
        Vector(
          ByteString.EMPTY
        ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis(), Integer.MAX_VALUE))

      for {
        dag1 <- blockDagStorage.getRepresentation
        deploysCheckpoint <- computeDeploysCheckpoint[Task](
                              Seq.empty,
                              deploys.map((_, ExecutionEffect())),
                              dag1,
                              runtimeManager
                            )
        Right((computedTsHash, processedDeploys)) = deploysCheckpoint
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

  ignore should "pass persistent produce test with causality" in withStorage {
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
      for {
        dag1 <- blockDagStorage.getRepresentation
        deploysCheckpoint <- computeDeploysCheckpoint[Task](
                              Seq.empty,
                              deploys.map((_, ExecutionEffect())),
                              dag1,
                              runtimeManager
                            )
        Right((computedTsHash, processedDeploys)) = deploysCheckpoint
        block <- createBlock[Task](
                  Seq.empty,
                  deploys = processedDeploys.map(ProcessedDeployUtil.fromInternal),
                  tsHash = computedTsHash
                )
        dag2           <- blockDagStorage.getRepresentation
        validateResult <- validateBlockCheckpoint[Task](block, dag2, runtimeManager)
        Right(tsHash)  = validateResult
      } yield tsHash should be(Some(computedTsHash))
  }

  ignore should "pass tests involving races" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      (0 to 10).toList.traverse_ { _ =>
        val deploys =
          Vector(
            """
            | contract @"loop"(@xs) = {
            |   match xs {
            |     [] => {
            |       for (@winner <- @"ch") {
            |         @"return"!(winner)
            |       }
            |     }
            |     [first, ...rest] => {
            |       @"ch"!(first) | @"loop"!(rest)
            |     }
            |   }
            | } | @"loop"!(["a","b","c","d"])
            |""".stripMargin
          ).map(
            s =>
              ProtoUtil.sourceDeploy(
                ByteString.copyFromUtf8(s),
                System.currentTimeMillis(),
                Integer.MAX_VALUE
            )
          )

        for {
          dag1 <- blockDagStorage.getRepresentation
          deploysCheckpoint <- computeDeploysCheckpoint[Task](
                                Seq.empty,
                                deploys,
                                dag1,
                                runtimeManager
                              )
          Right((computedTsHash, processedDeploys)) = deploysCheckpoint
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

  ignore should "pass map update test" in withStorage {
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

        for {
          dag1 <- blockDagStorage.getRepresentation
          deploysCheckpoint <- computeDeploysCheckpoint[Task](
                                Seq.empty,
                                deploys,
                                dag1,
                                runtimeManager
                              )
          Right((computedTsHash, processedDeploys)) = deploysCheckpoint
          block <- createBlock[Task](
                    Seq.empty,
                    deploys = processedDeploys.map(ProcessedDeployUtil.fromInternal),
                    tsHash = computedTsHash
                  )
          dag2           <- blockDagStorage.getRepresentation
          validateResult <- validateBlockCheckpoint[Task](block, dag2, runtimeManager)
          Right(tsHash)  = validateResult
        } yield tsHash should be(Some(computedTsHash))
      }
  }
}
