package io.casperlabs.casper

import cats.effect.Sync
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.DeploySelectionTest._
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.consensus.state.{
  BigInt,
  CLType,
  CLValue,
  Key,
  ProtocolVersion,
  StoredValue,
  Value
}
import io.casperlabs.casper.util.execengine.{
  DeployEffects,
  ExecutionEngineServiceStub,
  ProcessedDeployResult
}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.ipc
import io.casperlabs.ipc.DeployResult.Value.{ExecutionResult, PreconditionFailure}
import io.casperlabs.ipc._
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.models.bytesrepr._
import io.casperlabs.models.cltype
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.smartcontracts.ExecutionEngineService.CommitResult
import monix.eval.Task
import monix.eval.Task._
import monix.execution.Scheduler.Implicits.global
import monix.execution.atomic.AtomicInt
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest.prop.GeneratorDrivenPropertyChecks
import org.scalatest.{FlatSpec, Matchers}

import scala.util.Either
import io.casperlabs.casper.DeploySelection.DeploySelectionResult

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
  val protocolVersion = ProtocolVersion(1)

  val smallBlockSizeBytes                            = 5 * 1024
  val defaultMaxBlockCost: Option[scala.math.BigInt] = None

  val sampleDeploy        = sample(arbDeploy.arbitrary)
  val deploysInSmallBlock = smallBlockSizeBytes / sampleDeploy.serializedSize

  // ScalaCheck shrinker doesn't respect constraints on the generators.
  // There is no other way at the moment (`suchThat` used to work but it doesn't now).
  // see: https://gist.github.com/davidallsopp/f65d73fea8b5e5165fc3
  implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  it should "stop consuming the stream when block size limit is reached" in forAll(
    Gen.chooseNum(1, 100),
    arbDeploy.arbitrary,
    Gen.chooseNum(5 * 1024, 15 * 1024)
  ) {
    case (chunkSize, deploy, blockSizeBytes) =>
      val deploysInSmallBlock = blockSizeBytes / deploy.serializedSize
      val deploys             = List.fill(deploysInSmallBlock * 2)(deploy)
      val expected            = takeUnlessTooBig(blockSizeBytes)(deploys)
      assert(expected.size <= deploys.size)

      // Scale pull counts according to the chunk size.
      // Returns number of elements in all chunks consumed.
      def scaleWithChunkSize(pullsCount: Int): Int = {
        val base      = pullsCount / chunkSize
        val remainder = pullsCount % chunkSize
        chunkSize * (base + {
          if (remainder == 0) 0 else 1
        })
      }

      val expectedPulls =
        scaleWithChunkSize {
          // If we can fit it all we will consume the whole stream.
          if (expected.size == deploys.size) expected.size
          // If not then we have to make one additional pull to realize it's too big.
          else expected.size + 1
        }

      assert(deploysSize(expected) <= (0.9 * blockSizeBytes))

      val countedStream = CountedStream(fs2.Stream.fromIterator(deploys.toIterator))

      implicit val ee: ExecutionEngineService[Task] = eeExecMock(everythingCommutesExec _)

      val deploySelection: DeploySelection[Task] =
        DeploySelection.create[Task](minChunkSize = chunkSize)

      val test = for {
        selected <- deploySelection
                     .select(
                       prestate,
                       blocktime,
                       protocolVersion,
                       blockSizeBytes,
                       defaultMaxBlockCost,
                       countedStream.stream
                     )
                     .map(_.commuting.map(_.deploy))
        _ <- Task.delay(assert(scaleWithChunkSize(countedStream.getCount()) == expectedPulls))
      } yield selected should contain theSameElementsAs expected

      test.unsafeRunSync
  }

  it should "stop consuming the stream when block cost limit is reached" in forAll(
    arbDeploy.arbitrary,
    Gen.chooseNum(0, 5 * SomeCost.value.value.toInt)
  ) {
    case (deploy, maxBlockCost) =>
      val chunkSize  = 2
      val maxDeploys = maxBlockCost / SomeCost.value.value.toInt
      // It should pull `chunkSize` items at a time from the source,
      // which it will send in batches for execution, and then add
      // the results one by one until the cost exceed the maximum.
      // So if we know we want to find 3 deploys, it will execute 2+2,
      // but if we want just 2, it will still execute 2+2 and stop after.
      // Therefore we truncate the maxDeploys based on chunkSize and
      // allow one more batch.
      val maxPulls = maxDeploys - maxDeploys % chunkSize + chunkSize
      val deploys  = List.fill(maxDeploys * 5)(deploy)
      val expected = deploys.take(maxDeploys)

      val countedStream = CountedStream(fs2.Stream.fromIterator(deploys.toIterator))

      implicit val ee: ExecutionEngineService[Task] = eeExecMock(everythingCommutesExec _)

      val deploySelection: DeploySelection[Task] =
        DeploySelection.create[Task](minChunkSize = chunkSize)

      val test = for {
        selected <- deploySelection
                     .select(
                       prestate,
                       blocktime,
                       protocolVersion,
                       maxBlockSizeBytes = 5 * 1024 * 1024,
                       maxBlockCost = Option(scala.math.BigInt(maxBlockCost.toString)),
                       countedStream.stream
                     )
                     .map(_.commuting.map(_.deploy))
        _ <- Task.delay(assert(countedStream.getCount() <= maxPulls))
      } yield selected should contain theSameElementsAs expected

      test.unsafeRunSync
  }

  it should "skip elements that won't be included in a block (precondition failures)" +
    " and continue consuming the stream" in {
    val preconditionFailures = List.fill(deploysInSmallBlock * 2)(sample(arbDeploy.arbitrary))
    val commuting            = List.fill(deploysInSmallBlock * 2)(sample(arbDeploy.arbitrary))
    val stream = fs2.Stream
      .fromIterator(commuting.toIterator)
      .interleave(fs2.Stream.fromIterator(preconditionFailures.toIterator))

    val cappedEffects = takeUnlessTooBig(smallBlockSizeBytes)(commuting)

    val counter = AtomicInt(1)
    implicit val ee: ExecutionEngineService[Task] =
      eeExecMock(everyOtherInvalidDeploy(counter) _)

    val deploySelection: DeploySelection[Task] =
      DeploySelection.create[Task]()

    val test = deploySelection
      .select(
        prestate,
        blocktime,
        protocolVersion,
        smallBlockSizeBytes,
        defaultMaxBlockCost,
        stream
      )
      .map(results => {
        assert(results.commuting.map(_.deploy) == cappedEffects)
      })

    test.unsafeRunSync
  }

  it should "return conflicting deploys along the commuting ones if they fit the block size limit" in {
    val mixed          = List.fill(deploysInSmallBlock * 2)(sample(arbDeploy.arbitrary))
    val sizeLimitBytes = smallBlockSizeBytes * 4
    val cappedEffects  = takeUnlessTooBig(sizeLimitBytes)(mixed)

    val stream = fs2.Stream.fromIterator(mixed.toIterator)

    val counter                                   = AtomicInt(1)
    implicit val ee: ExecutionEngineService[Task] = eeExecMock(everyOtherCommutesExec(counter) _)

    val deploySelection = DeploySelection.create[Task]()

    // The very first WRITE doesn't conflict
    val expectedCommuting = cappedEffects.head +: cappedEffects.zipWithIndex
      .filter(_._2 % 2 == 1)
      .map(_._1)
    // Because first WRITE doesn't conflict we will get 1 less of them in conflicting section
    val expectedConflicting = cappedEffects.zipWithIndex.filter(_._2 % 2 == 0).map(_._1).tail

    val test = deploySelection
      .select(prestate, blocktime, protocolVersion, sizeLimitBytes, defaultMaxBlockCost, stream)
      .map {
        case DeploySelectionResult(commutingRes, conflictingRes, _) =>
          conflictingRes should contain theSameElementsAs expectedConflicting
          commutingRes.map(_.deploy) should contain theSameElementsAs expectedCommuting
      }

    test.unsafeRunSync
  }

  it should "consume the whole stream if all deploys commute and fit block size limit" in forAll(
    Gen
      .chooseNum(1 * 1024 * 1024, 3 * 1024 * 1024)
      .flatMap(
        size =>
          Gen
            .listOf(arbDeploy.arbitrary)
            .map(takeUnlessTooBig(size)(_))
            .tupleRight(size)
      )
  ) {
    case (deploys, maxBlockSizeBytes) =>
      val cappedDeploys = deploys

      implicit val ee: ExecutionEngineService[Task] = eeExecMock(everythingCommutesExec _)

      val deploySelection: DeploySelection[Task] =
        DeploySelection.create[Task]()

      val stream = fs2.Stream.fromIterator(cappedDeploys.toIterator)

      val test = deploySelection
        .select(
          prestate,
          blocktime,
          protocolVersion,
          maxBlockSizeBytes,
          defaultMaxBlockCost,
          stream
        )
        .map(_.commuting.map(_.deploy))
        .map(_ should contain theSameElementsAs cappedDeploys)

      test.unsafeRunSync
  }

  it should "push NoEffectsFailure elements to output stream" in {
    val deploys = List.fill(deploysInSmallBlock * 2)(sample(arbDeploy.arbitrary))
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
    val deploySelection: DeploySelection[Task]    = DeploySelection.create[Task]()

    val test = deploySelection
      .select(
        prestate,
        blocktime,
        protocolVersion,
        smallBlockSizeBytes,
        defaultMaxBlockCost,
        stream
      )
      .map {
        case DeploySelectionResult(chosenDeploys, _, invalidDeploys) => {
          // Assert that all invalid deploys are a subset of the input set of invalid deploys.
          assert(invalidDeploys.map(_.deploy.deployHash).forall(invalid.contains(_)))
          // Assert that commuting deploys are as expected.
          assert(chosenDeploys.map(_.deploy) == cappedEffects)
        }
      }

    test.unsafeRunSync
  }
}

