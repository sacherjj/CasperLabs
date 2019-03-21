package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import io.casperlabs.comm.discovery.Node
import org.scalacheck.{Arbitrary, Gen}
import scala.collection.JavaConverters._

object ArbitraryConsensus extends ArbitraryConsensus

trait ArbitraryConsensus {
  import Arbitrary.arbitrary

  def genBytes(length: Int): Gen[ByteString] =
    Gen.listOfN(length, arbitrary[Byte]).map { bytes =>
      ByteString.copyFrom(bytes.toArray)
    }

  val genHash = genBytes(20)
  val genKey  = genBytes(32)

  implicit val arbNode: Arbitrary[Node] = Arbitrary {
    for {
      id   <- genHash
      host <- Gen.listOfN(4, Gen.choose(0, 255)).map(xs => xs.mkString("."))
    } yield {
      Node(id, host, 40400, 40404)
    }
  }

  implicit val arbSignature: Arbitrary[Signature] = Arbitrary {
    for {
      alg <- Gen.oneOf("ed25519", "secp256k1")
      sig <- genHash
    } yield {
      Signature(alg, sig)
    }
  }

  implicit val arbBlock: Arbitrary[Block] = Arbitrary {
    for {
      summary <- arbitrary[BlockSummary]
      block   <- genBlockFromSummary(summary)
    } yield block
  }

  implicit val arbBlockHeader: Arbitrary[Block.Header] = Arbitrary {
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

  implicit val arbBlockSummary: Arbitrary[BlockSummary] = Arbitrary {
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

  implicit val arbDeploy: Arbitrary[Deploy] = Arbitrary {
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

  implicit val arbProcessedDeploy: Arbitrary[Block.ProcessedDeploy] = Arbitrary {
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

  // Used to generate a DAG of blocks if we need them.
  // It's backwards but then the DAG of summaries doesn't need to spend time generating bodies.
  private def genBlockFromSummary(summary: BlockSummary): Gen[Block] =
    for {
      deploys <- Gen.listOfN(summary.getHeader.deployCount, arbitrary[Block.ProcessedDeploy])
    } yield {
      Block()
        .withBlockHash(summary.blockHash)
        .withHeader(summary.getHeader)
        .withBody(Block.Body(deploys))
        .withSignature(summary.getSignature)
    }

  /** Grow a DAG by adding layers on top of the tips. */
  val genDag: Gen[Vector[BlockSummary]] = {
    def loop(
        acc: Vector[BlockSummary],
        tips: Set[BlockSummary]
    ): Gen[Vector[BlockSummary]] = {
      // Each child will choose some parents and some justifications from the tips.
      val genChild =
        for {
          parents        <- Gen.choose(1, tips.size).flatMap(Gen.pick(_, tips))
          justifications <- Gen.someOf(tips -- parents)
          block          <- arbitrary[BlockSummary]
        } yield {
          val header = block.getHeader
            .withParentHashes(parents.map(_.blockHash))
            .withJustifications(justifications.toSeq.map { j =>
              Block.Justification(
                latestBlockHash = j.blockHash,
                validatorPublicKey = j.getHeader.validatorPublicKey
              )
            })
          block.withHeader(header)
        }

      val genChildren =
        for {
          growth   <- Gen.frequency((1, 0), (2, 1), (3, 2), (3, 3), (2, 4), (1, 5))
          children <- Gen.listOfN(growth, genChild)
        } yield children

      // Continue until no children were generated or we reach maximum height.
      genChildren.flatMap { children =>
        val parentHashes = children.flatMap(_.getHeader.parentHashes).toSet
        val stillTips    = tips.filterNot(tip => parentHashes(tip.blockHash))
        if (children.isEmpty) Gen.const(acc)
        else loop(acc ++ children, stillTips ++ children)
      }
    }
    // Always start from the Genesis block.
    arbitrary[BlockSummary] map { summary =>
      summary.withHeader(
        summary.getHeader
          .withJustifications(Seq.empty)
          .withParentHashes(Seq.empty)
      )
    } flatMap { genesis =>
      loop(Vector(genesis), Set(genesis))
    }
  }

  val genBlockDag: Gen[Vector[Block]] = {
    for {
      summaries <- genDag
      blocks    <- Gen.sequence(summaries.map(genBlockFromSummary))
    } yield blocks.asScala.toVector
  }
}
