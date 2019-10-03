package io.casperlabs.storage

import io.casperlabs.casper.consensus.Block
import io.casperlabs.casper.consensus.state.Key
import io.casperlabs.ipc.{Transform, TransformEntry, TransformIdentity}
import io.casperlabs.models.ArbitraryConsensus
import org.scalacheck._

import scala.collection.JavaConverters._

trait ArbitraryStorageData extends ArbitraryConsensus {
  import Arbitrary.arbitrary

  val transformsNum: Gen[Int] = Gen.choose(0, 10)

  implicit val transform: Arbitrary[TransformEntry] = Arbitrary {
    for {
      bs        <- genHash
      key       = Key(Key.Value.Hash(Key.Hash(bs)))
      transform = Transform(Transform.TransformInstance.Identity(TransformIdentity()))
    } yield TransformEntry(Some(key), Some(transform))
  }

  implicit def arbBlockMsgWithTransformFromBlock(
      implicit c: ConsensusConfig
  ): Arbitrary[BlockMsgWithTransform] = Arbitrary {
    for {
      block                 <- arbitrary[Block]
      blockMsgWithTransform <- genBlockMsgWithTransformFromBlock(block)
    } yield blockMsgWithTransform
  }

  def listOfBlockMsgWithTransform(min: Int, max: Int)(
      implicit c: ConsensusConfig
  ): Gen[List[BlockMsgWithTransform]] =
    for {
      n      <- Gen.choose(min, max)
      blocks <- Gen.listOfN(n, arbitrary[BlockMsgWithTransform])
    } yield blocks

  def genBlockMsgWithTransformDagFromGenesis(
      implicit c: ConsensusConfig
  ): Gen[List[BlockMsgWithTransform]] =
    for {
      blocks <- genBlockDagFromGenesis
      blockMsgsWithTransform <- Gen
                                 .sequence(blocks.map(genBlockMsgWithTransformFromBlock))
                                 .map(_.asScala.toList)
    } yield blockMsgsWithTransform

  def blockWithNewHashesGen(blockElements: List[Block]): Gen[List[Block]] =
    Gen.listOfN(blockElements.size, genHash).map { blockHashes =>
      blockElements.zip(blockHashes).map {
        case (b, hash) => b.withBlockHash(hash)
      }
    }

  private def genBlockMsgWithTransformFromBlock(b: Block): Gen[BlockMsgWithTransform] =
    for {
      n          <- transformsNum
      transforms <- Gen.listOfN(n, arbitrary[TransformEntry])
    } yield BlockMsgWithTransform(Option(b), transforms)
}
