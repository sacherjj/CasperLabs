package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import org.scalacheck.{Arbitrary, Gen}

trait ArbitraryConsensus {
  import Arbitrary.arbitrary

  def genBytes(length: Int): Gen[ByteString] =
    Gen.listOfN(length, Gen.choose(Byte.MinValue, Byte.MaxValue)).map { bytes =>
      ByteString.copyFrom(bytes.toArray)
    }

  val genHash = genBytes(20)
  val genKey  = genBytes(32)

  implicit val signatureGen: Arbitrary[Signature] = Arbitrary {
    for {
      alg <- Gen.oneOf("ed25519", "secp256k1")
      sig <- genHash
    } yield {
      Signature(alg, sig)
    }
  }

  implicit val blockGen: Arbitrary[Block] = Arbitrary {
    for {
      blockHash          <- genHash
      parentCount        <- Gen.choose(1, 5)
      parentHashes       <- Gen.listOfN(parentCount, genHash)
      deployCount        <- Gen.choose(1, 10)
      deploys            <- Gen.listOfN(deployCount, processedDeployGen.arbitrary)
      bodyHash           <- genHash
      preStateHash       <- genHash
      postStateHash      <- genHash
      validatorPublicKey <- genKey
      signature          <- arbitrary[Signature]
    } yield {
      Block()
        .withBlockHash(blockHash)
        .withHeader(
          Block
            .Header()
            .withParentHashes(parentHashes)
            .withState(Block.GlobalState(preStateHash, postStateHash, Seq.empty))
            .withDeployCount(deployCount)
            .withValidatorPublicKey(validatorPublicKey)
        )
        .withBody(Block.Body(deploys))
        .withSignature(signature)
    }
  }

  implicit val deployGen: Arbitrary[Deploy] = Arbitrary {
    for {
      deployHash       <- genHash
      accountPublicKey <- genKey
      nonce            <- arbitrary[Long]
      timestamp        <- arbitrary[Long]
      gasPrice         <- arbitrary[Long]
      bodyHash         <- genHash
      deployHash       <- genHash
      sessionCode      <- Gen.choose(0, 1024 * 500).flatMap(genBytes(_))
      paymentCode      <- Gen.choose(0, 1024 * 100).flatMap(genBytes(_))
      signature        <- arbitrary[Signature]
    } yield {
      Deploy()
        .withDeployHash(deployHash)
        .withHeader(
          Deploy
            .Header()
            .withAccountPublicKey(accountPublicKey)
            .withNonce(nonce)
            .withTimestamp(timestamp)
            .withGasPrice(gasPrice)
            .withBodyHash(bodyHash)
        )
        .withBody(
          Deploy
            .Body()
            .withSessionCode(sessionCode)
            .withPaymentCode(paymentCode)
        )
        .withSignature(signature)
    }
  }

  implicit val processedDeployGen: Arbitrary[Block.ProcessedDeploy] = Arbitrary {
    for {
      deploy  <- arbitrary[Deploy]
      isError <- arbitrary[Boolean]
      cost    <- arbitrary[Long]
    } yield {
      Block
        .ProcessedDeploy()
        .withDeploy(deploy)
        .withCost(cost)
        .withIsError(isError)
        .withErrorMessage(if (isError) "Kaboom!" else "")
    }
  }
}
