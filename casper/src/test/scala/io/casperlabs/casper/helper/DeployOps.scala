package io.casperlabs.casper.helper

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.ProcessedDeploy
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.validation.Validation.{MAX_DEPENDENCIES, MAX_TTL, MIN_TTL}
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.models.{ArbitraryConsensus, DeployImplicits}
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen

object DeployOps extends ArbitraryConsensus {
  implicit class ChangeDeployOps(deploy: Deploy) {
    // Clears previous signatures and adds a new one.
    def signSingle: Deploy = {
      val (sk, pk) = Ed25519.newKeyPair
      DeployImplicits.DeployOps(deploy.withApprovals(Seq.empty)).sign(sk, pk)
    }

    // Adds a new signature to already existing ones.
    def addSignature: Deploy = {
      val (sk, pk) = Ed25519.newKeyPair
      DeployImplicits.DeployOps(deploy).sign(sk, pk)
    }
    def withSessionCode(bytes: ByteString): Deploy =
      rehash(
        deploy.withBody(deploy.getBody.withSession(Deploy.Code().withWasm(bytes)))
      )
    def withPaymentCode(bytes: ByteString): Deploy =
      rehash(
        deploy.withBody(deploy.getBody.withPayment(Deploy.Code().withWasm(bytes)))
      )
    def withTimestamp(timestamp: Long): Deploy =
      rehash(
        deploy.withHeader(deploy.getHeader.withTimestamp(timestamp))
      )
    def withTtl(ttl: Int): Deploy =
      rehash(
        deploy.withHeader(deploy.getHeader.withTtlMillis(ttl))
      )
    def withDependencies(dependencies: Seq[ByteString]): Deploy =
      rehash(
        deploy.withHeader(deploy.getHeader.withDependencies(dependencies))
      )

    def withChainName(chainName: String): Deploy =
      rehash(
        deploy.withHeader(deploy.getHeader.withChainName(chainName))
      )

    def processed(cost: Long): ProcessedDeploy = ProcessedDeploy().withDeploy(deploy).withCost(cost)
  }

  private def rehash(deploy: Deploy): Deploy = {
    val header = deploy.getHeader.withBodyHash(ProtoUtil.protoHash(deploy.getBody))
    deploy.withDeployHash(ProtoUtil.protoHash(header)).withHeader(header)
  }

  def randomTooShortTTL(): Deploy = {
    implicit val c = ConsensusConfig()

    val genDeploy = for {
      d   <- arbitrary[Deploy]
      ttl <- Gen.choose(1, MIN_TTL - 1)
    } yield d.withTtl(ttl)

    sample(genDeploy)
  }

  def randomTooLongTTL(): Deploy = {
    implicit val c = ConsensusConfig()

    val genDeploy = for {
      d   <- arbitrary[Deploy]
      ttl <- Gen.choose(MAX_TTL + 1, Int.MaxValue)
    } yield d.withTtl(ttl)

    sample(genDeploy)
  }

  def randomTooManyDependencies(): Deploy = {
    implicit val c = ConsensusConfig()

    val genDeploy = for {
      d               <- arbitrary[Deploy]
      numDependencies <- Gen.chooseNum(MAX_DEPENDENCIES + 1, 2 * MAX_DEPENDENCIES)
      dependencies    <- Gen.listOfN(numDependencies, genHash)
    } yield d.withDependencies(dependencies)

    sample(genDeploy)
  }

  def randomInvalidDependency(): Deploy = {
    implicit val c = ConsensusConfig()

    val genDeploy = for {
      d          <- arbitrary[Deploy]
      nBytes     <- Gen.oneOf(Gen.chooseNum(0, 31), Gen.chooseNum(33, 100))
      dependency <- genBytes(nBytes)
    } yield d.withDependencies(List(dependency))

    sample(genDeploy)
  }

  def randomNonzeroTTL(): Deploy = {
    implicit val c = ConsensusConfig()

    val genDeploy = arbitrary[Deploy]
      .filter(
        d =>
          d.getHeader.ttlMillis > 0 && d.getHeader.timestamp < (Long.MaxValue - d.getHeader.ttlMillis)
      )

    sample(genDeploy)
  }
}
