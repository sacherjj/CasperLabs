package io.casperlabs.storage.block

import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.storage.block.BlockStorage.{BlockHash, DeployHash}
import io.casperlabs.storage.{
  ArbitraryStorageData,
  BlockMsgWithTransform,
  SQLiteFixture,
  SQLiteStorage
}
import monix.eval.Task
import org.scalacheck._
import org.scalactic.anyvals.PosInt
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.language.higherKinds
import scala.util.Random

@silent("match may not be exhaustive")
trait BlockStorageTest
    extends FlatSpecLike
    with Matchers
    with OptionValues
    with EitherValues
    with GeneratorDrivenPropertyChecks
    with BeforeAndAfterAll
    with ArbitraryStorageData {

  implicit val consensusConfig: ConsensusConfig = ConsensusConfig(
    maxSessionCodeBytes = 0,
    maxPaymentCodeBytes = 0
  )

  val blockElementsGen: Gen[List[BlockMsgWithTransform]] = listOfBlockMsgWithTransform(1, 3)
  val blockBatchesGen: Gen[List[List[BlockMsgWithTransform]]] = for {
    n       <- Gen.choose(1, 3)
    batches <- Gen.listOfN(n, blockElementsGen)
  } yield batches

  implicit override val generatorDrivenConfig: PropertyCheckConfiguration =
    PropertyCheckConfiguration(minSuccessful = PosInt(100))

  def withStorage[R](f: BlockStorage[Task] => Task[R]): R

  def checkAllHashes(storage: BlockStorage[Task], hashes: List[BlockHash]) =
    hashes.traverse { h =>
      storage.getByPrefix(Base16.encode(h.toByteArray)).map(h -> _.isDefined)
    } map { res =>
      Inspectors.forAll(res) {
        case (_, isDefined) => isDefined shouldBe true
      }
    }

  it should "return Some(message) on get for a published key and return Some(blockSummary) on getSummary" in {
    forAll(blockElementsGen) { blockStorageElements =>
      withStorage { storage =>
        val items = blockStorageElements
        for {
          _ <- items.traverse_(storage.put)
          _ <- items.traverse[Task, Assertion] { block =>
                storage.get(block.getBlockMessage.blockHash).map(_ shouldBe Some(block)) *>
                  storage
                    .getBlockSummary(block.getBlockMessage.blockHash)
                    .map(
                      _ shouldBe Some(
                        block.toBlockSummary
                      )
                    )
              }
          _ <- checkAllHashes(storage, items.map(_.getBlockMessage.blockHash).toList)
        } yield ()
      }
    }
  }

  it should "discover blocks/summaries by block hash prefix" in {
    forAll(blockElementsGen) { blockStorageElements =>
      withStorage { storage =>
        val items = blockStorageElements
        for {
          _ <- items.traverse_(storage.put)
          _ <- items.traverse[Task, Unit] { blockMsg =>
                val randomPrefix =
                  Base16.encode(
                    blockMsg.getBlockMessage.blockHash.toByteArray.take(Random.nextInt(32) + 1)
                  )

                for {
                  _ <- storage
                        .getByPrefix(randomPrefix)
                        .map { maybeBlock =>
                          maybeBlock should not be empty
                          assert(
                            maybeBlock.get.getBlockMessage.blockHash
                              .startsWith(ByteString.copyFrom(Base16.decode(randomPrefix)))
                          )
                        }
                  _ <- storage.getBlockInfoByPrefix(randomPrefix).map { maybeInfo =>
                        maybeInfo should not be empty
                        assert(
                          maybeInfo.get.getSummary.blockHash
                            .startsWith(ByteString.copyFrom(Base16.decode(randomPrefix)))
                        )
                      }
                } yield ()
              }
        } yield ()
      }
    }
  }

  it should "be able to get a Map from deploy to the blocks containing the deploy on findBlockHashesWithDeployHashes" in {
    forAll(blockElementsGen) { blockStorageElements =>
      val deployHashToBlockHashes =
        blockStorageElements
          .flatMap(
            b =>
              b.getBlockMessage.getBody.deploys
                .map(
                  _.getDeploy.deployHash -> b.getBlockMessage.blockHash
                )
                .toSet
          )
          .groupBy(_._1)
          .mapValues(_.map(_._2).toSet)

      withStorage { storage =>
        val items = blockStorageElements
        for {
          _            <- items.traverse_(storage.put)
          deployHashes = deployHashToBlockHashes.keys.toList

          result <- storage.findBlockHashesWithDeployHashes(deployHashes)
          _      = result shouldBe deployHashToBlockHashes
        } yield ()
      }
    }
  }

  it should "returns a empty list for the deploy which is not contained in any blocks on findBlockHashesWithDeployHashes" in {
    forAll(blockElementsGen) { blockStorageElements =>
      val deployHashes = blockStorageElements
        .flatMap(
          b => b.getBlockMessage.getBody.deploys.map(_.getDeploy.deployHash)
        )

      withStorage { storage =>
        for {
          result <- storage.findBlockHashesWithDeployHashes(deployHashes)
          _      = deployHashes.foreach(d => result.get(d) shouldBe (Some(Set.empty[BlockHash])))
        } yield ()
      }
    }
  }

  it should "be able to properly (de)serialize data" in {
    forAll { b: BlockMsgWithTransform =>
      withStorage { storage =>
        val before = b.toByteArray
        for {
          _          <- storage.put(b)
          maybeBlock <- storage.get(b.getBlockMessage.blockHash)
          _ <- Task {
                maybeBlock should not be None
                val got = maybeBlock.get.toByteArray
                assert(before.sameElements(got))
              }
        } yield ()
      }
    }
  }

  //TODO: update this test to properly test rollback feature.
  //https://casperlabs.atlassian.net/browse/STOR-95
  it should "rollback the transaction on error" ignore {
    withStorage { storage =>
      val exception = new RuntimeException("msg")

      def elem: (BlockHash, BlockMsgWithTransform) =
        throw exception

      for {
        _                  <- storage.isEmpty.map(_ shouldBe true)
        (blockHash, block) = elem
        putAttempt         <- storage.put(blockHash, block).attempt
        _                  = putAttempt.left.value shouldBe exception
        _                  <- storage.isEmpty.map(_ shouldBe true)
      } yield ()
    }
  }
}

class SQLiteBlockStorageTest extends BlockStorageTest with SQLiteFixture[BlockStorage[Task]] {
  override def withStorage[R](f: BlockStorage[Task] => Task[R]): R = runSQLiteTest[R](f)

  override def db: String = "/tmp/block_storage.db"

  override def createTestResource: Task[BlockStorage[Task]] =
    SQLiteStorage.create[Task](readXa = xa, writeXa = xa)
}
