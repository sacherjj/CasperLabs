package io.casperlabs.casper.util.execengine

import cats.{Id, MonadError}
import cats.data.NonEmptyList
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.state.Key.Hash
import io.casperlabs.casper.consensus.state._
import io.casperlabs.casper.consensus.{state, Block, Deploy}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper._
import io.casperlabs.casper.util.{CasperLabsProtocol, ProtoUtil}
import io.casperlabs.casper.util.execengine.ExecEngineUtil.{EECommitFun, EEExecFun}
import io.casperlabs.casper.util.execengine.ExecEngineUtilTest._
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub.mock
import io.casperlabs.casper.util.execengine.Op.OpMap
import io.casperlabs.casper.{consensus, DeploySelection}
import io.casperlabs.ipc
import io.casperlabs.ipc.DeployResult.ExecutionResult
import io.casperlabs.ipc.Op.OpInstance
import io.casperlabs.ipc._
import io.casperlabs.metrics.Metrics
import io.casperlabs.models.{ArbitraryConsensus, SmartContractEngineError}
import io.casperlabs.p2p.EffectsTestInstances.LogicalTime
import io.casperlabs.shared.{LogStub, Time}
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.deploy._
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.atomic.{AtomicInt, AtomicLong}
import org.scalatest.{Assertion, FlatSpec, Matchers}

class ExecEngineUtilTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with ArbitraryConsensus
    with StorageFixture {

  implicit val logEff = LogStub[Task]()

  implicit val executionEngineService: ExecutionEngineService[Task] =
    HashSetCasperTestNode.simpleEEApi[Task](Map.empty)

  "computeBlockCheckpoint" should "compute the final post-state of a chain properly" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage => _ =>
      val genesisDeploys = Vector(ProtoUtil.deploy(System.currentTimeMillis))
      val genesisDeploysCost =
        genesisDeploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b1Deploys     = Vector(ProtoUtil.deploy(System.currentTimeMillis()))
      val b1DeploysCost = b1Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b2Deploys     = Vector(ProtoUtil.deploy(System.currentTimeMillis()))
      val b2DeploysCost = b2Deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(1))

      val b3Deploys     = Vector(ProtoUtil.deploy(System.currentTimeMillis()))
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
        genesis         <- createAndStoreMessage[Task](Seq.empty, deploys = genesisDeploysCost)
        b1              <- createAndStoreMessage[Task](Seq(genesis.blockHash), deploys = b1DeploysCost)
        b2              <- createAndStoreMessage[Task](Seq(b1.blockHash), deploys = b2DeploysCost)
        b3              <- createAndStoreMessage[Task](Seq(b2.blockHash), deploys = b3DeploysCost)
        dag1            <- dagStorage.getRepresentation
        blockCheckpoint <- computeBlockCheckpointFromDeploys(genesis, dag1)
        _ <- injectPostStateHash[Task](
              0,
              genesis,
              blockCheckpoint.postStateHash,
              blockCheckpoint.deploysForBlock
            )
        dag2         <- dagStorage.getRepresentation
        b1Checkpoint <- computeBlockCheckpointFromDeploys(b1, dag2)
        _ <- injectPostStateHash[Task](
              1,
              b1,
              b1Checkpoint.postStateHash,
              b1Checkpoint.deploysForBlock
            )
        dag3 <- dagStorage.getRepresentation
        b2Checkpoint <- computeBlockCheckpointFromDeploys(
                         b2,
                         dag3
                       )
        _ <- injectPostStateHash[Task](
              2,
              b2,
              b2Checkpoint.postStateHash,
              b2Checkpoint.deploysForBlock
            )

        dag4 <- dagStorage.getRepresentation
        _ <- computeBlockCheckpointFromDeploys(
              b3,
              dag4
            )
