package io.casperlabs.casper.util.execengine

import cats.Id
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockDagRepresentation, BlockStore}
import io.casperlabs.casper.consensus
import io.casperlabs.casper.consensus.{state, Block, Bond}
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtilTest._
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub.mock
import io.casperlabs.casper.util.execengine.Op.OpMap
import io.casperlabs.ipc
import io.casperlabs.casper.consensus.state._
import io.casperlabs.ipc.{
  DeployResult,
  ExecutionEffect,
  OpEntry,
  Transform,
  TransformEntry,
  TransformIdentity
}
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

  implicit val logEff: LogStub[Task] = new LogStub[Task]()

  implicit val executionEngineService: ExecutionEngineService[Task] =
    HashSetCasperTestNode.simpleEEApi[Task](Map.empty)

  "computeBlockCheckpoint" should "compute the final post-state of a chain properly" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val genesisDeploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis))
      val genesisDeploysCost =
        genesisDeploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b1Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis))
      val b1DeploysCost = b1Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b2Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis))
      val b2DeploysCost = b2Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b3Deploys = Vector(
        ByteString.EMPTY
      ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis))
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
        (_, _) = blockCheckpointB4
//          b3PostState          = runtimeManager.storageRepr(postb3StateHash).get
//
//          _      = b3PostState.contains("@{1}!(1)") should be(true)
//          _      = b3PostState.contains("@{1}!(15)") should be(true)
//          result = b3PostState.contains("@{7}!(7)") should be(true)
      } yield true
  }

  def computeSingleProcessedDeploy(
      deploy: Seq[consensus.Deploy],
      protocolVersion: state.ProtocolVersion = state.ProtocolVersion(1)
  )(
      implicit blockStore: BlockStore[Task],
      executionEngineService: ExecutionEngineService[Task]
  ): Task[Seq[ProcessedDeploy]] =
    for {
      blocktime <- Task.delay(System.currentTimeMillis)
      computeResult <- ExecEngineUtil
                        .computeDeploysCheckpoint[Task](
                          ExecEngineUtil.MergeResult.empty,
                          deploy,
                          blocktime,
                          protocolVersion
                        )
      DeploysCheckpoint(_, _, result, _, _, _, _) = computeResult
    } yield result

  "computeDeploysCheckpoint" should "aggregate the result of deploying multiple programs within the block" in withStorage {
    implicit blockStore =>
      _ =>
        // reference costs
        // deploy each Rholang program separately and record its cost
        val deploy1 = ProtoUtil.sourceDeploy(
          ByteString.copyFromUtf8("@1!(Nil)"),
          System.currentTimeMillis
        )
        val deploy2 =
          ProtoUtil.sourceDeploy(
            ByteString.copyFromUtf8("@3!([1,2,3,4])"),
            System.currentTimeMillis
          )
        val deploy3 =
          ProtoUtil.sourceDeploy(
            ByteString.copyFromUtf8("for(@x <- @0) { @4!(x.toByteArray()) }"),
            System.currentTimeMillis
          )
        for {
          proc1         <- computeSingleProcessedDeploy(Seq(deploy1))
          proc2         <- computeSingleProcessedDeploy(Seq(deploy2))
          proc3         <- computeSingleProcessedDeploy(Seq(deploy3))
          singleResults = proc1 ++ proc2 ++ proc3
          batchDeploy   = Seq(deploy1, deploy2, deploy3)
          batchResult   <- computeSingleProcessedDeploy(batchDeploy)
        } yield batchResult should contain theSameElementsAs singleResults
  }

  "computeDeploysCheckpoint" should "throw exception when EE Service Failed" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val failedExecEEService: ExecutionEngineService[Task] =
        mock[Task](
          (_, _) => new Throwable("failed when run genesis").asLeft.pure[Task],
          (_, _, _, _) => new Throwable("failed when exec deploys").asLeft.pure[Task],
          (_, _) => new Throwable("failed when commit transform").asLeft.pure[Task],
          (_, _, _) => SmartContractEngineError("unimplemented").asLeft.pure[Task],
          _ => Seq.empty[Bond].pure[Task],
          _ => Task.unit,
          _ => ().asRight[String].pure[Task]
        )

      val failedCommitEEService: ExecutionEngineService[Task] =
        mock[Task](
          (_, _) => new Throwable("failed when run genesis").asLeft.pure[Task],
          (_, _, deploys, _) =>
            Task.now {
              def getExecutionEffect(deploy: ipc.Deploy) = {
                val key =
                  Key(Key.Value.Hash(Key.Hash(ByteString.copyFromUtf8(deploy.toProtoString))))
                val transform     = Transform(Transform.TransformInstance.Identity(TransformIdentity()))
                val op            = ipc.Op(ipc.Op.OpInstance.Noop(io.casperlabs.ipc.NoOp()))
                val transforEntry = TransformEntry(Some(key), Some(transform))
                val opEntry       = OpEntry(Some(key), Some(op))
                ExecutionEffect(Seq(opEntry), Seq(transforEntry))
              }
              deploys
                .map(
                  d =>
                    DeployResult(
                      DeployResult.Value.ExecutionResult(
                        DeployResult.ExecutionResult(Some(getExecutionEffect(d)), None, 10)
                      )
                    )
                )
                .asRight[Throwable]
            },
          (_, _) => new Throwable("failed when commit transform").asLeft.pure[Task],
          (_, _, _) => SmartContractEngineError("unimplemented").asLeft.pure[Task],
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

      def step(index: Int, genesis: Block)(
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
          _ <- injectPostStateHash[Task](
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
        _       <- createBlock[Task](Seq(b1.blockHash, b2.blockHash), deploys = b3DeploysWithCost)
        _       <- step(0, genesis)
        _       <- step(1, genesis)
        r1 <- step(2, genesis)(failedExecEEService).onErrorHandleWith { ex =>
               Task.now {
                 ex.getMessage should startWith("failed when exec")
                 ByteString.copyFromUtf8("succeed")
               }
             }
        _ = r1 should be(ByteString.copyFromUtf8("succeed"))
        _ <- step(2, genesis)(failedCommitEEService).onErrorHandleWith { ex =>
              Task.now {
                ex.getMessage should startWith("failed when commit")
                ByteString.copyFromUtf8("succeed")
              }
            }
        _ = r1 should be(ByteString.copyFromUtf8("succeed"))
      } yield ()
  }

  "abstractMerge" should "do nothing in the case of zero or one candidates" in {
    val genesis = OpDagNode.genesis(Map(1     -> Op.Read))
    val tip     = OpDagNode.withParents(Map(2 -> Op.Write), List(genesis))

    implicit val order: Ordering[OpDagNode] = ExecEngineUtilTest.opDagNodeOrder
    val zeroResult = OpDagNode.merge(
      Vector(genesis)
    )
    val oneResult = OpDagNode.merge(
      Vector(tip)
    )

    zeroResult shouldBe ((Map.empty, Vector(genesis)))
    oneResult shouldBe ((Map.empty, Vector(tip)))
  }

  it should "correctly merge in the case of non-conflicting multiple blocks with shared history" in {
    val genesis = OpDagNode.genesis(Map(1 -> Op.Read))
    val aOps    = Map(2 -> Op.Write)
    val bOps    = Map(3 -> Op.Write)
    val cOps    = Map(4 -> Op.Add)

    val a = OpDagNode.withParents(aOps, List(genesis))
    val b = OpDagNode.withParents(bOps, List(genesis))
    val c = OpDagNode.withParents(cOps, List(genesis))

    implicit val order: Ordering[OpDagNode] = ExecEngineUtilTest.opDagNodeOrder
    val result                              = OpDagNode.merge(Vector(a, b, c))

    result shouldBe ((bOps + cOps, Vector(a, b, c)))
  }

  // test case courtesy of @afck: https://github.com/CasperLabs/CasperLabs/pull/385#discussion_r281099630
  it should "not consider effects of ancestors common to the presently chosen set and the candidate being merged" in {
    val genesis = OpDagNode.genesis(Map(1 -> Op.Read))
    val b1Ops   = Map(1 -> Op.Write)
    val a2Ops   = Map(2 -> Op.Write)
    val b2Ops   = Map(1 -> Op.Write)
    val c2Ops   = Map(4 -> Op.Add)

    val b1 = OpDagNode.withParents(b1Ops, List(genesis))
    val a2 = OpDagNode.withParents(a2Ops, List(b1))
    val b2 = OpDagNode.withParents(b2Ops, List(b1))
    val c2 = OpDagNode.withParents(c2Ops, List(genesis))

    // b1 and b2 both write to the same key, however since b1 is an ancestor of b2 and no other blocks
    // write to that key, this should not impact the merge

    implicit val order: Ordering[OpDagNode] = ExecEngineUtilTest.opDagNodeOrder
    val result                              = OpDagNode.merge(Vector(a2, b2, c2))

    result shouldBe ((b2Ops + c2Ops, Vector(a2, b2, c2)))
  }

  it should "correctly merge in the case of conflicting multiple blocks with shared history" in {
    val genesis = OpDagNode.genesis(Map(1 -> Op.Read))
    val aOps    = Map(2 -> Op.Write) // both a and b try to write to 2
    val bOps    = Map(2 -> Op.Write, 3 -> Op.Write)
    val cOps    = Map(4 -> Op.Add)

    val a = OpDagNode.withParents(aOps, List(genesis))
    val b = OpDagNode.withParents(bOps, List(genesis))
    val c = OpDagNode.withParents(cOps, List(genesis))

    implicit val order: Ordering[OpDagNode] = ExecEngineUtilTest.opDagNodeOrder
    val result                              = OpDagNode.merge(Vector(a, b, c))

    result shouldBe ((cOps, Vector(a, c)))
  }

  it should "correctly merge in the case of non-conflicting blocks with a more complex history" in {
    /*
     * The DAG looks like:
     *   j        k
     *   |     /     \
     *   g    h      i
     *   \    /\    /
     *    c d   e  f
     *     \/    \/
     *     a     b
     *      \    /
     *      genesis
     */

    val genesis = OpDagNode.genesis(Map(1 -> Op.Read))
    val ops: Map[Char, OpMap[Int]] = ('a' to 'k').zipWithIndex.map {
      case (char, index) => char -> Map(index -> Op.Write)
    }.toMap
    val a = OpDagNode.withParents(ops('a'), List(genesis))
    val b = OpDagNode.withParents(ops('b'), List(genesis))
    val c = OpDagNode.withParents(ops('c'), List(a))
    val d = OpDagNode.withParents(ops('d'), List(a))
    val e = OpDagNode.withParents(ops('e'), List(b))
    val f = OpDagNode.withParents(ops('f'), List(b))
    val g = OpDagNode.withParents(ops('g'), List(c))
    val h = OpDagNode.withParents(ops('h'), List(d, e))
    val i = OpDagNode.withParents(ops('i'), List(f))
    val j = OpDagNode.withParents(ops('j'), List(g))
    val k = OpDagNode.withParents(ops('k'), List(h, i))

    implicit val order: Ordering[OpDagNode] = ExecEngineUtilTest.opDagNodeOrder
    val result1                             = OpDagNode.merge(Vector(j, k))
    val result2                             = OpDagNode.merge(Vector(k, j))

    val nonFirstEffect1 = Vector('b', 'd', 'e', 'f', 'h', 'i', 'k').map(ops.apply).reduce(_ + _)
    val nonFirstEffect2 = Vector('c', 'g', 'j').map(ops.apply).reduce(_ + _)

    result1 shouldBe ((nonFirstEffect1, Vector(j, k)))
    result2 shouldBe ((nonFirstEffect2, Vector(k, j)))
  }

  it should "correctly merge in the case of conflicting blocks with a more complex history" in {
    /*
     * The DAG looks like:
     *  j   k        l
     *   \/   \      |
     *   g    h      i
     *   \    /\    /
     *    c d   e  f
     *     \/    \/
     *     a     b
     *      \    /
     *      genesis
     */

    val genesis = OpDagNode.genesis(Map(1 -> Op.Read))
    val ops: Map[Char, OpMap[Int]] =
      ('a' to 'l').zipWithIndex
        .map {
          case (char, index) => char -> Map(index -> Op.Write)
        }
        .toMap
        .updated('e', Map(100 -> Op.Write)) // both e and f try to update 100, so they conflict
        .updated('f', Map(100 -> Op.Add))

    val a = OpDagNode.withParents(ops('a'), List(genesis))
    val b = OpDagNode.withParents(ops('b'), List(genesis))
    val c = OpDagNode.withParents(ops('c'), List(a))
    val d = OpDagNode.withParents(ops('d'), List(a))
    val e = OpDagNode.withParents(ops('e'), List(b))
    val f = OpDagNode.withParents(ops('f'), List(b))
    val g = OpDagNode.withParents(ops('g'), List(c))
    val h = OpDagNode.withParents(ops('h'), List(d, e))
    val i = OpDagNode.withParents(ops('i'), List(f))
    val j = OpDagNode.withParents(ops('j'), List(g))
    val k = OpDagNode.withParents(ops('k'), List(g, h))
    val l = OpDagNode.withParents(ops('l'), List(i))

    implicit val order: Ordering[OpDagNode] = ExecEngineUtilTest.opDagNodeOrder
    val result1                             = OpDagNode.merge(Vector(j, k, l))
    val result2                             = OpDagNode.merge(Vector(j, l, k))
    val result3                             = OpDagNode.merge(Vector(k, j, l))
    val result4                             = OpDagNode.merge(Vector(k, l, j))

    val nonFirstEffect1 = Vector('b', 'd', 'e', 'h', 'k').map(ops.apply).reduce(_ + _)
    val nonFirstEffect2 = Vector('b', 'f', 'i', 'l').map(ops.apply).reduce(_ + _)
    val nonFirstEffect3 = ops('j')

    // cannot pick l and k together since l's history conflicts with k's history
    result1 shouldBe ((nonFirstEffect1, Vector(j, k)))
    result2 shouldBe ((nonFirstEffect2, Vector(j, l)))
    result3 shouldBe ((nonFirstEffect3, Vector(k, j)))
    result4 shouldBe result3
  }
}

