package io.casperlabs.node.api

import com.google.protobuf.ByteString
import io.casperlabs.node.api.casper.ListDeployInfosRequest
import io.casperlabs.node.api.DeployInfoPagination.DeployInfoPageTokenParams
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen
import org.scalatest.{Matchers, PropSpec}
import org.scalatest.prop.GeneratorDrivenPropertyChecks

class DeployInfoPaginationSpec extends PropSpec with Matchers with GeneratorDrivenPropertyChecks {
  property("decode after encode returns the original input") {
    forAll(Gen.chooseNum(0, Long.MaxValue), Gen.listOfN(32, arbitrary[Byte]), arbitrary[Boolean]) {
      (l, d, t) =>
        val bs              = ByteString.copyFrom(d.toArray)
        val pageTokenParams = DeployInfoPageTokenParams(l, bs, t)
        val pageToken       = DeployInfoPagination.createPageToken(Some(pageTokenParams))
        val (_, p) =
          DeployInfoPagination.parsePageToken(ListDeployInfosRequest().withPageToken(pageToken)).get
        p should be(pageTokenParams)
    }
  }
}
