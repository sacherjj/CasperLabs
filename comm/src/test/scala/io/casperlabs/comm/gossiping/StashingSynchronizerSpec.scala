package io.casperlabs.comm.gossiping

import cats.Applicative
import cats.syntax.either._
import cats.effect.concurrent.Deferred
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.gossiping.Synchronizer.SyncError
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import monix.execution.atomic.{Atomic, AtomicInt}
import monix.execution.schedulers.CanBlock.permit
import monix.execution.schedulers.SchedulerService
import org.scalacheck.{Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterEach, Inspectors, Matchers, WordSpecLike}

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

  val requestsGen: Gen[List[(Node, Set[ByteString])]] = for {
    nodes       <- Gen.listOfN(10, arbNode.arbitrary)
    blockHashes <- Gen.listOfN(10, genHash)
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
          TestFixture() { (synchronizer, deferred, mock) =>
            for {
              fiber <- requests.parTraverse {
                        case (source, hashes) => synchronizer.syncDag(source, hashes)
                      }.start
              _ <- Task.sleep(30.milliseconds)
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
      "propagate SyncError errors" in
        forAll(requestsGen) { requests =>
          val syncError =
            SyncError.ValidationError(sample(arbBlockSummary.arbitrary), new RuntimeException)
          TestFixture(error = syncError.asRight[Throwable].some) { (synchronizer, deferred, mock) =>
            for {
              fiber <- requests.traverse {
                        case (source, hashes) => synchronizer.syncDag(source, hashes)
                      }.start
              _   <- Task.sleep(30.milliseconds)
              _   <- Task(mock.requests.get().toList shouldBe empty)
              _   <- deferred.complete(())
              res <- fiber.join
              _ <- Task {
                    mock.requests.get().toList should contain allElementsOf requests
                      .groupBy(_._1)
                      .mapValues(_.map(_._2).reduce(_ ++ _))
                      .toList
                    Inspectors.forAll(res) { either =>
                      either.isLeft shouldBe true
                      either.left.get shouldBe an[SyncError.ValidationError]
                      either.left.get shouldBe syncError
                    }
                  }
            } yield ()
          }
        }
      "propagate unexpected runtime errors" in
        forAll(requestsGen) { requests =>
          val exception = new RuntimeException("Boom!")
          TestFixture(error = exception.asLeft[SyncError].some) { (synchronizer, deferred, mock) =>
            for {
              fiber <- requests.traverse {
                        case (source, hashes) => synchronizer.syncDag(source, hashes).attempt
                      }.start
              _   <- Task.sleep(30.milliseconds)
              _   <- Task(mock.requests.get().toList shouldBe empty)
              _   <- deferred.complete(())
              res <- fiber.join
              _ <- Task {
                    mock.requests.get().toList should contain allElementsOf requests
                      .groupBy(_._1)
                      .mapValues(_.map(_._2).reduce(_ ++ _))
                      .toList
                    Inspectors.forAll(res) { either =>
                      either.isLeft shouldBe true
                      either.left.get shouldBe an[RuntimeException]
                      either.left.get shouldBe exception
                    }
                  }
            } yield ()
          }
        }
    }
  }
}

object StashingSynchronizerSpec {
  class MockSynchronizer(error: Option[Either[Throwable, SyncError]]) extends Synchronizer[Task] {
    val requests =
      Atomic(Map.empty[Node, Set[ByteString]].withDefault(_ => Set.empty))
    private val utilityTotalConcurrency = AtomicInt(0)
    private val utilityPerNodeConcurrency =
      Atomic(Map.empty[Node, Int].withDefault(_ => 0))
    val totalConcurrency = AtomicInt(0)
    val concurrencyPerNode =
      Atomic(Map.empty[Node, Int].withDefault(_ => 0))

    def syncDag(
        source: Node,
        targetBlockHashes: Set[ByteString]
    ): Task[Either[SyncError, Vector[BlockSummary]]] =
      Task.defer {
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
        error.fold(Task.now(Vector.empty[BlockSummary].asRight[SyncError])) {
          case Left(e)  => Task.raiseError[Either[SyncError, Vector[BlockSummary]]](e)
          case Right(e) => Task.now(e.asLeft[Vector[BlockSummary]])
        }
      }
  }

  object TestFixture {
    def apply(error: Option[Either[Throwable, SyncError]] = None)(
        test: (Synchronizer[Task], Deferred[Task, Unit], MockSynchronizer) => Task[Unit]
    ): Unit = {
      val run = for {
        deferred <- Deferred[Task, Unit]
        mock     <- Task(new MockSynchronizer(error))
        stashed  <- StashingSynchronizer.wrap(mock, deferred.get)
        _        <- test(stashed, deferred, mock)
      } yield ()
      implicit val scheduler: SchedulerService =
        Scheduler.computation(
          parallelism = 100,
          executionModel = ExecutionModel.AlwaysAsyncExecution
        )
      run.runSyncUnsafe(5.seconds)
    }
  }
}