//          b3PostState          = runtimeManager.storageRepr(postb3StateHash).get
//
//          _      = b3PostState.contains("@{1}!(1)") should be(true)
//          _      = b3PostState.contains("@{1}!(15)") should be(true)
//          result = b3PostState.contains("@{7}!(7)") should be(true)
      } yield true
  }

  def computeSingleProcessedDeploy(
      deploys: Seq[consensus.Deploy],
      protocolVersion: state.ProtocolVersion = state.ProtocolVersion(1)
  )(
      implicit executionEngineService: ExecutionEngineService[Task],
      deployStorage: DeployStorage[Task]
  ): Task[Seq[ProcessedDeploy]] =
    for {
      blocktime <- Task.delay(System.currentTimeMillis)
      implicit0(deploySelection: DeploySelection[Task]) = DeploySelection.create[Task](
        5 * 1024 * 1024
      )
      _ <- deployStorage.writer.addAsPending(deploys.toList)
      computeResult <- ExecEngineUtil
                        .computeDeploysCheckpoint[Task](
                          ExecEngineUtil.MergeResult.empty,
                          fs2.Stream.fromIterator[Task](deploys.toIterator),
                          blocktime,
                          protocolVersion,
                          rank = 0,
                          upgrades = Nil
                        )
      DeploysCheckpoint(_, _, _, result, _) = computeResult
    } yield result

  "computeDeploysCheckpoint" should "aggregate the result of deploying multiple programs within the block" in withStorage {
    _ => _ => implicit deployStorage =>
      _ =>
        // reference costs
        // deploy each Rholang program separately and record its cost
        val deploy1 = ProtoUtil.deploy(
          System.currentTimeMillis,
          ByteString.copyFromUtf8("deployA")
        )
        val deploy2 =
          ProtoUtil.deploy(
            System.currentTimeMillis,
            ByteString.copyFromUtf8("deployB")
          )
        val deploy3 =
          ProtoUtil.deploy(
            System.currentTimeMillis,
            ByteString.copyFromUtf8("deployC")
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

  it should "throw exception when EE Service Failed" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage => _ =>
      val failedExecEEService: ExecutionEngineService[Task] =
        mock[Task](
          (_) => new Throwable("failed when run genesis").asLeft.pure[Task],
          (_, _, _) => new Throwable("failed when run upgrade").asLeft.pure[Task],
          (_, _, _, _) => new Throwable("failed when exec deploys").asLeft.pure[Task],
          (_, _) => new Throwable("failed when commit transform").asLeft.pure[Task],
          (_, _, _) => SmartContractEngineError("unimplemented").asLeft.pure[Task]
        )

      val failedCommitEEService: ExecutionEngineService[Task] =
        mock[Task](
          (_) => new Throwable("failed when run genesis").asLeft.pure[Task],
          (_, _, _) => new Throwable("failed when run upgrade").asLeft.pure[Task],
          (_, _, deploys, _) =>
            Task.now {
              def getExecutionEffect(deploy: ipc.DeployItem) = {
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
                        DeployResult.ExecutionResult(
                          Some(getExecutionEffect(d)),
                          None,
                          Some(state.BigInt("10", bitWidth = 512))
                        )
                      )
                    )
                )
                .asRight[Throwable]
            },
          (_, _) => new Throwable("failed when commit transform").asLeft.pure[Task],
          (_, _, _) => SmartContractEngineError("unimplemented").asLeft.pure[Task]
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

      def step(index: Int)(
          implicit executionEngineService: ExecutionEngineService[Task]
      ) =
        for {
          b1  <- dagStorage.lookupByIdUnsafe(index)
          dag <- dagStorage.getRepresentation
          computeBlockCheckpointResult <- computeBlockCheckpointFromDeploys(
                                           b1,
                                           dag
                                         )
          postB1StateHash        = computeBlockCheckpointResult.postStateHash
          postB1ProcessedDeploys = computeBlockCheckpointResult.deploysForBlock
          _ <- injectPostStateHash[Task](
                index,
                b1,
                postB1StateHash,
                postB1ProcessedDeploys
              )
        } yield postB1StateHash

      for {
        genesis <- createAndStoreMessage[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createAndStoreMessage[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createAndStoreMessage[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        _ <- createAndStoreMessage[Task](
              Seq(b1.blockHash, b2.blockHash),
              deploys = b3DeploysWithCost
            )
        _ <- step(1)
        _ <- step(2)
        r1 <- step(2)(failedExecEEService).onErrorHandleWith { ex =>
               Task.now {
                 ex.getMessage should startWith("failed when exec")
                 ByteString.copyFromUtf8("succeed")
               }
             }
        _ = r1 should be(ByteString.copyFromUtf8("succeed"))
        _ <- step(2)(failedCommitEEService).onErrorHandleWith { ex =>
              Task.now {
                ex.getMessage should startWith("failed when commit")
                ByteString.copyFromUtf8("succeed")
              }
            }
        _ = r1 should be(ByteString.copyFromUtf8("succeed"))
      } yield ()
  }

  it should "include conflicting deploys in the result" in withStorage {
    _ => _ => implicit deployStorage => _ =>
      val nonConflictingDeploys = List.fill(5)(ProtoUtil.basicDeploy[Task]()).sequence
      // Deploys' transforms depend on their code and `basicDeploy` parses timestamp
      // as session code. By replicating the same deploy we get multiple deploys with the same effects.
      val conflictingDeploys =
        ProtoUtil.basicDeploy[Task]().map(List.fill(3)(_))

      for {
        deploys      <- Task.parMap2(nonConflictingDeploys, conflictingDeploys)(_ ++ _)
        blockDeploys <- computeSingleProcessedDeploy(deploys)
      } yield {
        blockDeploys.map(_.getDeploy) should contain theSameElementsAs (deploys)
      }
  }

  it should "use post-state hash and bonded validators values of the last deploy execution" in withStorage {
    _ => _ => implicit deployStorage => _ =>
      import cats.implicits._

      import monix.execution.Scheduler.Implicits.global

      val processedDeploys = {
        val nonConflictingDeploys = ProtoUtil.basicDeploy[Task]().runSyncUnsafe()
        val conflictingDeploys = {
          val deploy = ProtoUtil.basicDeploy[Task]().runSyncUnsafe()
          List.fill(3)(deploy)
        }

        assert(
          conflictingDeploys
            .forall(_.deployHash == conflictingDeploys.head.deployHash),
          // "Note: using the deploy hash to signal conflicting deploys is a convenience for this test.
          // In production identical deploys may or may not conflict (pending future research)
          // and different deploys also may or may not conflict (depending on what keys they operate on,
          // and what operations they perform).
          "All conflicting deploys should have the same deploy hash"
        )

        (nonConflictingDeploys +: conflictingDeploys).zipWithIndex.map {
          case (deploy, stage) => ProcessedDeploy(Some(deploy), stage = stage)
        }
      }

      // Prepare deploys' effects so that we know what EE stub will return.
      // Since effects are the thing that gets sent to EE.commit we can make an association
      // between deploy and CommitResult.
      val deployToEffect: Map[ByteString, (Int, Seq[TransformEntry])] =
        processedDeploys.map { deploy =>
          val deployHash = deploy.getDeploy.deployHash
          deployHash -> (deploy.stage -> Seq(
            TransformEntry()
              .withKey(Key().withHash(Hash(deployHash)))
              .withTransform(
                Transform()
                  .withWrite(TransformWrite().withValue(StoredValue().withAccount(Account())))
              )
          ))
        }.toMap

      // Mapping between deploy stage and its effects.
      val stageEffects = deployToEffect.values
        .map(Map(_))
        .map(_.mapValues(_.toSet))
        .foldLeft(Map.empty[Int, Set[TransformEntry]])(_ |+| _)

      def isLastCommit(transforms: Seq[TransformEntry]): Boolean =
        stageEffects.toList.sortBy(_._1).last._2 == transforms.toSet
      def isLastButOne(transforms: Seq[TransformEntry]): Boolean =
        stageEffects.toList.sortBy(_._1).dropRight(1).last._2 == transforms.toSet
      val lastPreStateHash  = ByteString.copyFromUtf8("TheLastPreState")
      val lastPostStateHash = ByteString.copyFromUtf8("TheLastPostState")
      val lastBondedValidators = Seq(
        io.casperlabs.casper.consensus
          .Bond(ByteString.copyFromUtf8("LastBondedValidator"), Some(BigInt("1000")))
      )

      def deployHashToKey(deployHash: ByteString) =
        Key().withHash(Hash(deployHash))
      val writeOp = io.casperlabs.ipc.Op(OpInstance.Write(WriteOp()))
      def writeOpEntry(deployHash: ByteString) =
        OpEntry().withKey(deployHashToKey(deployHash)).withOperation(writeOp)

      def deployToDeployResult(deployItem: DeployItem): DeployResult =
        DeployResult()
          .withExecutionResult(
            ExecutionResult().withEffects(
              ExecutionEffect()
                .withOpMap(Seq(writeOpEntry(deployItem.deployHash)))
                .withTransformMap(deployToEffect(deployItem.deployHash)._2)
            )
          )

      implicit val ee = mock[Task](
        _ => new Throwable("Keep calm.").asLeft.pure[Task],
        (_, _, _) => new Throwable("Keep calm.").asLeft.pure[Task],
        (_, _, deploys, _) =>
          Task.now(
            deploys.map(deployToDeployResult).asRight[Throwable]
          ),
        (_, transforms) =>
          Task {
            if (isLastButOne(transforms)) {
              ExecutionEngineService.CommitResult(lastPreStateHash, Seq.empty).asRight[Throwable]
            } else if (isLastCommit(transforms)) {
              ExecutionEngineService
                .CommitResult(lastPostStateHash, lastBondedValidators)
                .asRight[Throwable]
            } else {
              ExecutionEngineService
                .CommitResult(ByteString.copyFromUtf8("testPostState"), Seq.empty)
                .asRight[Throwable]
            }
          },
        (_, _, _) => new Throwable("Keep calm.").asLeft.pure[Task]
      )

      implicit val deploySelection = DeploySelection.create[Task](
        5 * 1024 * 1024
      )(Sync[Task], ee, fs2.Stream.Compiler.syncInstance[Task])

      ExecEngineUtil
        .computeDeploysCheckpoint[Task](
          ExecEngineUtil.MergeResult.empty,
          fs2.Stream.fromIterator[Task](processedDeploys.map(_.getDeploy).toIterator),
          0L,
          ProtocolVersion(1),
          rank = 0,
          upgrades = Nil
        )(Sync[Task], deployStorage, logEff, ee, deploySelection, Metrics[Task])
        .map { result =>
          assert(result.postStateHash == lastPostStateHash)
          assert(result.bondedValidators == lastBondedValidators)
        }
  }

  "effectsForBlock" should "extract block's effects properly" in withStorage {
    implicit bs => _ => _ => _ =>
      import io.casperlabs.catscontrib.effect.implicits._
      import cats.implicits._

      implicit val timeEff: Time[Id] = new LogicalTime[Id]
      implicit val cc                = ConsensusConfig()

      val processedDeploys = (0 to 5).toList.traverse { stage =>
        val deploysNum = scala.util.Random.nextInt(10)
        List
          .fill(deploysNum)(ProtoUtil.basicProcessedDeploy[Id].map(_.withStage(stage)))
          .sequence
      }.flatten

      val deployToEffect: Map[ByteString, (Int, Seq[TransformEntry])] =
        processedDeploys.map { deploy =>
          val deployHash = deploy.getDeploy.deployHash
          deployHash -> (deploy.stage -> Seq(
            TransformEntry()
              .withKey(Key().withHash(Hash(deployHash)))
              .withTransform(Transform().withAddI32(TransformAddInt32(deploy.stage)))
          ))
        }.toMap

      val stageEffects = deployToEffect.values
        .map(Map(_))
        .map(_.mapValues(_.toSet))
        .foldLeft(Map.empty[Int, Set[TransformEntry]])(_ |+| _)

      implicit val cl = CasperLabsProtocol.unsafe[Task]((0, ProtocolVersion(1), None))

      implicit val ee = mock[Task](
        _ => new Throwable("Keep calm.").asLeft.pure[Task],
        (_, _, _) => new Throwable("Keep calm.").asLeft.pure[Task],
        (_, _, deploys, _) =>
          Task.now(
            deploys
              .map { item =>
                DeployResult()
                  .withExecutionResult(
                    ExecutionResult().withEffects(
                      ExecutionEffect().withTransformMap(deployToEffect(item.deployHash)._2)
                    )
                  )
              }
              .asRight[Throwable]
          ),
        (_, _) =>
          Task.now(
            ExecutionEngineService
              .CommitResult(ByteString.copyFromUtf8("testPostState"), Seq.empty)
              .asRight[Throwable]
          ),
        (_, _, _) => new Throwable("Keep calm.").asLeft.pure[Task]
      )

      val block = sample(arbBlock.arbitrary).update(_.body.update(_.deploys := processedDeploys))
      for {
        blockEffects <- ExecEngineUtil
                         .effectsForBlock[Task](block, ByteString.EMPTY)(
                           Sync[Task],
                           ee,
                           bs,
                           cl
                         )
        _ <- Task {
              assert(
                stageEffects == blockEffects.effects.mapValues(_.toSet),
                "Expected the same per-stage effects."
              )
            }
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

  it should "filter redundant secondary parents from the output list" in {
    /*
     * The DAG looks like:
     *       i     j
     *     /  \    |
     *     f   g   h
     *    /\    \ /
     *    c d    e
     *     \/    |
     *     a     b
     *      \    /
     *      genesis
     */

    val genesis = OpDagNode.genesis(Map(1 -> Op.Read))
    val ops: Map[Char, OpMap[Int]] = ('a' to 'j').zipWithIndex
      .map {
        case (char, index) => char -> Map(index -> Op.Write)
      }
      .toMap
      .updated('a', Map(100 -> Op.Write)) // a, d, f all update the same key, but are sequential
      .updated('d', Map(100 -> Op.Write))
      .updated('f', Map(100 -> Op.Write))

    val a = OpDagNode.withParents(ops('a'), List(genesis))
    val b = OpDagNode.withParents(ops('b'), List(genesis))
    val c = OpDagNode.withParents(ops('c'), List(a))
    val d = OpDagNode.withParents(ops('d'), List(a))
    val e = OpDagNode.withParents(ops('e'), List(b))
    val f = OpDagNode.withParents(ops('f'), List(c, d))
    val g = OpDagNode.withParents(ops('g'), List(e))
    val h = OpDagNode.withParents(ops('h'), List(e))
    val i = OpDagNode.withParents(ops('i'), List(f, g))
    val j = OpDagNode.withParents(ops('j'), List(h))

    implicit val order: Ordering[OpDagNode] = ExecEngineUtilTest.opDagNodeOrder
    val allBlocks                           = Vector(j, i, h, g, f, e, d, c, b, a)
    val result1                             = OpDagNode.merge(allBlocks) // includes many redundant parents in input
    val result2                             = OpDagNode.merge(Vector(j, i)) // includes only DAG tips

    val nonFirstEffect = Vector('a', 'c', 'd', 'f', 'g', 'i').map(ops.apply).reduce(_ + _)

    // output does not include any redundant parents
    result1 shouldBe ((nonFirstEffect, Vector(j, i)))
    // output is the same as if the input had only included the DAG tips
    result2 shouldBe result1
  }

  it should "filter redundant secondary parents, but not main parent" in {
    // Dag looks like:
    // genesis <- a <- b
    val ops     = Map(1 -> Op.Read)
    val genesis = OpDagNode.genesis(ops)
    val a       = OpDagNode.withParents(ops, List(genesis))
    val b       = OpDagNode.withParents(ops, List(a))

    implicit val order: Ordering[OpDagNode] = ExecEngineUtilTest.opDagNodeOrder
    val redundantMainParentResult           = OpDagNode.merge(Vector(a, b))
    val redundantSecondaryParentResult      = OpDagNode.merge(Vector(b, a))

    // main parent is not filtered out even though it is redundant with b
    redundantMainParentResult shouldBe (ops -> Vector(a, b))
    // secondary parent is filtered out because it is redundant with the main
    redundantSecondaryParentResult shouldBe (Map.empty -> Vector(b))
  }

  abstract class SequentialExecFixture(
      initPrestate: ByteString = ByteString.copyFromUtf8("initPrestateHash"),
      blockTime: Long = 1L,
      protocolVersion: state.ProtocolVersion = state.ProtocolVersion(1)
  ) {
    implicit val mockDS: DeployStorage[Task] = MockDeployStorage.unsafeCreate[Task]()
    implicit val scheduler: Scheduler        = monix.execution.Scheduler.Implicits.global

    val deploys: NonEmptyList[Deploy] =
      NonEmptyList.fromListUnsafe(List.fill(10)(ProtoUtil.deploy(System.currentTimeMillis)))

    val eeExec: EEExecFun[Task]
    val eeCommit: EECommitFun[Task]

    def test[R](f: DeploysCheckpoint => R): R =
      testF[R](d => Task(f(d)))

    def testF[R](f: DeploysCheckpoint => Task[R]): R =
      ExecEngineUtil
        .execCommitSeqDeploys[Task](initPrestate, blockTime, protocolVersion, deploys)(
          eeExec,
          eeCommit
        )
        .flatMap(f)
        .runSyncUnsafe()

    def deployResults(transforms: Seq[TransformEntry]): Task[Either[Throwable, Seq[DeployResult]]] =
      Task(
        Either.right[Throwable, Seq[DeployResult]](
          Seq(
            DeployResult().withExecutionResult(
              ExecutionResult().withEffects(
                ExecutionEffect().withTransformMap(transforms)
              )
            )
          )
        )
      )

    val preconditionFailure: Task[Either[Throwable, Seq[DeployResult]]] =
      Task(
        Either.right[Throwable, Seq[DeployResult]](
          Seq(DeployResult().withPreconditionFailure(DeployResult.PreconditionFailure("")))
        )
      )

    def commitResult(
        postStateHash: ByteString,
        bondedValidators: Seq[consensus.Bond] = Seq.empty
    ): Task[Either[Throwable, ExecutionEngineService.CommitResult]] =
      Task(
        Either.right[Throwable, ExecutionEngineService.CommitResult](
          ExecutionEngineService.CommitResult(postStateHash, bondedValidators)
        )
      )

    def deployEffects = (deployHash: ByteString) => {
      val key =
        Key(Key.Value.Hash(Key.Hash(deployHash)))
      val transform      = Transform(Transform.TransformInstance.Identity(TransformIdentity()))
      val transformEntry = TransformEntry(Some(key), Some(transform))
      transformEntry
    }
  }

  "commitDeploysSequentially" should "start `stage` from 1 and increase monotonically" in new SequentialExecFixture {
    override val eeExec: EEExecFun[Task]     = executionEngineService.exec _
    override val eeCommit: EECommitFun[Task] = executionEngineService.commit _

    test { result =>
      result.deploysForBlock.map(_.stage).toList.foldLeft(0) {
        case (prevStage, stage) =>
          assert(stage == prevStage + 1)
          stage
      }
    }
  }

  it should "send one deploy at a time to the ExecutionEngine" in new SequentialExecFixture {
    override val eeExec: EEExecFun[Task] =
      (_, _, deploys, _) => {
        assert(deploys.size == 1)
        preconditionFailure
      }

    override val eeCommit: EECommitFun[Task] =
      (_, _, _) => commitResult(ByteString.EMPTY, Seq.empty)

    test { _ =>
      assert(true)
    }
  }

  it should "use post-state hash of executing a deploy as a pre-state hash of the next one" in {
    val initPrestate = ByteString.copyFromUtf8("initPrestate")
    new SequentialExecFixture(initPrestate) {
      val postStateHashes =
        (initPrestate :: deploys.map(_.deployHash)).zipWithIndex.toList.map(_.swap).toMap

      override val eeExec: EEExecFun[Task] =
        (_, _, deploys, _) => deployResults(Seq(deployEffects(deploys.head.deployHash)))

      val commitCounter = AtomicInt(0)
      override val eeCommit: EECommitFun[Task] =
        (prestate, _, _) => {
          val commitCount = commitCounter.getAndIncrement()
          assert(prestate == postStateHashes(commitCount))
          commitResult(postStateHashes(commitCount + 1))
        }

      test { _ =>
        assert(true)
      }
    }
  }

  it should "mark deploys as invalid if they fail execution with PreconditionFailure" in new SequentialExecFixture {
    override val eeExec: EEExecFun[Task] = (_, _, _, _) => preconditionFailure
    override val eeCommit: EECommitFun[Task] =
      (_, _, _) => commitResult(ByteString.EMPTY, Seq.empty)

    mockDS.writer.addAsPending(deploys.toList).runSyncUnsafe()
    assert(mockDS.reader.readPending.runSyncUnsafe().toSet == deploys.toList.toSet)

    testF { _ =>
      // All deploys should result in `PreconditionFailure` and be marked as discarded.
      mockDS.reader.readPending.map(l => assert(l.isEmpty))
    }
  }

  it should "return post-state hash and bonded validators of the last deploy execution" in new SequentialExecFixture {
    val lastPostStateHash = ByteString.copyFromUtf8("LastPostStateHash")
    val lastBondedValidators = Seq[consensus.Bond](
      consensus.Bond(ByteString.copyFromUtf8("lastbonded"), Some(BigInt("123456")))
    )

    override val eeExec: EEExecFun[Task] =
      (_, _, deploys, _) => deployResults(Seq(deployEffects(deploys.head.deployHash)))

    val commitCounter = AtomicLong(0)
    override val eeCommit: EECommitFun[Task] = (_, _, _) => {
      val commitCount = commitCounter.incrementAndGet()
      if (commitCount == deploys.size) {
        commitResult(lastPostStateHash, lastBondedValidators)
      } else {
        commitResult(ByteString.copyFromUtf8("different"), Seq.empty)
      }
    }

    test { result =>
      assert(
        result.postStateHash == lastPostStateHash && result.bondedValidators == lastBondedValidators
      )
    }
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
        NonEmptyList.fromListUnsafe(candidates.toList),
        getParents,
        getEffect,
        identity
      )

      merged match {
        case ExecEngineUtil.MergeResult.Result(head, effect, tail) => (effect, head +: tail)
      }
    }
  }
  def opDagNodeOrder: Ordering[OpDagNode] =
    Ordering.by[OpDagNode, Int](_.height)

  def prepareDeploys(contracts: Vector[ByteString], cost: Long): Vector[ProcessedDeploy] = {
    val deploys =
      contracts.map(ProtoUtil.deploy(System.currentTimeMillis, _))
    deploys.map(d => ProcessedDeploy().withDeploy(d).withCost(cost))
  }
}