@silent("is never used")
object DeploySelectionTest {
  val SomeCost = Some(BigInt("10", bitWidth = 512))

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
    fs2.Stream.fromIterator[F](l.toIterator).chunkLimit(chunkSize).map(_.toList)

  // We need common key to generate conflicts.
  private val key = Key(
    Key.Value.Hash(Key.Hash(ByteString.EMPTY))
  )

  // Random key to generate commuting READs
  private def readKey() = Key(
    Key.Value.Hash(Key.Hash(ByteString.copyFromUtf8(scala.util.Random.nextString(10))))
  )

  private def readTransform(): (OpEntry, TransformEntry) = {
    val (op, transform) =
      Op(Op.OpInstance.Read(ReadOp())) ->
        Transform(Transform.TransformInstance.Identity(TransformIdentity()))

    val rKey = readKey()

    val transformEntry = TransformEntry(Some(rKey), Some(transform))
    val opEntry        = OpEntry(Some(rKey), Some(op))
    (opEntry, transformEntry)
  }

  private val writeTransform: (OpEntry, TransformEntry) = {
    val tyI32: CLType = CLType(CLType.Variants.SimpleType(CLType.Simple.I32))
    val ten_bytes     = ToBytes.toBytes(10)
    val ten: CLValue  = CLValue(Some(tyI32), ByteString.copyFrom(ten_bytes))
    val value         = StoredValue().withClValue(ten)

    val (op, transform) =
      Op(Op.OpInstance.Write(WriteOp())) ->
        Transform(
          Transform.TransformInstance.Write(
            TransformWrite().withValue(value)
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
          DeployResult().withPreconditionFailure(ipc.DeployResult.PreconditionFailure("Nope."))
        } else {
          val (opEntry, transformEntry) = readTransform
          val effect                    = ExecutionEffect(Seq(opEntry), Seq(transformEntry))
          DeployResult(
            ExecutionResult(ipc.DeployResult.ExecutionResult(Some(effect), None, SomeCost))
          )
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
          if (counterValue % 2 == 0) readTransform()
          else writeTransform
        val effect = ExecutionEffect(Seq(opEntry), Seq(transformEntry))
        DeployResult(
          ExecutionResult(ipc.DeployResult.ExecutionResult(Some(effect), None, SomeCost))
        )
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
              ExecutionResult(ipc.DeployResult.ExecutionResult(Some(effect), None, SomeCost))
            )
        )
        .asRight[Throwable]
    )
  }

  private def deploysSize(l: List[Deploy]): Int = l.map(_.serializedSize).sum

  private def raiseNotImplemented[F[_], A](implicit F: MonadThrowable[F]): F[A] =
    F.raiseError[A](new IllegalArgumentException("Not implemented in this mock."))

  private def takeUnlessTooBig(sizeLimitBytes: Int)(deploys: List[Deploy]): List[Deploy] =
    deploys
      .foldLeftM(List.empty[Deploy]) {
        case (state, element) =>
          val newState = element :: state
          if (deploysSize(newState) > (0.9 * sizeLimitBytes)) {
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
    (_) => raiseNotImplemented[F, Either[Throwable, GenesisResult]],
    (_, _, _) => raiseNotImplemented[F, Either[Throwable, UpgradeResult]],
    execFunc,
    (_, _) => raiseNotImplemented[F, Either[Throwable, CommitResult]],
    (_, _, _) => raiseNotImplemented[F, Either[Throwable, cltype.StoredValue]]
  )
}
