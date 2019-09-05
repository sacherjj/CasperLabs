package io.casperlabs.casper

import cats.effect.Sync
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.DeploySelectionTest._
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.consensus.state.{Key, ProtocolVersion, Value}
import io.casperlabs.casper.util.execengine.{DeployEffects, ExecutionEngineServiceStub}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.comm.gossiping.ArbitraryConsensus
import io.casperlabs.ipc
import io.casperlabs.ipc.DeployResult.Value.{ExecutionResult, InvalidNonce}
import io.casperlabs.ipc._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.smartcontracts.ExecutionEngineService.CommitResult
import monix.eval.Task
import monix.eval.Task._
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicInt
import org.scalacheck.Gen
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

import scala.util.Either

@silent("is never used")
class DeploySelectionTest
    extends FlatSpec
    with Matchers
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {

  behavior of "DeploySelection"

  implicit val cc = ConsensusConfig(
    maxSessionCodeBytes = 500,
    maxPaymentCodeBytes = 500
  )

  val prestate        = ByteString.EMPTY
  val blocktime       = 0L
  val protocolVersion = ProtocolVersion(1L)

  val smallBlockSizeBytes = 5 * 1024

  val sampleDeploy        = sample(arbDeploy.arbitrary)
  val deploysInSmallBlock = smallBlockSizeBytes / sampleDeploy.serializedSize

  it should "stop consuming the stream when block size limit is reached" in forAll(
    Gen.listOfN(deploysInSmallBlock * 2, arbDeploy.arbitrary)
  ) { deploys =>
    {
      val expected = takeUnlessTooBig(smallBlockSizeBytes)(deploys)
      assert(expected.size < deploys.size)

      assert(deploysSize(expected) < (0.9 * smallBlockSizeBytes))

      val countedStream = CountedStream(fs2.Stream.fromIterator(deploys.toIterator))

      implicit val ee: ExecutionEngineService[Task] = eeExecMock(everythingCommutesExec _)

      val deploySelection: DeploySelection[Task] =
        DeploySelection.create[Task](smallBlockSizeBytes)

      val test = for {
        selected <- deploySelection
                     .select((prestate, blocktime, protocolVersion, countedStream.stream))
                     .map(_.map(_.deploy))
        // If we will consume whole stream, before condition is met, that's how many
        // elements we expect to pull from the stream.
        // Otherwise we expect one more pull than elements, because we have to pull
        // from the stream first before we decide we're full.
        expectedPulls = if (expected.size == deploys.size) {
          expected.size
        } else expected.size + 1
        _ <- Task.delay(assert(countedStream.getCount() == expectedPulls))
      } yield selected should contain theSameElementsAs expected

      test.unsafeRunSync
    }
  }

  it should "skip elements that don't commute and continue consuming the stream" in forAll(
    Gen.zip(
      Gen.listOfN(deploysInSmallBlock * 2, arbDeploy.arbitrary),
      Gen.listOfN(deploysInSmallBlock * 2, arbDeploy.arbitrary)
    )
  ) {
    case (conflicting, commuting) =>
      val stream = fs2.Stream
        .fromIterator(commuting.toIterator)
        .interleave(fs2.Stream.fromIterator(conflicting.toIterator))

      val cappedEffects = takeUnlessTooBig(smallBlockSizeBytes)(commuting)

      val counter                                   = AtomicInt(0)
      implicit val ee: ExecutionEngineService[Task] = eeExecMock(everyOtherCommutesExec(counter) _)
      val deploySelection: DeploySelection[Task] =
        DeploySelection.create[Task](smallBlockSizeBytes)

      val test = deploySelection
        .select((prestate, blocktime, protocolVersion, stream))
        .map(results => assert(results.map(_.deploy) == cappedEffects))

      test.unsafeRunSync
  }

  it should "consume the whole stream if all deploys commute and fit block size limit" in forAll(
    Gen
      .listOf(arbDeploy.arbitrary),
    Gen.chooseNum(1 * 1024 * 1024, 3 * 1024 * 1024)
  ) {
    case (deploys, maxBlockSizeMb) =>
      val cappedDeploys = takeUnlessTooBig(maxBlockSizeMb)(deploys)
      assert(cappedDeploys.size == deploys.size)

      implicit val ee: ExecutionEngineService[Task] = eeExecMock(everythingCommutesExec _)

      val deploySelection: DeploySelection[Task] =
        DeploySelection.create[Task](maxBlockSizeMb)

      val stream = fs2.Stream.fromIterator(cappedDeploys.toIterator)

      val test = deploySelection
        .select((prestate, blocktime, protocolVersion, stream))
        .map(_.map(_.deploy))
        .map(_ should contain theSameElementsAs cappedDeploys)

      test.unsafeRunSync
  }

  it should "push NoEffectsFailure elements to output stream" in forAll(
    Gen.listOfN(deploysInSmallBlock * 2, arbDeploy.arbitrary)
  ) { deploys =>
    val (invalid, effects) = {
      val (invalidIdx, effectsIdx) = deploys.toStream.zipWithIndex.partition {
        case (_, idx) => idx % 2 == 0
      }
      (invalidIdx.map(_._1.deployHash).toSet, effectsIdx.map(_._1).toList)
    }

    val cappedEffects = takeUnlessTooBig(smallBlockSizeBytes)(effects)

    val stream = fs2.Stream.fromIterator(deploys.toIterator)

    val counter                                   = AtomicInt(0)
    implicit val ee: ExecutionEngineService[Task] = eeExecMock(everyOtherInvalidDeploy(counter) _)
    val deploySelection: DeploySelection[Task] =
      DeploySelection.create[Task](smallBlockSizeBytes)

    val test = deploySelection
      .select((prestate, blocktime, protocolVersion, stream))
      .map(results => {
        // Partition the output stream to invalid deploys and commuting deploys.
        val (invalidDeploys, chosenDeploys) = results.partition(!_.isInstanceOf[DeployEffects])
        // Assert that all invalid deploys are a subset of the input set of invalid deploys.
        assert(invalidDeploys.map(_.deploy.deployHash).forall(invalid.contains(_)))
        // Assert that commuting deploys are as expected.
        assert(chosenDeploys.map(_.deploy) == cappedEffects)
      })

    test.unsafeRunSync
  }
}

