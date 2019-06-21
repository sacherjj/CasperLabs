package io.casperlabs.blockstorage

import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.Justification
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.ipc._
import io.casperlabs.casper.consensus.state.Key
import io.casperlabs.storage.BlockMsgWithTransform
import org.scalacheck.Arbitrary.arbitrary
import org.scalacheck.Gen.listOfN
import org.scalacheck.{Arbitrary, Gen}

object blockImplicits {

  val blockHashGen: Gen[ByteString] = for {
    byteArray <- listOfN(32, arbitrary[Byte])
  } yield ByteString.copyFrom(byteArray.toArray)

  implicit val arbitraryHash: Arbitrary[ByteString] = Arbitrary(blockHashGen)

  val transform: Gen[TransformEntry] = for {
    bs        <- arbitrary[ByteString]
    key       = Key(Key.Value.Hash(Key.Hash(bs)))
    transform = Transform(Transform.TransformInstance.Identity(TransformIdentity()))
  } yield TransformEntry(Some(key), Some(transform))
  implicit val arbitraryTransformEntry: Arbitrary[TransformEntry] = Arbitrary(transform)

  val justificationGen: Gen[Justification] = for {
    latestBlockHash <- arbitrary[ByteString]
  } yield Justification().withLatestBlockHash(latestBlockHash)

  implicit val arbitraryJustification: Arbitrary[Justification] = Arbitrary(justificationGen)

  val deployGen: Gen[Deploy] = for {
    n          <- Gen.choose(0, 10)
    deployHash = ByteString.copyFromUtf8(s"$n")
  } yield Deploy().withDeployHash(deployHash)
  implicit val arbitraryDeploy: Arbitrary[Deploy] = Arbitrary(deployGen)

  val processedDeployGen: Gen[Block.ProcessedDeploy] = for {
    deploy <- arbitrary[Deploy]
  } yield Block.ProcessedDeploy().withDeploy(deploy).withCost(1L)

  implicit val arbitraryProcessedDeploy: Arbitrary[Block.ProcessedDeploy] =
    Arbitrary(processedDeployGen)

  val blockMessageGen: Gen[Block] =
    for {
      hash            <- arbitrary[ByteString]
      validator       <- arbitrary[ByteString]
      version         <- arbitrary[Long]
      timestamp       <- arbitrary[Long]
      parentsHashList <- arbitrary[Seq[ByteString]]
      justifications  <- arbitrary[Seq[Justification]]
      deploys         <- arbitrary[Seq[Block.ProcessedDeploy]]
    } yield Block()
      .withBlockHash(hash)
      .withHeader(
        Block
          .Header()
          .withParentHashes(parentsHashList)
          .withProtocolVersion(version)
          .withTimestamp(timestamp)
          .withValidatorPublicKey(validator)
      )
      .withBody(Block.Body().withDeploys(deploys))

  val blockMsgWithTransformGen: Gen[BlockMsgWithTransform] =
    for {
      transform <- arbitrary[Seq[TransformEntry]]
      block     <- blockMessageGen
    } yield BlockMsgWithTransform(Some(block), transform)

  val blockElementsGen: Gen[List[BlockMsgWithTransform]] =
    Gen.listOf(blockMsgWithTransformGen)

  val blockBatchesGen: Gen[List[List[BlockMsgWithTransform]]] =
    Gen.listOf(blockElementsGen)

  def blockElementsWithParentsGen: Gen[List[BlockMsgWithTransform]] =
    Gen.sized { size =>
      (0 until size).foldLeft(Gen.listOfN(0, blockMsgWithTransformGen)) {
        case (gen, _) =>
          for {
            blocks                                    <- gen
            blockMsgWithTransform                     <- blockMsgWithTransformGen
            BlockMsgWithTransform(Some(b), transform) = blockMsgWithTransform
            parents                                   <- Gen.someOf(blocks)
            parentHashes                              = parents.map(_.getBlockMessage.blockHash)
            newBlock                                  = b.withHeader(b.header.get.withParentHashes(parentHashes))
            newBlockWithTransform                     = BlockMsgWithTransform(Some(newBlock), transform)
          } yield newBlockWithTransform :: blocks
      }
    }

  def blockWithNewHashesGen(blockElements: List[Block]): Gen[List[Block]] =
    Gen.listOfN(blockElements.size, blockHashGen).map { blockHashes =>
      blockElements.zip(blockHashes).map {
        case (b, hash) => b.withBlockHash(hash)
      }
    }
}
