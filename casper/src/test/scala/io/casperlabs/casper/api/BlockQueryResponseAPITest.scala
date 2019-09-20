package io.casperlabs.casper.api

import cats.effect.Sync
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockStorage, DagStorage}
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.finality.singlesweep.{
  FinalityDetector,
  FinalityDetectorBySingleSweepImpl
}
import io.casperlabs.casper.helper.{DagStorageFixture, NoOpsCasperEffect}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.p2p.EffectsTestInstances.{LogStub, LogicalTime}
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.HashMap

@silent("deprecated")
class BlockQueryResponseAPITest extends FlatSpec with Matchers with DagStorageFixture {
  implicit val timeEff = new LogicalTime[Task]
  val secondBlockQuery = "1234"
  val badTestHashQuery = "No such a hash"

  val genesisHashString = "0" * 64
  val genesisHash       = ProtoUtil.stringToByteString(genesisHashString)
  val version           = 1L

  def genesisBlock(genesisHashString: String, version: Long): Block = {
    val genesisHash = ProtoUtil.stringToByteString(genesisHashString)
    val ps = Block
      .GlobalState()
      .withBonds(Seq(Bond(ByteString.copyFromUtf8("random"), 1)))
    val body = Block.Body()
    val header = ProtoUtil.blockHeader(
      body,
      parentHashes = Nil,
      justifications = Nil,
      state = ps,
      rank = 0,
      protocolVersion = version,
      timestamp = 1527191663,
      chainId = "casperlabs"
    )
    ProtoUtil.unsignedBlockProto(body, header).withBlockHash(genesisHash)
  }
  val genesisBlock: Block = genesisBlock(genesisHashString, version)

  val secondHashString     = "1234567891011121314151617181921234567891011121314151617181928192"
  val blockHash: BlockHash = ProtoUtil.stringToByteString(secondHashString)
  val blockNumber          = 1L
  val timestamp            = 1527191665L
  val ps                   = Block.GlobalState()
  val deployCount          = 10L
  val randomDeploys =
    (0L until deployCount).toList
      .traverse(_ => ProtoUtil.basicProcessedDeploy[Task]())
      .unsafeRunSync(scheduler)
  val body                             = Block.Body().withDeploys(randomDeploys)
  val parentsString                    = List(genesisHashString, "0000000001")
  val parentsHashList: List[BlockHash] = parentsString.map(ProtoUtil.stringToByteString)
  val header = ProtoUtil.blockHeader(
    body,
    parentsHashList,
    Nil,
    ps,
    blockNumber,
    version,
    timestamp,
    "casperlabs"
  )
  val secondBlockSenderString: String =
    "3456789101112131415161718192345678910111213141516171819261718192"
  val secondBlockSender: ByteString = ProtoUtil.stringToByteString(secondBlockSenderString)
  val chainId: String               = "abcdefgh"
  val secondBlock: Block =
    Block()
      .withBlockHash(blockHash)
      .withHeader(header.withValidatorPublicKey(secondBlockSender).withChainId(chainId))
      .withBody(body)

  val faultTolerance = 0

  // TODO: Test tsCheckpoint:
  // we should be able to stub in a tuplespace dump but there is currently no way to do that.
  "showBlock" should "return successful block info response" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      for {
        effects                                     <- effectsForSimpleCasperSetup(blockStorage, dagStorage)
        (logEff, casperRef, finalityDetectorEffect) = effects
        blockInfo <- BlockAPI.getBlockInfo[Task](secondBlockQuery, full = true)(
                      Sync[Task],
                      logEff,
                      casperRef,
                      finalityDetectorEffect,
                      blockStorage
                    )
        _      = blockInfo.getSummary.blockHash should be(blockHash)
        _      = blockInfo.getStatus.getStats.blockSizeBytes should be(secondBlock.serializedSize)
        _      = blockInfo.getSummary.getHeader.rank should be(blockNumber)
        _      = blockInfo.getSummary.getHeader.protocolVersion should be(version)
        _      = blockInfo.getSummary.getHeader.deployCount should be(deployCount)
        _      = blockInfo.getStatus.faultTolerance should be(faultTolerance)
        _      = blockInfo.getSummary.getHeader.parentHashes.head should be(genesisHash)
        _      = blockInfo.getSummary.getHeader.parentHashes should be(parentsHashList)
        _      = blockInfo.getSummary.getHeader.validatorPublicKey should be(secondBlockSender)
        result = blockInfo.getSummary.getHeader.chainId should be(chainId)
      } yield result
  }

  it should "return error when no block exists" in withStorage {
    implicit blockStorage => implicit dagStorage =>
      for {
        effects                                     <- emptyEffects(blockStorage, dagStorage)
        (logEff, casperRef, finalityDetectorEffect) = effects
        blockQueryResponse <- BlockAPI
                               .getBlockInfo[Task](badTestHashQuery)(
                                 Sync[Task],
                                 logEff,
                                 casperRef,
                                 finalityDetectorEffect,
                                 blockStorage
                               )
                               .attempt
      } yield {
        blockQueryResponse.isLeft shouldBe true
        blockQueryResponse.left.get.getMessage should include("NOT_FOUND")
      }
  }

  private def effectsForSimpleCasperSetup(
      blockStorage: BlockStorage[Task],
      dagStorage: DagStorage[Task]
  ): Task[(LogStub[Task], MultiParentCasperRef[Task], FinalityDetector[Task])] =
    for {
      _ <- dagStorage.insert(genesisBlock)
      _ <- dagStorage.insert(secondBlock)
      casperEffect <- NoOpsCasperEffect[Task](
                       HashMap[BlockHash, BlockMsgWithTransform](
                         (
                           ProtoUtil.stringToByteString(genesisHashString),
                           BlockMsgWithTransform(Some(genesisBlock), Seq.empty)
                         ),
                         (
                           ProtoUtil.stringToByteString(secondHashString),
                           BlockMsgWithTransform(Some(secondBlock), Seq.empty)
                         )
                       )
                     )(Sync[Task], blockStorage, dagStorage)
      logEff                 = new LogStub[Task]()
      casperRef              <- MultiParentCasperRef.of[Task]
      _                      <- casperRef.set(casperEffect)
      finalityDetectorEffect = new FinalityDetectorBySingleSweepImpl[Task]()(Sync[Task], logEff)
    } yield (logEff, casperRef, finalityDetectorEffect)

  private def emptyEffects(
      blockStorage: BlockStorage[Task],
      dagStorage: DagStorage[Task]
  ): Task[(LogStub[Task], MultiParentCasperRef[Task], FinalityDetector[Task])] =
    for {
      casperEffect <- NoOpsCasperEffect(
                       HashMap[BlockHash, BlockMsgWithTransform](
                         (
                           ProtoUtil.stringToByteString(genesisHashString),
                           BlockMsgWithTransform(Some(genesisBlock), Seq.empty)
                         ),
                         (
                           ProtoUtil.stringToByteString(secondHashString),
                           BlockMsgWithTransform(Some(secondBlock), Seq.empty)
                         )
                       )
                     )(Sync[Task], blockStorage, dagStorage)
      logEff                 = new LogStub[Task]()
      casperRef              <- MultiParentCasperRef.of[Task]
      _                      <- casperRef.set(casperEffect)
      finalityDetectorEffect = new FinalityDetectorBySingleSweepImpl[Task]()(Sync[Task], logEff)
    } yield (logEff, casperRef, finalityDetectorEffect)
}