object ExecEngineUtilTest {
  case class OpDagNode(ops: OpMap[Int], parents: List[OpDagNode], height: Int)
  object OpDagNode {
    val getParents: OpDagNode => List[OpDagNode]   = _.parents
    val getEffect: OpDagNode => Option[OpMap[Int]] = node => Some(node.ops)

    def genesis(ops: OpMap[Int]): OpDagNode =
      OpDagNode(ops, Nil, 0)

    def withParents(ops: OpMap[Int], parents: List[OpDagNode]): OpDagNode = {
      val maxParentHeight = parents.foldLeft(-1) { case (max, p) => math.max(max, p.height) }
      OpDagNode(ops, parents, maxParentHeight + 1)
    }

    def merge(
        candidates: Vector[OpDagNode]
    )(implicit order: Ordering[OpDagNode]): (OpMap[Int], Vector[OpDagNode]) = {
      val merged = ExecEngineUtil.abstractMerge[Id, OpMap[Int], OpDagNode, Int](
        candidates,
        getParents,
        getEffect,
        identity
      )

      merged match {
        case ExecEngineUtil.MergeResult.EmptyMerge =>
          throw new RuntimeException("No candidates were given to merge!")
        case ExecEngineUtil.MergeResult.Result(head, effect, tail) => (effect, head +: tail)
      }
    }
  }
  def opDagNodeOrder: Ordering[OpDagNode] =
    Ordering.by[OpDagNode, Int](_.height)

  val registry: String = """ """.stripMargin

  val other: String = """ """.stripMargin

  def prepareDeploys(v: Vector[ByteString], c: Long): Vector[ProcessedDeploy] = {
    val genesisDeploys =
      v.map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis))
    genesisDeploys.map(d => ProcessedDeploy().withDeploy(d).withCost(c))
  }
}
