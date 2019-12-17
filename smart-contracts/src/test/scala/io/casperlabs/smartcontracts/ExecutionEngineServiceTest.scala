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

  // Don't shrink the message size.
  // Default shrinker doesn't respect Gen's configuration and we may end up with message size smaller
  // than generated DeployItem.
  implicit val messageSizeShrink: Shrink[Int] = Shrink(_ => Stream.empty)

  "ExecutionEngineService.batchDeploys" should "create execute requests in batches, limited by size" in forAll(
    sizeDeployItemList(1, 50),
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
    sizeDeployItemList(1, 50),
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

}
