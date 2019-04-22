package io.casperlabs.comm.gossiping

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus._
import io.casperlabs.comm.discovery.Node
import org.scalacheck.{Arbitrary, Gen, Shrink}
import scala.collection.JavaConverters._

object ArbitraryConsensus extends ArbitraryConsensus

trait ArbitraryConsensus {
  import Arbitrary.arbitrary

  case class ConsensusConfig(
      // Number of blocks in the DAG. 0 means no limit.
      dagSize: Int = 0,
      // Maximum size of code in blocks. Slow to generate.
      maxSessionCodeBytes: Int = 500 * 1024,
      maxPaymentCodeBytes: Int = 100 * 1024,
      minSessionCodeBytes: Int = 0,
      minPaymentCodeBytes: Int = 0
  )

  def genBytes(length: Int): Gen[ByteString] =
    Gen.listOfN(length, arbitrary[Byte]).map { bytes =>
      ByteString.copyFrom(bytes.toArray)
    }

  // A generators .sample.get can sometimes return None, but these examples have no reason to not generate a result,
  // so defend against that and retry if it does happen.
  def sample[T](g: Gen[T]): T = {
    def loop(i: Int): T = {
      assert(i > 0, "Should be able to generate a sample.")
      g.sample.fold(loop(i - 1))(identity)
    }
    loop(10)
  }

  val genHash = genBytes(32)
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

  implicit val arbApproval: Arbitrary[Approval] = Arbitrary {
    for {
      pk  <- genKey
      sig <- arbitrary[Signature]
    } yield Approval().withValidatorPublicKey(pk).withSignature(sig)
  }

  implicit val arbBond: Arbitrary[Bond] = Arbitrary {
    for {
      pk    <- genKey
      stake <- arbitrary[Long]
    } yield Bond().withValidatorPublicKey(pk).withStake(stake)
  }

  implicit def arbBlock(implicit c: ConsensusConfig): Arbitrary[Block] = Arbitrary {
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

  implicit def arbDeploy(implicit c: ConsensusConfig): Arbitrary[Deploy] = Arbitrary {
    for {
      deployHash       <- genHash
      accountPublicKey <- genKey
      nonce            <- arbitrary[Long]
      timestamp        <- arbitrary[Long]
      gasPrice         <- arbitrary[Long]
      bodyHash         <- genHash
      deployHash       <- genHash
      sessionCode      <- Gen.choose(0, c.maxSessionCodeBytes).flatMap(genBytes(_))
      paymentCode      <- Gen.choose(0, c.maxPaymentCodeBytes).flatMap(genBytes(_))
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
            .withSession(DeployCode().withCode(sessionCode))
            .withPayment(DeployCode().withCode(paymentCode))
        )
        .withSignature(signature)
    }
  }

  implicit def arbProcessedDeploy(implicit c: ConsensusConfig): Arbitrary[Block.ProcessedDeploy] =
    Arbitrary {
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
  private def genBlockFromSummary(summary: BlockSummary)(implicit c: ConsensusConfig): Gen[Block] =
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
  def genDag(implicit c: ConsensusConfig): Gen[Vector[BlockSummary]] = {
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
            .withRank(parents.map(_.getHeader.rank).max + 1)
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
        val nextTips     = stillTips ++ children
        val nextAcc      = acc ++ children
        c.dagSize match {
          case 0 if children.isEmpty           => Gen.const(nextAcc)
          case n if 0 < n && n <= nextAcc.size => Gen.const(nextAcc.take(n))
          case _                               => loop(nextAcc, nextTips)
        }
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

  def genBlockDag(implicit c: ConsensusConfig): Gen[Vector[Block]] =
    for {
      summaries <- genDag
      blocks    <- Gen.sequence(summaries.map(genBlockFromSummary))
    } yield blocks.asScala.toVector

  // It doesn't make sense to shrink DAGs because the default shrink
  // will most likely get rid of parents, and make any derived selections
  // invalid too.
  implicit val noShrinkBlockDag: Shrink[Vector[Block]]               = Shrink(_ => Stream.empty)
  implicit val noShrinkBlockSummaryDag: Shrink[Vector[BlockSummary]] = Shrink(_ => Stream.empty)
}
