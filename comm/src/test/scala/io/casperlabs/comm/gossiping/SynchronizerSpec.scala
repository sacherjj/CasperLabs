package io.casperlabs.comm.gossiping

import cats.implicits._
import com.google.protobuf.ByteString
import eu.timepit.refined._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.auto._
import eu.timepit.refined.numeric._
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus.{BlockSummary, GenesisCandidate}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping
import io.casperlabs.comm.gossiping.Synchronizer.SyncError
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicInt
import monix.execution.schedulers.CanBlock.permit
import monix.tail.Iterant
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, Inspectors, Matchers, WordSpecLike}
import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

class SynchronizerSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {
  import SynchronizerSpec._

  override implicit val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 25, workers = 1)

  implicit val consensusConfig: ConsensusConfig = ConsensusConfig(dagDepth = 5, dagWidth = 5)

  def genPositiveInt(min: Int, max: Int): Gen[Int Refined Positive] =
    Gen.choose(min, max).map(i => refineV[Positive](i).right.get)

  def genDoubleGreaterEqualOf1(max: Double): Gen[Double Refined GreaterEqual[W.`1.0`.T]] =
    Gen.choose(1.0, max).map(i => refineV[GreaterEqual[W.`1.0`.T]](i).right.get)

  "Synchronizer" when {
    "streamed DAG contains cycle" should {
      "return SyncError.Cycle" in forAll(genPartialDagFromTips) { dag =>
        log.reset()
        val withCycleInParents =
          dag.updated(
            dag.size - 1,
            dag.last.withHeader(dag.last.getHeader.withParentHashes(Seq(dag.head.blockHash)))
          )
        val withCycleInJustifications =
          dag.updated(
            dag.size - 1,
            dag.last.withHeader(
              dag.last.getHeader
                .withJustifications(Seq(Justification(ByteString.EMPTY, dag.head.blockHash)))
            )
          )

        def test(cycledDag: Vector[BlockSummary]): Unit = TestFixture(cycledDag)() {
          (synchronizer, _, _) =>
            synchronizer.syncDag(Node(), Set(dag.head.blockHash)) foreachL { dagOrError =>
              dagOrError.isLeft shouldBe true
              dagOrError.left.get shouldBe an[SyncError.Cycle]
              dagOrError.left.get
                .asInstanceOf[SyncError.Cycle]
                .summary shouldBe cycledDag.last
            }
        }
        test(withCycleInParents)
        test(withCycleInJustifications)
      }
    }
    "streamed DAG is too deep" should {
      "return SyncError.TooDeep" in forAll(
        genPartialDagFromTips,
        genPositiveInt(1, consensusConfig.dagDepth - 1)
      ) { (dag, n) =>
        log.reset()
        TestFixture(dag)(maxPossibleDepth = n) { (synchronizer, _, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.TooDeep]
          }
        }
      }
    }
    "streamed DAG is abnormally wide" should {
      "return SyncError.TooWide" in forAll(
        genPartialDagFromTips(
          ConsensusConfig(dagDepth = 5, dagBranchingFactor = 4, dagWidth = Int.MaxValue)
        ),
        genDoubleGreaterEqualOf1(3)
      ) { (dag, n) =>
        log.reset()
        TestFixture(dag)(maxBranchingFactor = n, minBlockCountToCheckBranchingFactor = 0) {
          (synchronizer, _, _) =>
            synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
              dagOrError.isLeft shouldBe true
              dagOrError.left.get shouldBe an[SyncError.TooWide]
            }
        }
      }
    }
    "streamed summary can not be connected to initial block hashes" should {
      "return SyncError.Unreachable" in forAll(
        genPartialDagFromTips,
        arbBlockSummary.arbitrary
      ) { (dag, arbitraryBlock) =>
        log.reset()
        TestFixture(dag :+ arbitraryBlock)() { (synchronizer, _, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.Unreachable]
          }
        }
      }
    }
    "streamed summary is too far away from initial block hashes" should {
      "return SyncError.Unreachable" in forAll(
        genPartialDagFromTips,
        genPositiveInt(1, consensusConfig.dagDepth - 2)
      ) { (dag, n) =>
        log.reset()
        TestFixture(dag)(maxDepthAncestorsRequest = n) { (synchronizer, _, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.Unreachable]
          }
        }
      }
    }
    "streamed block summary can not be validated" should {
      "return SyncError.ValidationError" in forAll(
        genPartialDagFromTips
      ) { dag =>
        log.reset()
        val e = new RuntimeException("Boom!")
        TestFixture(dag)(validate = _ => Task.raiseError[Unit](e)) { (synchronizer, _, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.ValidationError]
            dagOrError.left.get.asInstanceOf[SyncError.ValidationError].reason shouldBe e
          }
        }
      }
    }
    "returned new part of DAG can not be connected to our DAG" should {
      "return SyncError.MissingDependencies" in forAll(
        genPartialDagFromTips
      ) { dag =>
        log.reset()
        TestFixture(dag)(notInDag = _ => Task.now(true)) { (synchronizer, _, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe an[SyncError.MissingDependencies]
          }
        }
      }
    }
    "streaming is halted with error" should {
      "log error and return empty DAG" in forAll(
        genPartialDagFromTips
      ) { dag =>
        log.reset()
        val e = new RuntimeException("Boom!")
        TestFixture(dag)(error = e.some) { (synchronizer, _, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).attempt.foreachL { dagOrError =>
            dagOrError.isLeft shouldBe true
            dagOrError.left.get shouldBe e
            log.causes should have size 1
            log.causes.head shouldBe e
          }
        }
      }
    }
    "asked to sync DAG" should {
      "ignore branching factor checks until specified count-threshold is reached" in forAll(
        genPartialDagFromTips(
          ConsensusConfig(dagDepth = 3, dagBranchingFactor = 3, dagWidth = Int.MaxValue)
        ),
        genDoubleGreaterEqualOf1(2)
      ) { (dag, n) =>
        log.reset()
        TestFixture(dag)(maxBranchingFactor = n, minBlockCountToCheckBranchingFactor = Int.MaxValue) {
          (synchronizer, _, _) =>
            synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
              dagOrError.isRight shouldBe true
              dagOrError.right.get should contain allElementsOf dag
            }
        }
      }

      "include tips and justifications as known hashes" in forAll(
        genPartialDagFromTips,
        genHash,
        genHash
      ) { (dag, tip, justification) =>
        log.reset()
        TestFixture(dag)(tips = List(tip), justifications = List(justification)) {
          (synchronizer, _, knownHashes) =>
            synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { _ =>
              knownHashes should contain allElementsOf (tip :: justification :: Nil)
            }
        }
      }

      "iteratively traverse DAG" in forAll(
        genPartialDagFromTips
      ) { dag =>
        log.reset()
        val ancestorsDepthRequest: Int Refined Positive = 2
        val grouped = {
          val headGroup   = Vector(Vector(dag.head))
          val generations = dag.tail.grouped(consensusConfig.dagWidth).toVector
          val groups      = headGroup ++ generations
          groups.grouped(ancestorsDepthRequest).toVector.map(_.flatten)
        }
        val finalParents = dag.takeRight(consensusConfig.dagWidth).map(_.blockHash).toSet
        TestFixture(grouped: _*)(
          maxDepthAncestorsRequest = ancestorsDepthRequest,
          notInDag = bs => Task.now(!finalParents(bs))
        ) { (synchronizer, requestsCount, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isRight shouldBe true
            val d = dagOrError.right.get
            d should contain allElementsOf dag.dropRight(consensusConfig.dagWidth)
            requestsCount
              .get() shouldBe (grouped.size.toDouble / ancestorsDepthRequest).ceil.toInt + 1
          }
        }
      }

      "return DAG in topological order" in forAll(genPartialDagFromTips) { dag =>
        log.reset()
        val hashToSummary = dag.map(s => (s.blockHash, s)).toMap.lift

        def allAncestors(summary: BlockSummary): List[BlockSummary] = {
          def directAncestors(s: BlockSummary): List[BlockSummary] =
            s.getHeader.parentHashes
              .flatMap(p => hashToSummary(p).toList)
              .toList ::: s.getHeader.justifications
              .flatMap(j => hashToSummary(j.latestBlockHash))
              .toList

          @annotation.tailrec
          def loop(acc: List[BlockSummary], summaries: List[BlockSummary]): List[BlockSummary] =
            if (summaries.isEmpty) {
              acc
            } else {
              val ancestors = summaries.flatMap(directAncestors).distinct
              loop(acc ::: ancestors, ancestors)
            }
          loop(Nil, List(summary)).distinct
        }

        TestFixture(dag)() { (synchronizer, _, _) =>
          synchronizer.syncDag(Node(), Set(dag.head.blockHash)).foreachL { dagOrError =>
            dagOrError.isRight shouldBe true
            val d = dagOrError.right.get
            val hashToIndex =
              d.zipWithIndex.map { case (s, i) => (s.blockHash, i) }.toMap.withDefaultValue(-1)
            d should not be empty
            Inspectors.forAll(d.zipWithIndex) {
              case (summary, index) =>
                val ancestors = allAncestors(summary)
                ancestors.forall(a => hashToIndex(a.blockHash) < index) shouldBe true
            }
          }
        }
      }
    }
  }
}

