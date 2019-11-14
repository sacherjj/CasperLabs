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
    forAll(Gen.chooseNum(0, Long.MaxValue), Gen.listOfN(32, arbitrary[Byte]), arbitrary[Boolean]) {
      (l, d, t) =>
        val bs        = ByteString.copyFrom(d.toArray)
        val pageToken = DeployInfoPagination.createPageToken(Option((l, bs, t)))
        val (_, (parsedTimestamp: Long, parsedByteString: DeployHash, next: Boolean)) =
          DeployInfoPagination.parsePageToken(ListDeployInfosRequest().withPageToken(pageToken)).get
        parsedTimestamp shouldBe l
        parsedByteString shouldBe bs
        next shouldBe t
    }
  }
}
