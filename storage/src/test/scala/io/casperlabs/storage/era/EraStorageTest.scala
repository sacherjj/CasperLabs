package io.casperlabs.storage.era

import cats._
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Era
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.storage.{SQLiteFixture, SQLiteStorage}
import monix.eval.Task
import org.scalatest._

trait EraStorageTest extends FlatSpecLike with Matchers with ArbitraryConsensus {

  def withStorage[R](f: EraStorage[Task] => Task[R]): R

  behavior of "EraStorage"

  it should "return None for non-existing" in withStorage { db =>
    db.getEra(ByteString.copyFromUtf8("non-existent-era")) map {
      _ shouldBe empty
    }
  }

  it should "raise NoSuchElementException for non-existing with" in withStorage { db =>
    db.getEraUnsafe(ByteString.copyFromUtf8("non-existent-era")).attempt map {
      case Left(ex) => ex shouldBe a[NoSuchElementException]
      case Right(_) => fail("Not expected to find the era.")
    }
  }

  it should "add and get an era" in withStorage { db =>
    val era = sample[Era]
    for {
      _ <- db.addEra(era)
      a <- db.getEra(era.keyBlockHash)
      _ = a shouldBe Some(era)
      b <- db.getEraUnsafe(era.keyBlockHash)
      _ = b shouldBe era
    } yield ()
  }

  it should "return whether the era already existed" in withStorage { db =>
    val era = sample[Era]
    for {
      a <- db.containsEra(era.keyBlockHash)
      b <- db.addEra(era)
      c <- db.containsEra(era.keyBlockHash)
      d <- db.addEra(era)
    } yield {
      a shouldBe false
      b shouldBe true
      c shouldBe true
      d shouldBe false
    }
  }

  it should "find the children of an era" in withStorage { db =>
    val e0 = sample[Era]
    val e1 = sample[Era].withParentKeyBlockHash(e0.keyBlockHash)
    val e2 = sample[Era].withParentKeyBlockHash(e0.keyBlockHash)
    for {
      _  <- db.addEra(e0)
      _  <- db.addEra(e1)
      _  <- db.addEra(e2)
      es <- db.getChildEras(e0.keyBlockHash)
      _  = es should contain theSameElementsAs List(e1, e2)
    } yield ()
  }

  it should "find childless eras" in withStorage { db =>
    // e0 - e1
    //    \ e2 - e3
    //         \ e4
    val e0 = sample[Era]
    val e1 = sample[Era].withParentKeyBlockHash(e0.keyBlockHash)
    val e2 = sample[Era].withParentKeyBlockHash(e0.keyBlockHash)
    val e3 = sample[Era].withParentKeyBlockHash(e2.keyBlockHash)
    val e4 = sample[Era].withParentKeyBlockHash(e2.keyBlockHash)
    for {
      _  <- List(e0, e1, e2, e3, e4).traverse(db.addEra)
      es <- db.getChildlessEras
      _  = es should contain theSameElementsAs List(e1, e3, e4)
    } yield ()
  }
}

class SQLiteEraStorageTest extends EraStorageTest with SQLiteFixture[EraStorage[Task]] {
  override def withStorage[R](f: EraStorage[Task] => Task[R]): R = runSQLiteTest[R](f)

  override def db: String = "/tmp/era_storage.db"

  override def createTestResource: Task[EraStorage[Task]] =
    SQLiteStorage.create[Task](readXa = xa, writeXa = xa)
}