@silent("is never used")
object DeploySelectionTest {
  case class CountedStream[F[_], A] private (
      private val counter: AtomicInt,
      private val streamPrivate: fs2.Stream[F, A]
  ) {
    def stream: fs2.Stream[F, A] = streamPrivate.map { el =>
      counter.increment()
      el
    }

    def getCount(): Int = counter.get()
  }

  object CountedStream {
    def apply[F[_], A](stream: fs2.Stream[F, A]): CountedStream[F, A] =
      CountedStream(AtomicInt(0), stream)
  }

  def toStreamChunked[F[_]: Sync, A](l: List[A], chunkSize: Int = 1): fs2.Stream[F, List[A]] =
    fs2.Stream.fromIterator[F, A](l.toIterator).chunkLimit(chunkSize).map(_.toList)

  // We need common key to generate conflicts.
  private val key = Key(
    Key.Value.Hash(Key.Hash(ByteString.EMPTY))
  )

  private val readTransform: (OpEntry, TransformEntry) = {
    val (op, transform) =
      Op(Op.OpInstance.Read(ReadOp())) ->
        Transform(Transform.TransformInstance.Identity(TransformIdentity()))

    val transformEntry = TransformEntry(Some(key), Some(transform))
    val opEntry        = OpEntry(Some(key), Some(op))
    (opEntry, transformEntry)
  }

  private val writeTransform: (OpEntry, TransformEntry) = {
    val (op, transform) =
      Op(Op.OpInstance.Write(WriteOp())) ->
        Transform(
          Transform.TransformInstance.Write(
            TransformWrite().withValue(Value(Value.Value.IntValue(10)))
          )
        )

    val transformEntry = TransformEntry(Some(key), Some(transform))
    val opEntry        = OpEntry(Some(key), Some(op))
    (opEntry, transformEntry)
  }

