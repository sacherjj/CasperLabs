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
          .withHeader(
            // Body hash would have to be a valid serialization from the 2 fields in the old message.
            orig.getHeader.withBodyHash(ByteString.EMPTY)
          )
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
      back shouldBe comp
    }
  }

}
