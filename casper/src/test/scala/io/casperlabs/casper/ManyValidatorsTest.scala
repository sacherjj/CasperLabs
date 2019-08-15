package io.casperlabs.casper

import cats.Monad
import cats.effect.Sync
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.IndexedDagStorage
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.consensus.Bond
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.{Log, Time}
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.schedulers.CanBlock
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap
import scala.concurrent.duration._
import scala.util.Random

@silent("deprecated")
class ManyValidatorsTest extends FlatSpec with Matchers with BlockGenerator with DagStorageFixture {
  "Show blocks" should "be processed quickly for a node with 300 validators" in {
    val dagStorageDir    = DagStorageTestFixture.dagStorageDir
    val blockStorageDir  = DagStorageTestFixture.blockStorageDir
    implicit val metrics = new MetricsNOP[Task]()
    implicit val log     = new Log.NOPLog[Task]()
    val bonds = Seq
      .fill(300)(
        ByteString.copyFromUtf8(Random.nextString(20)).substring(0, 32)
      )
      .map(Bond(_, 10))
    val v1 = bonds(0).validatorPublicKey

    val testProgram = for {
      blockStorage <- DagStorageTestFixture.createBlockStorage[Task](blockStorageDir)
      dagStorage <- DagStorageTestFixture.createDagStorage(dagStorageDir)(
                     metrics,
                     log,
                     blockStorage
                   )
      indexedDagStorage <- IndexedDagStorage.create(dagStorage)
      genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)(
                  Monad[Task],
                  Time[Task],
                  blockStorage,
                  indexedDagStorage
                )
      b <- createBlock[Task](Seq(genesis.blockHash), v1, bonds, bonds.map {
            case Bond(validator, _) => validator -> genesis.blockHash
          }.toMap)(Monad[Task], Time[Task], blockStorage, indexedDagStorage)
      _                     <- indexedDagStorage.close()
      initialLatestMessages = bonds.map { case Bond(validator, _) => validator -> b }.toMap
      _ <- Sync[Task].delay {
            DagStorageTestFixture.writeInitialLatestMessages(
              dagStorageDir.resolve("latest-messages-log"),
              dagStorageDir.resolve("latest-messages-crc"),
              initialLatestMessages
            )
          }
      newDagStorage <- DagStorageTestFixture.createDagStorage(dagStorageDir)(
                        metrics,
                        log,
                        blockStorage
                      )
      newIndexedDagStorage <- IndexedDagStorage.create(newDagStorage)
      dag                  <- newIndexedDagStorage.getRepresentation
      tips                 <- Estimator.tips[Task](dag, genesis.blockHash)(MonadThrowable[Task])
      casperEffect <- NoOpsCasperEffect[Task](
                       HashMap.empty[BlockHash, BlockMsgWithTransform],
                       tips.toIndexedSeq
                     )(Sync[Task], blockStorage, newIndexedDagStorage)
      logEff                 = new LogStub[Task]
      casperRef              <- MultiParentCasperRef.of[Task]
      _                      <- casperRef.set(casperEffect)
      finalityDetectorEffect = new FinalityDetectorInstancesImpl[Task]
      result <- BlockAPI.showBlocks[Task](Int.MaxValue)(
                 MonadThrowable[Task],
                 casperRef,
                 logEff,
                 finalityDetectorEffect,
                 blockStorage
               )
    } yield result
    testProgram.runSyncUnsafe(1 minute)(scheduler, CanBlock.permit)
  }
}
