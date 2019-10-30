package io.casperlabs.node.api

import com.google.protobuf.ByteString
import io.casperlabs.node.api.casper.ListDeployInfosRequest
import io.casperlabs.storage.block.BlockStorage.DeployHash
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.{Matchers, PropSpec}
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class DeployInfoPaginationSpec extends PropSpec with Matchers with GeneratorDrivenPropertyChecks {
  property("decode after encode returns the original input") {
    forAll(Gen.chooseNum(0, Long.MaxValue), Gen.listOfN(32, arbitrary[Byte])) { (l, d) =>
      val bs        = ByteString.copyFrom(d.toArray)
      val pageToken = DeployInfoPagination.createNextPageToken(Option((l, bs)))
      val (_, (parsedTimestamp: Long, parsedByteString: DeployHash)) =
        DeployInfoPagination.parsePageToken(ListDeployInfosRequest().withPageToken(pageToken)).get
      parsedTimestamp shouldBe l
      parsedByteString shouldBe bs
    }
  }
}