object SynchronizerSpec {
  implicit var log: LogStub[Task] = new LogStub[Task]
  implicit val metrics            = new Metrics.MetricsNOP[Task]

  object MockBackend {
    def apply(
        mockTips: List[ByteString],
        mockJustifications: List[ByteString],
        mockNotInDag: ByteString => Task[Boolean],
        mockValidate: BlockSummary => Task[Unit]
    ): gossiping.SynchronizerImpl.Backend[Task] =
      new gossiping.SynchronizerImpl.Backend[Task] {
        def tips: Task[List[ByteString]]                     = Task.now(mockTips)
        def justifications: Task[List[ByteString]]           = Task.now(mockJustifications)
        def validate(blockSummary: BlockSummary): Task[Unit] = mockValidate(blockSummary)
        def notInDag(blockHash: ByteString): Task[Boolean]   = mockNotInDag(blockHash)
      }
  }

  object MockGossipService {
    def apply(
        counter: AtomicInt,
        error: Option[RuntimeException],
        knownHashes: ListBuffer[ByteString],
        dags: Vector[BlockSummary]*
    ): Task[GossipService[Task]] =
      Task.now {
        new GossipService[Task] {
          def newBlocks(request: NewBlocksRequest): Task[NewBlocksResponse] = ???
          def streamAncestorBlockSummaries(
              request: StreamAncestorBlockSummariesRequest
          ): Iterant[Task, BlockSummary] = {
            request.knownBlockHashes.foreach(h => knownHashes += h)
            error.fold(
              Iterant.fromSeq[Task, BlockSummary](
                dags.lift(counter.getAndIncrement()).getOrElse(Vector.empty)
              )
            ) { e =>
              Iterant.raiseError(e)
            }
          }

          def streamDagTipBlockSummaries(
              request: StreamDagTipBlockSummariesRequest
          ): Iterant[Task, BlockSummary] = ???
          def streamBlockSummaries(
              request: StreamBlockSummariesRequest
          ): Iterant[Task, BlockSummary]                                                       = ???
          def getBlockChunked(request: GetBlockChunkedRequest): Iterant[Task, Chunk]           = ???
          def getGenesisCandidate(request: GetGenesisCandidateRequest): Task[GenesisCandidate] = ???
          def addApproval(request: AddApprovalRequest): Task[Unit]                             = ???
        }
      }
  }

