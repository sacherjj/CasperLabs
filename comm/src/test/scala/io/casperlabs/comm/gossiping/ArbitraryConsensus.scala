package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import org.scalacheck.{Arbitrary, Gen}

trait ArbitraryConsensus {
  import Arbitrary.arbitrary

  def genBytes(length: Int): Gen[ByteString] =
    Gen.listOfN(length, arbitrary[Byte]).map { bytes =>
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
      summary <- arbitrary[BlockSummary]
      deploys <- Gen.listOfN(summary.getHeader.deployCount, arbitrary[Block.ProcessedDeploy])
    } yield {
      Block()
        .withBlockHash(summary.blockHash)
        .withHeader(summary.getHeader)
        .withBody(Block.Body(deploys))
        .withSignature(summary.getSignature)
    }
  }

  implicit val blockHeaderGen: Arbitrary[Block.Header] = Arbitrary {
    for {
      parentCount        <- Gen.choose(1, 5)
      parentHashes       <- Gen.listOfN(parentCount, genHash)
      deployCount        <- Gen.choose(1, 10)
      bodyHash           <- genHash
      preStateHash       <- genHash
      postStateHash      <- genHash
      validatorPublicKey <- genKey
    } yield {
      Block
        .Header()
        .withParentHashes(parentHashes)
        .withState(Block.GlobalState(preStateHash, postStateHash, Seq.empty))
        .withDeployCount(deployCount)
        .withValidatorPublicKey(validatorPublicKey)
        .withBodyHash(bodyHash)
    }
  }

  implicit val blockSummaryGen: Arbitrary[BlockSummary] = Arbitrary {
    for {
      blockHash <- genHash
      header    <- arbitrary[Block.Header]
      signature <- arbitrary[Signature]
    } yield {
      BlockSummary()
        .withBlockHash(blockHash)
        .withHeader(header)
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
