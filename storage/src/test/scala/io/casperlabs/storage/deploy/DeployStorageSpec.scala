package io.casperlabs.storage.deploy

import cats.data.NonEmptyList
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, Deploy}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.models.ArbitraryConsensus
import monix.eval.Task
import org.scalacheck.{Arbitrary, Gen, Shrink}
import org.scalatest._
import org.scalatest.prop.GeneratorDrivenPropertyChecks

import scala.concurrent.duration._
import scala.util.Random

/* Trait for testing various implementations of DeployStorageReader and DeployStorageWriter */
trait DeployStorageSpec
    extends WordSpec
    with Matchers
    with ArbitraryConsensus
    with GeneratorDrivenPropertyChecks {

  /* Implement this method in descendants substituting various DeployStorageReader and DeployStorageWriter implementations */
  protected def testFixture(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit]
  ): Unit

  private implicit def noShrink[T]: Shrink[T] = Shrink.shrinkAny

  private implicit val consensusConfig: ConsensusConfig =
    ConsensusConfig(maxSessionCodeBytes = 50, maxPaymentCodeBytes = 10)

  private def deploysGen(min: Int = 1): Gen[List[Deploy]] =
    for {
      n       <- Gen.choose(min, 10)
      deploys <- Gen.listOfN(n, arbDeploy.arbitrary)
    } yield deploys

  "DeployStorageReader and DeployStorageWriter" when {
    "((addAsPending + markAsDiscarded) | (addAsProcessed + markAsFinalized)) + getPendingOrProcessed" should {
      "return None" in forAll(deploysGen(), Arbitrary.arbBool.arbitrary) { (ds, bool) =>
        testFixture { (reader, writer) =>
          for {
            _ <- if (bool)
                  writer.addAsPending(ds) >> writer.markAsDiscarded(ds)
                else writer.addAsProcessed(ds) >> writer.markAsFinalized(ds)
            _ <- reader.getPendingOrProcessed(chooseHash(ds)).foreachL(_ shouldBe None)
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
        testFixture { (reader, writer) =>
          for {
            _ <- if (pendingOrProcessed)
                  writer.addAsPending(ds) >> (if (secondStep) writer.markAsProcessed(ds)
                                              else Task.unit)
                else
                  writer.addAsProcessed(ds) >> (if (secondStep) writer.markAsPending(ds)
                                                else Task.unit)
            _ <- reader.getPendingOrProcessed(chooseHash(ds)).foreachL(_ should not be empty)
          } yield ()
        }
      }
    }

    "(addAsPending + addAsPending) | (addAsProcessed + addAsProcessed)" should {
      "not fail, ignore duplicates and insert new deploys from batch even if contains existing deploys" in forAll(
        Arbitrary.arbBool.arbitrary
      )(
        b =>
          testFixture { (reader, writer) =>
            val d1 = sample(arbDeploy.arbitrary)
            val d2 = sample(arbDeploy.arbitrary)

            val (add1, add2, read) =
              if (b)
                (writer.addAsPending(d1), writer.addAsPending(List(d1, d2)), reader.readPending)
              else
                (
                  writer.addAsProcessed(d1),
                  writer.addAsProcessed(List(d1, d2)),
                  reader.readProcessed
                )
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

    "addAsPending + addAsProcessed + getByHashes" should {
      "return the same list of deploys" in forAll(deploysGen()) { deploys =>
        testFixture { (reader, writer) =>
          val idx                  = scala.util.Random.nextInt(deploys.size)
          val (pending, processed) = deploys.splitAt(idx)
          val deployHashes         = deploys.map(_.deployHash)
          for {
            _   <- writer.addAsPending(pending)
            _   <- writer.addAsProcessed(processed)
            all <- reader.getByHashes(deployHashes)
            _   = assert(deploys.sortedByHash == all.sortedByHash)
          } yield ()

        }
      }
    }

    "readProcessedByAccount" should {
      "return only processed deploys with appropriate account" in testFixture { (reader, writer) =>
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
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(List(processed, wrongAccount))
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          p <- reader.readProcessedByAccount(processed.getHeader.accountPublicKey)
          //should
        } yield {
          p shouldBe List(processed)
        }
      }
    }

    "markAsPending" should {
      "affect only processed deploys" in testFixture { (reader, writer) =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(processed)
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          _ <- writer.markAsPending(all)
          //should
          p <- reader.readPending
        } yield {
          p should contain theSameElementsAs List(pending, processed)
        }
      }
    }

    "markAsProcessed" should {
      "affect only pending deploys" in testFixture { (reader, writer) =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(processed)
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          _ <- writer.markAsProcessed(all)
          //should
          p <- reader.readProcessed
        } yield {
          p should contain theSameElementsAs List(pending, processed)
        }
      }
    }

    "markAsFinalized" should {
      "affect only processed deploys" in testFixture { (reader, writer) =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(processed)
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          _ <- writer.markAsFinalized(all)
          //should
          _ <- reader.readProcessed.foreachL(_ shouldBe empty)
          _ <- reader.readPending.foreachL(_ shouldBe List(pending))
          _ <- writer.discardedNum().foreachL(_ shouldBe 1)
        } yield ()
      }
    }

    "markAsDiscarded" should {
      "affect only pending deploys" in testFixture { (reader, writer) =>
        val pending   = sample(arbDeploy.arbitrary)
        val processed = sample(arbDeploy.arbitrary)
        val finalized = sample(arbDeploy.arbitrary)
        val discarded = sample(arbDeploy.arbitrary)
        val all       = List(pending, processed, finalized, discarded)
        for {
          //given
          _ <- writer.addAsPending(pending)
          _ <- writer.addAsProcessed(processed)
          _ <- writer.addAsDiscarded(discarded)
          _ <- writer.addAsFinalized(finalized)
          //when
          _ <- writer.markAsDiscarded(all)
          //should
          _ <- reader.readProcessed.foreachL(_ shouldBe List(processed))
          _ <- reader.readPending.foreachL(_ shouldBe empty)
          _ <- writer.discardedNum().foreachL(_ shouldBe 2)
        } yield ()
      }
    }

    "(addAsPending + markAsDiscarded) | (addAsProcessed + markAsFinalized)" should {
      "return empty response for all 'read*' methods" in forAll(
        deploysGen(),
        Arbitrary.arbBool.arbitrary
      )(
        (ds: List[Deploy], b: Boolean) =>
          testFixture { (reader, writer) =>
            for {
              _ <- if (b) writer.addAsPending(ds) >> writer.markAsDiscarded(ds)
                  else writer.addAsProcessed(ds) >> writer.markAsFinalized(ds)
              _ <- (
                    reader.readPending,
                    reader.readPendingHashes,
                    reader.readProcessed,
                    reader.readProcessedHashes,
                    reader.readProcessedByAccount(chooseAccount(ds))
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
      "delete discarded deploys only older than 'now - expirationPeriod'" in testFixture {
        (_, writer) =>
          val first  = sample(arbDeploy.arbitrary)
          val second = sample(arbDeploy.arbitrary)
          for {
            _ <- writer.addAsPending(List(first, second))
            _ <- writer.markAsDiscarded(List(first))
            _ <- Task.sleep(1.second)
            _ <- writer.markAsDiscarded(List(second))
            _ <- writer.cleanupDiscarded(expirationPeriod = 500.millis).foreachL(_ shouldBe 1)
            _ <- Task.sleep(1.second)
            _ <- writer.cleanupDiscarded(expirationPeriod = 500.millis).foreachL(_ shouldBe 1)
            _ <- Task.sleep(1.second)
            _ <- writer.cleanupDiscarded(expirationPeriod = 500.millis).foreachL(_ shouldBe 0)
          } yield ()
      }
    }

    "markAsDiscarded(duration)" should {
      "mark only pending deploys were added more than 'now - expirationPeriod' time ago" in testFixture {
        (reader, writer) =>
          val first  = sample(arbDeploy.arbitrary)
          val second = sample(arbDeploy.arbitrary)
          for {
            _ <- writer.addAsPending(List(first))
            _ <- reader.pendingNum.foreachL(_ shouldBe 1)
            _ <- Task.sleep(1.second)
            _ <- writer.addAsProcessed(List(second))
            _ <- writer.markAsPending(List(second))
            _ <- reader.pendingNum.foreachL(_ shouldBe 2)
            _ <- writer.markAsDiscarded(expirationPeriod = 500.millis)
            _ <- reader.pendingNum.foreachL(_ shouldBe 1)
            _ <- Task.sleep(1.second)
            _ <- writer.markAsDiscarded(expirationPeriod = 500.millis)
            _ <- reader.pendingNum.foreachL(_ shouldBe 0)
          } yield ()
      }
    }

    "addAsExecuted + addAsExecuted" should {
      "not fail, ignore duplicates and insert new deploys from block even if contains existing deploys" in testFixture {
        (reader, writer) =>
          val b1         = sample(arbBlock.arbitrary)
          val difference = sample(arbBlock.arbitrary)
          val b2 = difference.withBody(
            difference.getBody.withDeploys(difference.getBody.deploys ++ b1.getBody.deploys)
          )

          def processingResults(bs: Block*) =
            bs.toList.flatMap(b => b.getBody.deploys.map((b.blockHash, _)).toList)

          val deployHashesB1         = b1.getBody.deploys.map(_.getDeploy.deployHash).toList
          val deployHashesB2         = b2.getBody.deploys.map(_.getDeploy.deployHash).toList
          val deployHashesDifference = difference.getBody.deploys.map(_.getDeploy.deployHash).toList

          for {
            _ <- writer.addAsExecuted(b1)
            _ <- writer.addAsExecuted(b2).attempt.foreachL(_ shouldBe an[Right[_, _]])
            processingResultsB1 <- deployHashesB1.flatTraverse { deployHash =>
                                    reader.getProcessingResults(deployHash)
                                  }
            processingResultsB2 <- deployHashesB2.flatTraverse { deployHash =>
                                    reader.getProcessingResults(deployHash)
                                  }
            processingResultsDiff <- deployHashesDifference.flatTraverse { deployHash =>
                                      reader.getProcessingResults(deployHash)
                                    }
          } yield {
            processingResultsB1 should contain theSameElementsAs processingResults(
              b1,
              b1.copy(blockHash = b2.blockHash)
            )
            processingResultsB2 should contain theSameElementsAs processingResults(b1, b2)
            processingResultsDiff should contain theSameElementsAs processingResults(difference)
          }
      }
    }
  }

  private def chooseHash(deploys: List[Deploy]): ByteString =
    deploys(Random.nextInt(deploys.size)).deployHash

  private def chooseAccount(deploys: List[Deploy]): ByteString =
    deploys(Random.nextInt(deploys.size)).getHeader.accountPublicKey

  private implicit class DeployStorageWriterOps(writer: DeployStorageWriter[Task]) {
    def addAsPending(d: Deploy): Task[Unit]   = writer.addAsPending(List(d))
    def addAsProcessed(d: Deploy): Task[Unit] = writer.addAsProcessed(List(d))
    def addAsDiscarded(d: Deploy): Task[Unit] =
      writer.addAsPending(List(d)) >> writer.markAsDiscarded(List((d, "")))
    def markAsDiscarded(ds: List[Deploy]): Task[Unit] = writer.markAsDiscarded(ds.map((_, "")))
    def addAsFinalized(d: Deploy): Task[Unit] =
      writer.addAsProcessed(List(d)) >> writer.markAsFinalized(List(d))
    def discardedNum(): Task[Int] = Task.sleep(50.millis) >> writer.cleanupDiscarded(Duration.Zero)
  }

  private implicit class DeployStorageReaderOps(reader: DeployStorageReader[Task]) {
    def pendingNum: Task[Int] = reader.readPending.map(_.size)
  }

  private implicit class DeploysOps(ds: List[Deploy]) {
    implicit val deployOrdering: Ordering[Deploy] =
      Ordering.by(d => Base16.encode(d.deployHash.toByteArray))
    def sortedByHash: List[Deploy] = ds.sorted
  }
}
