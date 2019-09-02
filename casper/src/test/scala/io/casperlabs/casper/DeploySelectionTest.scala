package io.casperlabs.casper

import cats.implicits._
import cats.mtl.FunctorRaise
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.DeploySelectionTest._
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.consensus.state.{Key, ProtocolVersion, Value}
import io.casperlabs.casper.deploybuffer.{DeployBuffer, MockDeployBuffer}
import io.casperlabs.casper.util.execengine.ExecutionEngineServiceStub
import io.casperlabs.comm.gossiping.ArbitraryConsensus
import io.casperlabs.ipc
import io.casperlabs.ipc.DeployResult.Value.ExecutionResult
import io.casperlabs.ipc._
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Log
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.smartcontracts.ExecutionEngineService.CommitResult
import monix.eval.Task
import monix.eval.Task._
import org.scalacheck.Gen
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import monix.execution.Scheduler.Implicits.global
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.catscontrib.MonadThrowable
import monix.execution.atomic.AtomicInt

import scala.util.Either

@silent("is never used")
class DeploySelectionTest
    extends FlatSpec
    with Matchers
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {

  behavior of "DeploySelection"

  implicit val cc = ConsensusConfig()

  val prestate        = ByteString.EMPTY
  val blocktime       = 0L
  val protocolVersion = ProtocolVersion(1L)

  val smallBlockSizeMb = 2 * 1024 * 1024

  it should "stop consuming the stream when block size limit is reached" in forAll(
    Gen.listOf(arbDeploy.arbitrary).suchThat(assertMinSize(smallBlockSizeMb))
  ) { deploys =>
    {
      val expected = deploys
        .foldLeftM(List.empty[Deploy]) {
          case (state, element) =>
            val newState = element :: state
            if (deploysSize(newState) > (0.9 * smallBlockSizeMb)) {
              Left(state)
            } else {
              Right(newState)
            }
        }
        .fold(identity, identity)
        .reverse

      assert(deploysSize(expected) < (0.9 * smallBlockSizeMb))

      val countedStream = CountedStream(
        fs2.Stream
          .fromIterator[Task, Deploy](deploys.toIterator)
          .chunkLimit(1) // We don't want to return all elements in one chunk.
          .map(_.toList) // We want to see if we pull chunks lazily.
      )

      implicit val ee: ExecutionEngineService[Task] = eeExecMock(everythingCommutesExec _)

      val deploySelection: DeploySelection[Task] =
        DeploySelection.unsafeCreate[Task](smallBlockSizeMb)

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

  def everythingCommutesExec(
      prestate: ByteString,
      blockTime: Long,
      deploys: Seq[DeployItem],
      version: ProtocolVersion
  ): Task[Either[Throwable, Seq[DeployResult]]] = {
    val key = Key(
      Key.Value.Hash(Key.Hash(ByteString.EMPTY))
    )
    val (op, transform) =
      Op(Op.OpInstance.Read(ReadOp())) ->
        Transform(Transform.TransformInstance.Identity(TransformIdentity()))

    val transformEntry = TransformEntry(Some(key), Some(transform))
    val opEntry        = OpEntry(Some(key), Some(op))
    val effect         = ExecutionEffect(Seq(opEntry), Seq(transformEntry))

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

  def deploysSize(l: List[Deploy]): Int = l.map(_.serializedSize).sum

  def raiseNotImplemented[F[_], A](implicit F: MonadThrowable[F]): F[A] =
    F.raiseError[A](new IllegalArgumentException("Not implemented in this mock."))

  def assertMinSize(sizeLimitMb: Int)(l: List[Deploy]): Boolean =
    deploysSize(l) > (sizeLimitMb)

  def eeExecMock[F[_]: MonadThrowable](
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
