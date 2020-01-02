package io.casperlabs.storage.dag

import cats.implicits._
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.{ArbitraryStorageData, SQLiteFixture}
import monix.eval.Task
import org.scalatest.{Assertion, FlatSpec, Matchers}

class FinalityStorageTest
    extends FlatSpec
    with Matchers
    with ArbitraryStorageData
    with SQLiteFixture[DagStorage[Task] with FinalityStorage[Task]] {

  implicit val consensusConfig: ConsensusConfig = ConsensusConfig(
    dagSize = 5,
    dagDepth = 3,
    dagBranchingFactor = 1,
    maxSessionCodeBytes = 1,
    maxPaymentCodeBytes = 1,
    minSessionCodeBytes = 1,
    minPaymentCodeBytes = 1
  )

  def withFinalityStorage[R](f: DagStorage[Task] with FinalityStorage[Task] => Task[R]): R =
    runSQLiteTest[R](f)

  override def db: String = "/tmp/finality_storage.db"

  override def createTestResource: Task[DagStorage[Task] with FinalityStorage[Task]] =
    SQLiteDagStorage.create[Task](readXa = xa, writeXa = xa)

  def finalityStatus(storage: FinalityStorage[Task], hashes: Seq[BlockHash]): Task[List[Boolean]] =
    hashes.toList.traverse(storage.isFinalized(_))

  def assertNotFinalized(storage: FinalityStorage[Task], hashes: BlockHash*): Task[Assertion] =
    finalityStatus(storage, hashes).map(results => assert(results.forall(!_)))

  def assertFinalized(storage: FinalityStorage[Task], hashes: BlockHash*): Task[Assertion] =
    finalityStatus(storage, hashes).map(results => assert(results.forall(identity)))

  "FinalityStorage" should "mark blocks as finalized" in withFinalityStorage { storage =>
    val sampleBlock = sample(arbBlock.arbitrary)

    for {
      _ <- storage.insert(sampleBlock)
      _ <- assertNotFinalized(storage, sampleBlock.blockHash)
      _ <- storage.markAsFinalized(sampleBlock.blockHash, Set.empty)
      _ <- assertFinalized(storage, sampleBlock.blockHash)
    } yield ()
  }

  it should "mark blocks as finalized in batches" in withFinalityStorage { storage =>
    val blocks           = List.fill(10)(sample(arbBlock.arbitrary))
    val mainParent       = blocks.head.blockHash
    val secondaryParents = blocks.tail.map(_.blockHash).toSet

    for {
      _ <- blocks.traverse_(storage.insert(_))
      _ <- assertNotFinalized(storage, blocks.map(_.blockHash): _*)
      _ <- storage.markAsFinalized(mainParent, secondaryParents)
      _ <- assertFinalized(storage, blocks.map(_.blockHash): _*)
    } yield ()
  }

  it should "return last finalized block (highest ranked block from the main chain)" in withFinalityStorage {
    storage =>
      val blocks = List.fill(10)(sample(arbBlock.arbitrary)).zipWithIndex.map {
        case (block, idx) =>
          block.update(_.header.rank := idx.toLong)
      }

      for {
        _   <- blocks.traverse_(storage.insert(_))
        _   <- assertNotFinalized(storage, blocks.map(_.blockHash): _*)
        _   <- blocks.traverse_(block => storage.markAsFinalized(block.blockHash, Set.empty))
        _   <- assertFinalized(storage, blocks.map(_.blockHash): _*)
        lfb <- storage.getLastFinalizedBlock
      } yield assert(lfb == blocks.last.blockHash)

  }
}