  object TestFixture {
    def apply(dags: Vector[BlockSummary]*)(
        maxPossibleDepth: Int Refined Positive = Int.MaxValue,
        maxBranchingFactor: Double Refined GreaterEqual[W.`1.0`.T] = Double.MaxValue,
        maxDepthAncestorsRequest: Int Refined Positive = Int.MaxValue,
        minBlockCountToCheckBranchingFactor: Int Refined NonNegative = Int.MaxValue,
        validate: BlockSummary => Task[Unit] = _ => Task.unit,
        notInDag: ByteString => Task[Boolean] = _ => Task.now(false),
        error: Option[RuntimeException] = None,
        tips: List[ByteString] = Nil,
        justifications: List[ByteString] = Nil
    )(test: (Synchronizer[Task], AtomicInt, ListBuffer[ByteString]) => Task[Unit]): Unit = {
      val requestsCounter = AtomicInt(0)
      val knownHashes     = ListBuffer.empty[ByteString]

      val synchronizer = new SynchronizerImpl[Task](
        connectToGossip = _ => MockGossipService(requestsCounter, error, knownHashes, dags: _*),
        backend = MockBackend(tips, justifications, notInDag, validate),
        maxPossibleDepth = maxPossibleDepth,
        minBlockCountToCheckBranchingFactor = minBlockCountToCheckBranchingFactor,
        maxBranchingFactor = maxBranchingFactor,
        maxDepthAncestorsRequest = maxDepthAncestorsRequest
      )
      test(synchronizer, requestsCounter, knownHashes).runSyncUnsafe(5.seconds)
    }
  }
}
