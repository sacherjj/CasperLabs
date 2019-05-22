package io.casperlabs.casper

import com.google.protobuf.ByteString
import org.scalatest._
import io.casperlabs.comm.gossiping.ArbitraryConsensus
import org.scalatest.prop.GeneratorDrivenPropertyChecks.{forAll, PropertyCheckConfiguration}

class LegacyConversionsTest extends FlatSpec with ArbitraryConsensus with Matchers {

  implicit val propCheckConfig = PropertyCheckConfiguration(minSuccessful = 100)

  implicit val consensusConfig =
    ConsensusConfig(maxSessionCodeBytes = 50, maxPaymentCodeBytes = 10)

  "LegacyConversions" should "convert correctly between old and new blocks" in {
    forAll { (orig: consensus.Block) =>
      // Some fields are not supported by the legacy one.
      val comp =
        orig
          .withBody(
            orig.getBody.withDeploys(orig.getBody.deploys.map { pd =>
              pd.withDeploy(
                  pd.getDeploy
                    .withDeployHash(ByteString.EMPTY)
                    .withHeader(pd.getDeploy.getHeader.withBodyHash(ByteString.EMPTY))
                )
                .withErrorMessage("")
            })
          )
      val conv = LegacyConversions.fromBlock(comp)
      val back = LegacyConversions.toBlock(conv)
      back.toProtoString shouldBe comp.toProtoString
    }
  }

  it should "preserve genesis" in {
    forAll { (orig: consensus.Block) =>
      val genesis =
        orig
          .withBody(consensus.Block.Body())
          .clearSignature
      val conv = LegacyConversions.fromBlock(genesis)
      val back = LegacyConversions.toBlock(conv)
      back.toProtoString shouldBe genesis.toProtoString
    }
  }

}
