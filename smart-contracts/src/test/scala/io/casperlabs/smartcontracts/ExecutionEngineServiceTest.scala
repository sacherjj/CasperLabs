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
    PropertyCheckConfiguration(minSuccessful = 50)

  // Don't shrink the message size.
  // Default shrinker doesn't respect Gen's configuration and we may end up with message size smaller
  // than generated DeployItem.
  implicit val messageSizeShrink: Shrink[Int] = Shrink(_ => Stream.empty)

  "ExecutionEngineService.batchDeploysBySize" should "create execute requests in batches, limited by size" in forAll(
    sizeDeployItemList(1, 50),
    Gen.chooseNum(minMessageSize, maxMessageSize)
  ) {
    case (deployItems, msgSize) =>
      val baseRequest =
        ExecuteRequest(ByteString.EMPTY, 0L, protocolVersion = Some(ProtocolVersion(1L)))
      val batches =
        ExecutionEngineService.batchDeploysBySize(baseRequest, msgSize)(deployItems)
      batches.flatMap(_.deploys) should contain theSameElementsInOrderAs deployItems
      batches.foreach { request =>
        assert(request.serializedSize <= msgSize)
      }
  }

}
