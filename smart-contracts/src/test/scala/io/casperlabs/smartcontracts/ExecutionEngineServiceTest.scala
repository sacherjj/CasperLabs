package io.casperlabs.smartcontracts

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.ipc.ExecuteRequest
import org.scalacheck.{Gen, Shrink}
import org.scalatest.{FlatSpec, Matchers}
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class ExecutionEngineServiceTest
    extends FlatSpec
    with Matchers
    with ArbitraryIpc
    with GeneratorDrivenPropertyChecks {

  val minMessageSize = 2 * 1024 * 1024  // 2MB
  val maxMessageSize = 16 * 1024 * 1024 // 16MB

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = 10)

  // Default shrinker doesn't respect Gen's configuration and we may end up with message size smaller
  // than generated DeployItem.
  implicit def noShrink[T]: Shrink[T] = Shrink(_ => Stream.empty)

  "batchDeploys" should "create execute requests in batches, limited by size" in forAll(
    sizeDeployItemList(0, 50),
    Gen.chooseNum(minMessageSize, maxMessageSize),
    Gen.choose(1, 4)
  ) {
    case (deployItems, msgSize, parallelism) =>
      val baseRequest =
        ExecuteRequest(ByteString.EMPTY, 0L, protocolVersion = Some(ProtocolVersion(1)))
      val batches =
        ExecutionEngineService.batchDeploys(baseRequest, msgSize, parallelism)(deployItems)
      batches.flatMap(_.deploys) should contain theSameElementsInOrderAs deployItems
      batches.foreach { request =>
        request.serializedSize shouldBe <=(msgSize)
      }
      if (deployItems.size <= parallelism)
        batches.size shouldBe deployItems.size
      else
        batches.size shouldBe >=(parallelism)
  }

  it should "create as many batches as the target parallelism if possible" in forAll(
    sizeDeployItemList(0, 50),
    Gen.choose(1, 4)
  ) {
    case (deployItems, parallelism) =>
      val baseRequest =
        ExecuteRequest(ByteString.EMPTY, 0L, protocolVersion = Some(ProtocolVersion(1)))
      val batches =
        ExecutionEngineService.batchDeploys(baseRequest, Int.MaxValue, parallelism)(deployItems)
      if (deployItems.size <= parallelism)
        batches.size shouldBe deployItems.size
      else
        batches.size shouldBe parallelism
  }

  "groupSizes" should "create balanced groups" in {
    ExecutionEngineService.groupSizes(5, 4) shouldBe List(1, 1, 1, 2)
    ExecutionEngineService.groupSizes(10, 4) shouldBe List(2, 2, 3, 3)
    ExecutionEngineService.groupSizes(7, 3) shouldBe List(2, 2, 3)
    ExecutionEngineService.groupSizes(3, 5) shouldBe List(1, 1, 1)
  }

  it should "preserve the overall size" in forAll(
    Gen.choose(0, 100),
    Gen.choose(1, 4)
  ) {
    case (size, parallelism) =>
      ExecutionEngineService.groupSizes(size, parallelism).sum shouldBe size
  }

  it should "allocate the remainder evenly" in forAll(
    Gen.choose(1, 50),
    Gen.choose(1, 8)
  ) {
    case (size, parallelism) =>
      val sizes = ExecutionEngineService.groupSizes(size, parallelism)
      sizes.max - sizes.min shouldBe <=(1)
  }

  "groupElements" should "create groups with the given sizes" in {
    val items = List.range(0, 10)
    val sizes = List(2, 4, 3, 1, 5)
    ExecutionEngineService.groupElements(items, sizes) shouldBe List(
      List(0, 1),
      List(2, 3, 4, 5),
      List(6, 7, 8),
      List(9)
    )
  }

  it should "handle 0 sized groups" in {
    val items = List.range(0, 5)
    val sizes = List(2, 0, 3)
    ExecutionEngineService.groupElements(items, sizes) shouldBe List(
      List(0, 1),
      List(),
      List(2, 3, 4)
    )
  }
}
