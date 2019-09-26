package io.casperlabs.casper

import com.google.protobuf.ByteString
import io.casperlabs.comm.gossiping.ArbitraryConsensus
import io.casperlabs.crypto.hash.Blake2b256
import org.scalatest._
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
              val deploy = pd.getDeploy
              val header = deploy.getHeader
              val newHeader = header
                .withDependencies(List.empty)
                .withTtlMillis(0)
              pd.withErrorMessage("")
                .withDeploy(
                  deploy
                    .withHeader(
                      newHeader
                    )
                    .withDeployHash(ByteString.copyFrom(Blake2b256.hash(newHeader.toByteArray)))
                )
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
