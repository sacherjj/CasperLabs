package io.casperlabs.casper.deploybuffer

import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.comm.gossiping.ArbitraryConsensus
import monix.eval.Task
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.concurrent.duration._
import scala.util.Random

/* Trait for testing various implementations of DeployBuffer */
@silent(".*")
trait DeployBufferSpec
    extends WordSpec
    with Matchers
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {

  /* Implement this method in descendants substituting various DeployBuffer implementations */
  protected def testFixture(test: DeployBuffer[Task] => Task[Unit]): Unit

  private implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  private implicit val consensusConfig: ConsensusConfig =
    ConsensusConfig(maxSessionCodeBytes = 50, maxPaymentCodeBytes = 10)

  private def deploysGen(min: Int = 1): Gen[List[Deploy]] =
    for {
      n       <- Gen.choose(min, 10)
      deploys <- Gen.listOfN(n, arbDeploy.arbitrary)
    } yield deploys

  "DeployBuffer" when {
    "((addAsPending + markAsDiscarded) | (addAsProcessed + markAsFinalized)) + getPendingOrProcessed" should {
      "return None" in forAll(deploysGen(), Arbitrary.arbBool.arbitrary) { (ds, bool) =>
        testFixture { db =>
          for {
            _ <- if (bool) db.addAsPending(ds) >> db.markAsDiscarded(ds)
                else db.addAsProcessed(ds) >> db.markAsFinalized(ds)
            _ <- db.getPendingOrProcessed(chooseHash(ds)).foreachL(_ shouldBe None)
          } yield ()
        }
      }
    }

    "((addAsPending + markAsProcessed?) | (addAsProcessed + markAsPending?)) + getPendingOrProcessed" should {
      "return Some if exists" in forAll(
        deploysGen(),
        Arbitrary.arbBool.arbitrary,
        Arbitrary.arbBool.arbitrary
      ) { (ds, pendingOrProcessed, secondStep) =>
        testFixture { db =>
          for {
            _ <- if (pendingOrProcessed)
                  db.addAsPending(ds) >> (if (secondStep) db.markAsProcessed(ds)
                                          else Task.unit)
                else
                  db.addAsProcessed(ds) >> (if (secondStep) db.markAsPending(ds)
                                            else Task.unit)
            _ <- db.getPendingOrProcessed(chooseHash(ds)).foreachL(_ should not be empty)
          } yield ()
        }
      }
    }

    "(addAsPending + addAsPending) | (addAsProcessed + addAsProcessed)" should {
      "not fail, ignore duplicates and insert new deploys from batch even if contains existing deploys" in forAll(
        Arbitrary.arbBool.arbitrary
      )(
        b =>
          testFixture { db =>
            val d1 = sample(arbDeploy.arbitrary)
            val d2 = sample(arbDeploy.arbitrary)

            val (add1, add2, read) =
              if (b) (db.addAsPending(d1), db.addAsPending(List(d1, d2)), db.readPending)
              else (db.addAsProcessed(d1), db.addAsProcessed(List(d1, d2)), db.readProcessed)
            for {
              _ <- add1
              _ <- add2.attempt.foreachL(_ shouldBe an[Right[_, _]])
              _ <- read
                    .foreachL(
                      _.sortedByHash should contain theSameElementsAs List(d1, d2).sortedByHash
                    )
            } yield ()
          }
      )
    }

    "readProcessedByAccount" should {
      "return only processed deploys with appropriate account" in testFixture { db =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val wrongAccount =
          processed
            .withHeader(processed.getHeader.withAccountPublicKey(sample(genHash)))
            .withDeployHash(sample(genHash))
        for {
          //given
          _ <- db.addAsPending(pending)
          _ <- db.addAsProcessed(List(processed, wrongAccount))
          _ <- db.addAsDiscarded(discarded)
          _ <- db.addAsFinalized(finalized)
          //when
          p <- db
                .readProcessedByAccount(processed.getHeader.accountPublicKey)
          //should
        } yield {
          p shouldBe List(processed)
        }
      }
    }

    "markAsPending" should {
      "affect only processed deploys" in testFixture { db =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- db.addAsPending(pending)
          _ <- db.addAsProcessed(processed)
          _ <- db.addAsDiscarded(discarded)
          _ <- db.addAsFinalized(finalized)
          //when
          _ <- db.markAsPending(all)
          //should
          p <- db.readPending
        } yield {
          p should contain theSameElementsAs List(pending, processed)
        }
      }
    }

    "markAsProcessed" should {
      "affect only pending deploys" in testFixture { db =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- db.addAsPending(pending)
          _ <- db.addAsProcessed(processed)
          _ <- db.addAsDiscarded(discarded)
          _ <- db.addAsFinalized(finalized)
          //when
          _ <- db.markAsProcessed(all)
          //should
          p <- db.readProcessed
        } yield {
          p should contain theSameElementsAs List(pending, processed)
        }
      }
    }

    "markAsFinalized" should {
      "affect only processed deploys" in testFixture { db =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- db.addAsPending(pending)
          _ <- db.addAsProcessed(processed)
          _ <- db.addAsDiscarded(discarded)
          _ <- db.addAsFinalized(finalized)
          //when
          _ <- db.markAsFinalized(all)
          //should
          _ <- db.readProcessed.foreachL(_ shouldBe empty)
          _ <- db.readPending.foreachL(_ shouldBe List(pending))
          _ <- db.discardedNum().foreachL(_ shouldBe 1)
        } yield ()
      }
    }

    "markAsDiscarded" should {
      "affect only pending deploys" in testFixture { db =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- db.addAsPending(pending)
          _ <- db.addAsProcessed(processed)
          _ <- db.addAsDiscarded(discarded)
          _ <- db.addAsFinalized(finalized)
          //when
          _ <- db.markAsDiscarded(all)
          //should
          _ <- db.readProcessed.foreachL(_ shouldBe List(processed))
          _ <- db.readPending.foreachL(_ shouldBe empty)
          _ <- db.discardedNum().foreachL(_ shouldBe 2)
        } yield ()
      }
    }

    "(addAsPending + markAsDiscarded) | (addAsProcessed + markAsFinalized)" should {
      "return empty response for all 'read*' methods" in forAll(
        deploysGen(),
        Arbitrary.arbBool.arbitrary
      )(
        (ds: List[Deploy], b: Boolean) =>
          testFixture { db =>
            for {
              _ <- if (b) db.addAsPending(ds) >> db.markAsDiscarded(ds)
                  else db.addAsProcessed(ds) >> db.markAsFinalized(ds)
              _ <- (
                    db.readPending,
                    db.readPendingHashes,
                    db.readProcessed,
                    db.readProcessedHashes,
                    db.readProcessedByAccount(chooseAccount(ds))
                  ).mapN {
                    (pending, pendingHashes, processed, processedHashes, processedByAccount) =>
                      pending shouldBe empty
                      pendingHashes shouldBe empty
                      processed shouldBe empty
                      processedHashes shouldBe empty
                      processedByAccount shouldBe empty
                  }
            } yield ()
          }
      )
    }

    "cleanupDiscarded" should {
      "delete discarded deploys only older than 'now - expirationPeriod'" in testFixture { db =>
        val first  = sample(arbDeploy.arbitrary)
        val second = sample(arbDeploy.arbitrary)
        for {
          _ <- db.addAsPending(List(first, second))
          _ <- db.markAsDiscarded(List(first))
          _ <- Task.sleep(1.second)
          _ <- db.markAsDiscarded(List(second))
          _ <- db.cleanupDiscarded(expirationPeriod = 500.millis).foreachL(_ shouldBe 1)
          _ <- Task.sleep(1.second)
          _ <- db.cleanupDiscarded(expirationPeriod = 500.millis).foreachL(_ shouldBe 1)
          _ <- Task.sleep(1.second)
          _ <- db.cleanupDiscarded(expirationPeriod = 500.millis).foreachL(_ shouldBe 0)
        } yield ()
      }
    }

    "markAsDiscarded(duration)" should {
      "mark only pending deploys were added more than 'now - expirationPeriod' time ago" in testFixture {
        db =>
          val first  = sample(arbDeploy.arbitrary)
          val second = sample(arbDeploy.arbitrary)
          for {
            _ <- db.addAsPending(List(first))
            _ <- db.pendingNum.foreachL(_ shouldBe 1)
            _ <- Task.sleep(1.second)
            _ <- db.addAsProcessed(List(second))
            _ <- db.markAsPending(List(second))
            _ <- db.pendingNum.foreachL(_ shouldBe 2)
            _ <- db.markAsDiscarded(expirationPeriod = 500.millis)
            _ <- db.pendingNum.foreachL(_ shouldBe 1)
            _ <- Task.sleep(1.second)
            _ <- db.markAsDiscarded(expirationPeriod = 500.millis)
            _ <- db.pendingNum.foreachL(_ shouldBe 0)
          } yield ()
      }
    }
  }

  private def chooseHash(deploys: List[Deploy]): ByteString =
    deploys(Random.nextInt(deploys.size)).deployHash

  private def chooseAccount(deploys: List[Deploy]): ByteString =
    deploys(Random.nextInt(deploys.size)).getHeader.accountPublicKey

  private implicit class DeployBufferOps(db: DeployBuffer[Task]) {
    def addAsPending(d: Deploy): Task[Unit] =
      db.addAsPending(List(d))
    def addAsProcessed(d: Deploy): Task[Unit] =
      db.addAsProcessed(List(d))
    def addAsDiscarded(d: Deploy): Task[Unit] =
      db.addAsPending(List(d)) >> db.markAsDiscarded(List(d))
    def addAsFinalized(d: Deploy): Task[Unit] =
      db.addAsProcessed(List(d)) >> db.markAsFinalized(List(d))
    def discardedNum(): Task[Int] =
      Task.sleep(50.millis) >> db.cleanupDiscarded(Duration.Zero)
    def pendingNum: Task[Int] = db.readPending.map(_.size)
  }

  private implicit class DeploysOps(ds: List[Deploy]) {
    import io.casperlabs.casper.util.Sorting._
    def sortedByHash: List[Deploy] = ds.sortBy(_.deployHash)
  }
}
