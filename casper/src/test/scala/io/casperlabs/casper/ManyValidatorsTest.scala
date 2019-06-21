package io.casperlabs.casper

import cats.Monad
import cats.effect.Sync
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.IndexedBlockDagStorage
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.api.BlockAPI
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper._
import io.casperlabs.casper.consensus.{Block, Bond}
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

class ManyValidatorsTest
    extends FlatSpec
    with Matchers
    with BlockGenerator
    with BlockDagStorageFixture {
  "Show blocks" should "be processed quickly for a node with 300 validators" in {
    val blockDagStorageDir = BlockDagStorageTestFixture.blockDagStorageDir
    val blockStoreDir      = BlockDagStorageTestFixture.blockStorageDir
    implicit val metrics   = new MetricsNOP[Task]()
    implicit val log       = new Log.NOPLog[Task]()
    val bonds = Seq
      .fill(300)(
        ByteString.copyFromUtf8(Random.nextString(20)).substring(0, 32)
      )
      .map(Bond(_, 10))
    val v1 = bonds(0).validatorPublicKey

    val testProgram = for {
      blockStore <- BlockDagStorageTestFixture.createBlockStorage[Task](blockStoreDir)
      blockDagStorage <- BlockDagStorageTestFixture.createBlockDagStorage(blockDagStorageDir)(
                          metrics,
                          log,
                          blockStore
                        )
      indexedBlockDagStorage <- IndexedBlockDagStorage.create(blockDagStorage)
      genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)(
                  Monad[Task],
                  Time[Task],
                  blockStore,
                  indexedBlockDagStorage
                )
      b <- createBlock[Task](Seq(genesis.blockHash), v1, bonds, bonds.map {
            case Bond(validator, _) => validator -> genesis.blockHash
          }.toMap)(Monad[Task], Time[Task], blockStore, indexedBlockDagStorage)
      _                     <- indexedBlockDagStorage.close()
      initialLatestMessages = bonds.map { case Bond(validator, _) => validator -> b }.toMap
      _ <- Sync[Task].delay {
            BlockDagStorageTestFixture.writeInitialLatestMessages(
              blockDagStorageDir.resolve("latest-messages-log"),
              blockDagStorageDir.resolve("latest-messages-crc"),
              initialLatestMessages
            )
          }
      newBlockDagStorage <- BlockDagStorageTestFixture.createBlockDagStorage(blockDagStorageDir)(
                             metrics,
                             log,
                             blockStore
                           )
      newIndexedBlockDagStorage <- IndexedBlockDagStorage.create(newBlockDagStorage)
      dag                       <- newIndexedBlockDagStorage.getRepresentation
      tips                      <- Estimator.tips[Task](dag, genesis.blockHash)(Monad[Task])
      casperEffect <- NoOpsCasperEffect[Task](
                       HashMap.empty[BlockHash, BlockMsgWithTransform],
                       tips.toIndexedSeq
                     )(Sync[Task], blockStore, newIndexedBlockDagStorage)
      logEff             = new LogStub[Task]
      casperRef          <- MultiParentCasperRef.of[Task]
      _                  <- casperRef.set(casperEffect)
      cliqueOracleEffect = new SafetyOracleInstancesImpl[Task]
      result <- BlockAPI.showBlocks[Task](Int.MaxValue)(
                 MonadThrowable[Task],
                 casperRef,
                 logEff,
                 cliqueOracleEffect,
                 blockStore
               )
    } yield result
    testProgram.runSyncUnsafe(1 minute)(scheduler, CanBlock.permit)
  }
}
