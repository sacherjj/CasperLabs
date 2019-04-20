package io.casperlabs.comm.gossiping

import cats.Applicative
import cats.effect.concurrent.Deferred
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import monix.execution.atomic.{Atomic, AtomicInt}
import monix.execution.schedulers.CanBlock.permit
import monix.execution.schedulers.SchedulerService
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}

import scala.concurrent.duration._

class StashingSynchronizerSpec
    extends WordSpecLike
    with Matchers
    with BeforeAndAfterEach
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {
  import cats.implicits._
  import StashingSynchronizerSpec._

  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  implicit val applicativeGen: Applicative[Gen] = new Applicative[Gen] {
    override def pure[A](x: A): Gen[A] = Gen.const(x)

    override def ap[A, B](ff: Gen[A => B])(fa: Gen[A]): Gen[B] =
      fa.flatMap(a => ff.map(f => f(a)))
  }

  val requestsGen: Gen[List[(Node, Set[ByteString])]] = for {
    nodes       <- Gen.listOfN(100, arbNode.arbitrary)
    blockHashes <- Gen.listOfN(100, genHash)
    hashesPerNode <- nodes.traverse(
                      n =>
                        for {
                          k      <- Gen.choose(1, blockHashes.size)
                          hashes <- Gen.pick(k, blockHashes)
                        } yield (n, hashes.toSet)
                    )
  } yield hashesPerNode

  "StashingSynchronizer" when {
    "approved" should {
      "run stashed requests in parallel but sequentially per node" in
        forAll(requestsGen) { requests =>
          TestFixture { (synchronizer, deferred, mock) =>
            for {
              fiber <- requests.parTraverse {
                        case (source, hashes) => synchronizer.syncDag(source, hashes)
                      }.start
              _ <- Task.sleep(500.milliseconds)
              _ <- Task(mock.requests.get().toList shouldBe empty)
              _ <- deferred.complete(())
              _ <- fiber.join
              _ <- Task {
                    mock.requests.get().toList should contain allElementsOf requests
                      .groupBy(_._1)
                      .mapValues(_.map(_._2).reduce(_ ++ _))
                      .toList
                  }
            } yield {
              mock.concurrencyPerNode.get().values.max shouldBe 1
              //Doesn't pass in CI because it doesn't have much cores
              //mock.totalConcurrency.get() should be > 1
            }
          }
        }
    }
  }
}

object StashingSynchronizerSpec {
  class MockSynchronizer extends Synchronizer[Task] {
    val requests =
      Atomic(Map.empty[Node, Set[ByteString]].withDefault(_ => Set.empty))
    private val utilityTotalConcurrency = AtomicInt(0)
    private val utilityPerNodeConcurrency =
      Atomic(Map.empty[Node, Int].withDefault(_ => 0))
    val totalConcurrency = AtomicInt(0)
    val concurrencyPerNode =
      Atomic(Map.empty[Node, Int].withDefault(_ => 0))

    def syncDag(source: Node, targetBlockHashes: Set[ByteString]): Task[Vector[BlockSummary]] =
      Task {
        utilityTotalConcurrency.increment()
        totalConcurrency.transform(math.max(_, utilityTotalConcurrency.get()))
        utilityTotalConcurrency.decrement()

        utilityPerNodeConcurrency.transform(map => map.updated(source, map(source) + 1))
        concurrencyPerNode.transform(
          map => map.updated(source, math.max(map(source), utilityPerNodeConcurrency.get()(source)))
        )
        utilityPerNodeConcurrency.transform(map => map.updated(source, map(source) - 1))
        requests.transform { rs =>
          rs.updated(source, rs(source) ++ targetBlockHashes)
        }
        Vector.empty
      }
  }

  object TestFixture {
    def apply(
        test: (Synchronizer[Task], Deferred[Task, Unit], MockSynchronizer) => Task[Unit]
    ): Unit = {
      val run = for {
        deferred <- Deferred[Task, Unit]
        mock     <- Task(new MockSynchronizer)
        stashed  <- StashingSynchronizer.wrap(mock, deferred.get)
        _        <- test(stashed, deferred, mock)
      } yield ()
      implicit val scheduler: SchedulerService =
        Scheduler.computation(
          parallelism = 100,
          executionModel = ExecutionModel.AlwaysAsyncExecution
        )
      run.runSyncUnsafe(10.seconds)
    }
  }
}