  // WARNING: `counter` is thread-safe but not synchronized
  private def everyOtherInvalidDeploy(counter: AtomicInt)(
      prestate: ByteString,
      blockTime: Long,
      deploys: Seq[DeployItem],
      version: ProtocolVersion
  ): Task[Either[Throwable, Seq[DeployResult]]] =
    Seq
      .fill(deploys.size) {
        val counterValue = counter.getAndIncrement()
        if (counterValue % 2 == 0) {
          DeployResult(InvalidNonce(ipc.DeployResult.InvalidNonce(0, 0)))
        } else {
          val (opEntry, transformEntry) = readTransform
          val effect                    = ExecutionEffect(Seq(opEntry), Seq(transformEntry))
          DeployResult(ExecutionResult(ipc.DeployResult.ExecutionResult(Some(effect), None, 10)))
        }
      }
      .asRight[Throwable]
      .pure[Task]

  // WARNING: `counter` is thread-safe but not synchronized
  private def everyOtherCommutesExec(counter: AtomicInt)(
      prestate: ByteString,
      blockTime: Long,
      deploys: Seq[DeployItem],
      version: ProtocolVersion
  ): Task[Either[Throwable, Seq[DeployResult]]] =
    Seq
      .fill(deploys.size) {
        val counterValue = counter.getAndIncrement()
        val (opEntry, transformEntry) =
          if (counterValue % 2 == 0) readTransform
          else writeTransform
        val effect = ExecutionEffect(Seq(opEntry), Seq(transformEntry))
        DeployResult(ExecutionResult(ipc.DeployResult.ExecutionResult(Some(effect), None, 10)))
      }
      .asRight[Throwable]
      .pure[Task]

  private def everythingCommutesExec(
      prestate: ByteString,
      blockTime: Long,
      deploys: Seq[DeployItem],
      version: ProtocolVersion
  ): Task[Either[Throwable, Seq[DeployResult]]] = {
    val (opEntry, transformEntry) = readTransform
    val effect                    = ExecutionEffect(Seq(opEntry), Seq(transformEntry))

    Task.now(
      deploys
        .map(
          _ =>
            DeployResult(
              ExecutionResult(ipc.DeployResult.ExecutionResult(Some(effect), None, 10))
            )
        )
        .asRight[Throwable]
    )
  }

  private def deploysSize(l: List[Deploy]): Int = l.map(_.serializedSize).sum

  private def raiseNotImplemented[F[_], A](implicit F: MonadThrowable[F]): F[A] =
    F.raiseError[A](new IllegalArgumentException("Not implemented in this mock."))

  private def assertMinSize(sizeLimitMb: Int)(l: List[Deploy]): Boolean =
    deploysSize(l) > (sizeLimitMb)

  private def assertMaxSize(sizeLimitMb: Int)(l: List[Deploy]): Boolean =
    deploysSize(l) < sizeLimitMb

  private def takeUnlessTooBig(sizeLimitMb: Int)(deploys: List[Deploy]): List[Deploy] =
    deploys
      .foldLeftM(List.empty[Deploy]) {
        case (state, element) =>
          val newState = element :: state
          if (deploysSize(newState) > (0.9 * sizeLimitMb)) {
            Left(state)
          } else {
            Right(newState)
          }
      }
      .fold(identity, identity)
      .reverse

  private def eeExecMock[F[_]: MonadThrowable](
      execFunc: (
          ByteString,
          Long,
          Seq[DeployItem],
          ProtocolVersion
      ) => F[Either[Throwable, Seq[DeployResult]]]
  ): ExecutionEngineService[F] = ExecutionEngineServiceStub.mock(
    (_, _) => raiseNotImplemented[F, Either[Throwable, GenesisResult]],
    execFunc,
    (_, _) => raiseNotImplemented[F, Either[Throwable, CommitResult]],
    (_, _, _) => raiseNotImplemented[F, Either[Throwable, Value]],
    _ => raiseNotImplemented[F, Either[String, Unit]]
  )
}
