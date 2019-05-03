package io.casperlabs.casper

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.helper.HashSetCasperTestNode.Effect
import io.casperlabs.casper.helper.{
  BlockDagStorageTestFixture,
  BlockUtil,
  GossipServiceCasperTestNodeFactory,
  HashSetCasperTestNode,
  HashSetCasperTestNodeFactory,
  TransportLayerCasperTestNode,
  TransportLayerCasperTestNodeFactory
}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.{BondingUtil, ProtoUtil}
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.comm.rp.ProtocolHelper.packet
import io.casperlabs.comm.transport
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.{Blake2b256, Keccak256}
import io.casperlabs.crypto.signatures.{Ed25519, Secp256k1}
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.p2p.EffectsTestInstances.{LogStub, LogicalTime}
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{Assertion, FlatSpec, Matchers}

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration

/** Run tests using the TransportLayer. */
class TransportLayerCasperTest extends HashSetCasperTest with TransportLayerCasperTestNodeFactory

/** Run tests using the GossipService and co. */
class GossipServiceCasperTest extends HashSetCasperTest with GossipServiceCasperTestNodeFactory

/** Run tests against whatever kind of test node the inheriting sets up. */
abstract class HashSetCasperTest extends FlatSpec with Matchers with HashSetCasperTestNodeFactory {

  import HashSetCasperTest._

  implicit val timeEff = new LogicalTime[Effect]

  private val (otherSk, otherPk)          = Ed25519.newKeyPair
  private val (validatorKeys, validators) = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
  private val (ethPivKeys, ethPubKeys)    = (1 to 4).map(_ => Secp256k1.newKeyPair).unzip
  private val ethAddresses =
    ethPubKeys.map(pk => "0x" + Base16.encode(Keccak256.hash(pk.bytes.drop(1)).takeRight(20)))
  private val wallets     = ethAddresses.map(addr => PreWallet(addr, BigInt(10001)))
  private val bonds       = createBonds(validators)
  private val minimumBond = 100L
  private val BlockMsgWithTransform(Some(genesis), transforms) =
    buildGenesis(wallets, bonds, minimumBond, Long.MaxValue, Faucet.basicWalletFaucet, 0L)

  //put a new casper instance at the start of each
  //test since we cannot reset it
  behavior of "HashSetCasper"

  it should "accept deploys" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy <- ProtoUtil.basicDeployData[Effect](0)
      _      <- MultiParentCasper[Effect].deploy(deploy)

      _      = logEff.infos.size should be(1)
      result = logEff.infos(0).contains("Received Deploy") should be(true)
      _      <- node.tearDown()
    } yield result
  }

  it should "not allow multiple threads to process the same block" in {
    val scheduler = Scheduler.fixedPool("three-threads", 3)
    val node =
      standaloneEff(genesis, transforms, validatorKeys.head)(scheduler)
    val casper = node.casperEff

    val testProgram = for {
      deploy <- ProtoUtil.basicDeployData[Effect](0)
      _      <- casper.deploy(deploy)
      block  <- casper.createBlock.map { case Created(block) => block }
      result <- EitherT(
                 Task
                   .racePair(
                     casper.addBlock(block).value,
                     casper.addBlock(block).value
                   )
                   .flatMap {
                     case Left((statusA, running)) =>
                       running.join.map((statusA, _).tupled)

                     case Right((running, statusB)) =>
                       running.join.map((_, statusB).tupled)
                   }
               )
    } yield result
    val threadStatuses: (BlockStatus, BlockStatus) =
      testProgram.value.unsafeRunSync(scheduler).right.get

    threadStatuses should matchPattern { case (Processing, Valid) | (Valid, Processing) => }
    node.tearDown().value.unsafeRunSync
  }

  it should "create blocks based on deploys" in effectTest {
    val node            = standaloneEff(genesis, transforms, validatorKeys.head)
    implicit val casper = node.casperEff

    for {
      deploy <- ProtoUtil.basicDeployData[Effect](0)
      _      <- MultiParentCasper[Effect].deploy(deploy)

      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
      deploys           = block.body.get.deploys.flatMap(_.deploy)
      parents           = ProtoUtil.parentHashes(block)
      storage           <- blockTuplespaceContents(block)

      _ = parents should have size 1
      _ = parents.head shouldBe genesis.blockHash
      _ = deploys should have size 1
      _ = deploys.head shouldEqual deploy
      _ = pendingUntilFixed {
        storage.contains("@{0}!(0)") shouldBe true
      }
      _ <- node.tearDown()
    } yield ()
  }

  it should "accept signed blocks" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy               <- ProtoUtil.basicDeployData[Effect](0)
      _                    <- MultiParentCasper[Effect].deploy(deploy)
      createBlockResult    <- MultiParentCasper[Effect].createBlock
      Created(signedBlock) = createBlockResult
      _                    <- MultiParentCasper[Effect].addBlock(signedBlock)
      _                    = logEff.warns.isEmpty should be(true)
      dag                  <- MultiParentCasper[Effect].blockDag
      estimate             <- MultiParentCasper[Effect].estimator(dag)
      _                    = estimate shouldBe IndexedSeq(signedBlock.blockHash)
      _                    = node.tearDown()
    } yield ()
  }

  it should "be able to create a chain of blocks from different deploys" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._

    val start = System.currentTimeMillis()

    val deployDatas = Vector(
      "contract @\"add\"(@x, @y, ret) = { ret!(x + y) }",
      "new unforgable in { @\"add\"!(5, 7, *unforgable) }"
    ).zipWithIndex.map(s => ProtoUtil.sourceDeploy(s._1, start + s._2, Integer.MAX_VALUE))

    for {
      createBlockResult1 <- MultiParentCasper[Effect].deploy(deployDatas.head) *> MultiParentCasper[
                             Effect
                           ].createBlock
      Created(signedBlock1) = createBlockResult1
      _                     <- MultiParentCasper[Effect].addBlock(signedBlock1)
      createBlockResult2 <- MultiParentCasper[Effect].deploy(deployDatas(1)) *> MultiParentCasper[
                             Effect
                           ].createBlock
      Created(signedBlock2) = createBlockResult2
      _                     <- MultiParentCasper[Effect].addBlock(signedBlock2)
      storage               <- blockTuplespaceContents(signedBlock2)
      _                     = logEff.warns shouldBe empty
      _                     = ProtoUtil.parentHashes(signedBlock2) should be(Seq(signedBlock1.blockHash))
      dag                   <- MultiParentCasper[Effect].blockDag
      estimate              <- MultiParentCasper[Effect].estimator(dag)

      _ = estimate shouldBe IndexedSeq(signedBlock2.blockHash)
      _ = pendingUntilFixed {
        storage.contains("!(12)") should be(true)
      }
      _ <- node.tearDown()
    } yield ()
  }

  it should "allow multiple deploys in a single block" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._

    val startTime = System.currentTimeMillis()
    val source    = " for(@x <- @0){ @0!(x) } | @0!(0) "
    val deploys = (source #:: source #:: Stream.empty[String]).zipWithIndex
      .map(s => ProtoUtil.sourceDeploy(s._1, startTime + s._2, Integer.MAX_VALUE))
    for {
      _                 <- deploys.traverse_(MultiParentCasper[Effect].deploy(_))
      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
      _                 <- MultiParentCasper[Effect].addBlock(block)
      result            <- MultiParentCasper[Effect].contains(block) shouldBeF true
      _                 <- node.tearDown()
    } yield result
  }

  it should "reject unsigned blocks" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      basicDeployData <- ProtoUtil.basicDeployData[Effect](0)
      createBlockResult <- MultiParentCasper[Effect].deploy(basicDeployData) *> MultiParentCasper[
                            Effect
                          ].createBlock
      Created(block) = createBlockResult
      invalidBlock   = block.withSig(ByteString.EMPTY)
      _              <- MultiParentCasper[Effect].addBlock(invalidBlock)
      _              = logEff.warns.count(_.contains("because block signature")) should be(1)
      _              <- node.tearDownNode()
      result <- validateBlockStore(node) { blockStore =>
                 blockStore.getBlockMessage(block.blockHash) shouldBeF None
               }
    } yield result
  }

  it should "not request invalid blocks from peers" in effectTest {
    val dummyContract =
      ByteString.readFrom(getClass.getResourceAsStream("/helloname.wasm"))

    val List(data0, data1) =
      (for (i <- 0 to 1)
        yield ProtoUtil.sourceDeploy(dummyContract, i.toLong, Long.MaxValue)).toList

    for {
      nodes              <- networkEff(validatorKeys.take(2), genesis, transforms)
      List(node0, node1) = nodes.toList
      unsignedBlock <- (node0.casperEff.deploy(data0) *> node0.casperEff.createBlock)
                        .map {
                          case Created(block) =>
                            block.copy(sigAlgorithm = "invalid", sig = ByteString.EMPTY)
                        }
      _ <- node0.casperEff.addBlock(unsignedBlock)
      _ <- node1.clearMessages() //node1 misses this block

      signedBlock <- (node0.casperEff.deploy(data1) *> node0.casperEff.createBlock)
                      .map { case Created(block) => block }

      _ <- node0.casperEff.addBlock(signedBlock)
      _ <- node1.receive() //receives block1; should not ask for block0

      _ <- node0.casperEff.contains(unsignedBlock) shouldBeF false
      _ <- node1.casperEff.contains(unsignedBlock) shouldBeF false

    } yield ()
  }

  it should "reject blocks not from bonded validators" in effectTest {
    val node = standaloneEff(genesis, transforms, otherSk)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      basicDeployData <- ProtoUtil.basicDeployData[Effect](0)
      createBlockResult <- MultiParentCasper[Effect].deploy(basicDeployData) *> MultiParentCasper[
                            Effect
                          ].createBlock
      Created(signedBlock) = createBlockResult
      _                    <- MultiParentCasper[Effect].addBlock(signedBlock)
      _                    = exactly(1, logEff.warns) should include("Ignoring block")
      _                    <- node.tearDownNode()
      result <- validateBlockStore(node) { blockStore =>
                 blockStore.getBlockMessage(signedBlock.blockHash) shouldBeF None
               }
    } yield result
  }

  it should "propose blocks it adds to peers" in effectTest {
    for {
      nodes                <- networkEff(validatorKeys.take(2), genesis, transforms)
      deployData           <- ProtoUtil.basicDeployData[Effect](0)
      createBlockResult    <- nodes(0).casperEff.deploy(deployData) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      _                    <- nodes(0).casperEff.addBlock(signedBlock)
      _                    <- nodes(1).receive()
      result               <- nodes(1).casperEff.contains(signedBlock) shouldBeF true
      _                    <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              blockStore
                .getBlockMessage(signedBlock.blockHash)
                .map(_.map(_.toProtoString)) shouldBeF Some(
                signedBlock.toProtoString
              )
            }(nodes(0).metricEff, nodes(0).logEff)
          }
    } yield result
  }

  it should "add a valid block from peer" in effectTest {
    for {
      nodes                      <- networkEff(validatorKeys.take(2), genesis, transforms)
      deployData                 <- ProtoUtil.basicDeployData[Effect](1)
      createBlockResult          <- nodes(0).casperEff.deploy(deployData) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult
      _                          <- nodes(0).casperEff.addBlock(signedBlock1Prime)
      _                          <- nodes(1).receive()
      _                          = nodes(1).logEff.infos.count(_ startsWith "Added") should be(1)
      result                     = nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(0)
      _                          <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              blockStore.getBlockMessage(signedBlock1Prime.blockHash) shouldBeF Some(
                signedBlock1Prime
              )
            }(nodes(0).metricEff, nodes(0).logEff)
          }
    } yield result
  }

  it should "handle multi-parent blocks correctly" in effectTest {
    for {
      nodes       <- networkEff(validatorKeys.take(2), genesis, transforms)
      deployData0 <- ProtoUtil.basicDeployData[Effect](0)
      deployData2 <- ProtoUtil.basicDeployData[Effect](2)
      deploys = Vector(
        deployData0,
        ProtoUtil.sourceDeploy(
          "@1!(1) | for(@x <- @1){ @1!(x) }",
          System.currentTimeMillis(),
          Integer.MAX_VALUE
        ),
        deployData2
      )
      createBlockResult0 <- nodes(0).casperEff.deploy(deploys(0)) *> nodes(0).casperEff.createBlock
      createBlockResult1 <- nodes(1).casperEff.deploy(deploys(1)) *> nodes(1).casperEff.createBlock
      Created(block0)    = createBlockResult0
      Created(block1)    = createBlockResult1
      _                  <- nodes(0).casperEff.addBlock(block0)
      _                  <- nodes(1).casperEff.addBlock(block1)
      _                  <- nodes(0).receive()
      _                  <- nodes(1).receive()
      _                  <- nodes(0).receive()
      _                  <- nodes(1).receive()

      //multiparent block joining block0 and block1 since they do not conflict
      multiparentCreateBlockResult <- nodes(0).casperEff
                                       .deploy(deploys(2)) *> nodes(0).casperEff.createBlock
      Created(multiparentBlock) = multiparentCreateBlockResult
      _                         <- nodes(0).casperEff.addBlock(multiparentBlock)
      _                         <- nodes(1).receive()

      _ = nodes(0).logEff.warns shouldBe empty
      _ = nodes(1).logEff.warns shouldBe empty
      _ = multiparentBlock.header.get.parentsHashList.size shouldBe 2
      _ = nodes(0).casperEff.contains(multiparentBlock) shouldBeF true
      _ = nodes(1).casperEff.contains(multiparentBlock) shouldBeF true

      finalTuplespace <- nodes(0).casperEff
                          .storageContents(ProtoUtil.postStateHash(multiparentBlock))
      _ = pendingUntilFixed {
        finalTuplespace.contains("@{0}!(0)") shouldBe true
        finalTuplespace.contains("@{1}!(1)") shouldBe true
        finalTuplespace.contains("@{2}!(2)") shouldBe true
      }

      _ = nodes.foreach(_.tearDown())
    } yield ()
  }

  //todo we need some genenis Contract to pass this test
//  it should "allow bonding and distribute the joining fee" in {
//    val nodes =
//      HashSetCasperTestNode.network(
//        validatorKeys :+ otherSk,
//        genesis,
//        storageSize = 1024L * 1024 * 10
//      )
//    implicit val runtimeManager = nodes(0).runtimeManager
//    val pubKey                  = Base16.encode(ethPubKeys.head.bytes.drop(1))
//    val secKey                  = ethPivKeys.head.bytes
//    val ethAddress              = ethAddresses.head
//    val bondKey                 = Base16.encode(otherPk)
//    val walletUnlockDeploy =
//      RevIssuanceTest.preWalletUnlockDeploy(ethAddress, pubKey, secKey, "unlockOut")
//    val bondingForwarderAddress = BondingUtil.bondingForwarderAddress(ethAddress)
//    val bondingForwarderDeploy = ProtoUtil.sourceDeploy(
//      BondingUtil.bondingForwarderDeploy(bondKey, ethAddress),
//      System.currentTimeMillis(),
//      Integer.MAX_VALUE
//    )
//    val transferStatusOut = BondingUtil.transferStatusOut(ethAddress)
//    val bondingTransferDeploy =
//      RevIssuanceTest.walletTransferDeploy(
//        0,
//        wallets.head.initRevBalance.toLong,
//        bondingForwarderAddress,
//        transferStatusOut,
//        pubKey,
//        secKey
//      )
//
//    val Created(block1) = nodes(0).casperEff.deploy(walletUnlockDeploy) *> nodes(0).casperEff
//      .deploy(bondingForwarderDeploy) *> nodes(0).casperEff.createBlock
//    val block1Status = nodes(0).casperEff.addBlock(block1)
//    nodes.foreach(_.receive) //send to all peers

  it should "allow bonding via the faucet" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node.{casperEff, logEff}

    val (sk, pk)    = Ed25519.newKeyPair
    val pkStr       = Base16.encode(pk)
    val amount      = 314L
    val forwardCode = BondingUtil.bondingForwarderDeploy(pkStr, pkStr)
    for {
      bondingCode <- BondingUtil.faucetBondDeploy[Effect](amount, "ed25519", pkStr, sk)
      forwardDeploy = ProtoUtil.sourceDeploy(
        forwardCode,
        System.currentTimeMillis(),
        Integer.MAX_VALUE
      )
      bondingDeploy = ProtoUtil.sourceDeploy(
        bondingCode,
        forwardDeploy.timestamp + 1,
        Integer.MAX_VALUE
      )
      createBlockResult1 <- casperEff.deploy(forwardDeploy) *> casperEff.createBlock
      Created(block1)    = createBlockResult1
      block1Status       <- casperEff.addBlock(block1)
      createBlockResult2 <- casperEff.deploy(bondingDeploy) *> casperEff.createBlock
      Created(block2)    = createBlockResult2
      block2Status       <- casperEff.addBlock(block2)

      _        = logEff.warns shouldBe empty
      oldBonds = block1.getBody.getState.bonds
      newBonds = block2.getBody.getState.bonds
      _        = block1Status shouldBe Valid
      _        = block2Status shouldBe Valid
      // Need bonding to be implemented and the bonding code to actually do something.
      _ = pendingUntilFixed {
        (oldBonds.size + 1) shouldBe newBonds.size
      }

      _ = node.tearDown()
    } yield ()
  }

  it should "not fail if the forkchoice changes after a bonding event" in {
    val localValidators = validatorKeys.take(3)
    val localBonds      = localValidators.map(Ed25519.toPublic).zip(List(10L, 30L, 5000L)).toMap
    val BlockMsgWithTransform(Some(localGenesis), localTransforms) =
      buildGenesis(Nil, localBonds, 1L, Long.MaxValue, Faucet.basicWalletFaucet, 0L)
    for {
      nodes <- networkEff(
                localValidators,
                localGenesis,
                localTransforms
              )

      (sk, pk)    = Ed25519.newKeyPair
      pkStr       = Base16.encode(pk)
      forwardCode = BondingUtil.bondingForwarderDeploy(pkStr, pkStr)
      bondingCode <- BondingUtil.faucetBondDeploy[Effect](50, "ed25519", pkStr, sk)(
                      Sync[Effect]
                    )
      forwardDeploy = ProtoUtil.sourceDeploy(
        forwardCode,
        System.currentTimeMillis(),
        Integer.MAX_VALUE
      )
      bondingDeploy = ProtoUtil.sourceDeploy(
        bondingCode,
        forwardDeploy.timestamp + 1,
        Integer.MAX_VALUE
      )

      _                    <- nodes.head.casperEff.deploy(forwardDeploy)
      _                    <- nodes.head.casperEff.deploy(bondingDeploy)
      createBlockResult1   <- nodes.head.casperEff.createBlock
      Created(bondedBlock) = createBlockResult1

      bondedBlockStatus <- nodes.head.casperEff
                            .addBlock(bondedBlock)
      _ <- nodes(1).receive()
      _ <- nodes.head.receive()
      _ <- nodes(2).clearMessages() //nodes(2) misses bonding

      createBlockResult2 <- {
        val n = nodes(1)
        import n.casperEff._
        (ProtoUtil.basicDeployData[Effect](0) >>= deploy) *> createBlock
      }
      Created(block2) = createBlockResult2
      status2         <- nodes(1).casperEff.addBlock(block2)
      _               <- nodes.head.receive()
      _               <- nodes(1).receive()
      _               <- nodes(2).clearMessages() //nodes(2) misses block built on bonding

      createBlockResult3 <- { //nodes(2) proposes a block
        val n = nodes(2)
        import n.casperEff._
        (ProtoUtil.basicDeployData[Effect](1) >>= deploy) *> createBlock
      }
      Created(block3) = createBlockResult3
      status3         <- nodes(2).casperEff.addBlock(block3)
      _               <- nodes.toList.traverse_(_.receive())
      //Since weight of nodes(2) is higher than nodes(0) and nodes(1)
      //their fork-choice changes, thus the new validator
      //is no longer bonded

      createBlockResult4 <- { //nodes(0) proposes a new block
        val n = nodes.head
        import n.casperEff._
        (ProtoUtil.basicDeployData[Effect](2) >>= deploy) *> createBlock
      }
      Created(block4) = createBlockResult4
      status4         <- nodes.head.casperEff.addBlock(block4)
      _               <- nodes.toList.traverse_(_.receive())

      _      = bondedBlockStatus shouldBe Valid
      _      = status2 shouldBe Valid
      _      = status3 shouldBe Valid
      result = status4 shouldBe Valid
      _      = nodes.foreach(_.logEff.warns shouldBe Nil)

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield result
  }

  it should "reject addBlock when there exist deploy by the same (user, millisecond timestamp) in the chain" in {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis, transforms)
      deployDatas <- (0 to 2).toList
                      .traverse[Effect, DeployData](i => ProtoUtil.basicDeployData[Effect](i))
      deployPrim0 = deployDatas(1)
        .withTimestamp(deployDatas(0).timestamp)
        .withUser(deployDatas(0).user) // deployPrim0 has the same (user, millisecond timestamp) with deployDatas(0)
      createdBlockResult1 <- nodes(0).casperEff
                              .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createdBlockResult1
      _                     <- nodes(0).casperEff.addBlock(signedBlock1)
      _                     <- nodes(1).receive() // receive block1

      createBlockResult2 <- nodes(0).casperEff
                             .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
      Created(signedBlock2) = createBlockResult2
      _                     <- nodes(0).casperEff.addBlock(signedBlock2)
      _                     <- nodes(1).receive() // receive block2

      createBlockResult3 <- nodes(0).casperEff
                             .deploy(deployDatas(2)) *> nodes(0).casperEff.createBlock
      Created(signedBlock3) = createBlockResult3
      _                     <- nodes(0).casperEff.addBlock(signedBlock3)
      _                     <- nodes(1).receive() // receive block3

      _ <- nodes(1).casperEff.contains(signedBlock3) shouldBeF true

      createBlockResult4 <- nodes(1).casperEff
                             .deploy(deployPrim0) *> nodes(1).casperEff.createBlock
      Created(signedBlock4) = createBlockResult4
      _ <- nodes(1).casperEff
            .addBlock(signedBlock4) // should succeed
      _ <- nodes(0).receive() // still receive signedBlock4

      result <- nodes(1).casperEff
                 .contains(signedBlock4) shouldBeF true // Invalid blocks are still added
      _ <- nodes(0).casperEff.contains(signedBlock4) shouldBeF (false)
      _ = nodes(0).logEff.warns
        .count(_ contains "found deploy by the same (user, millisecond timestamp) produced") shouldBe (1)
      _ <- nodes.map(_.tearDownNode()).toList.sequence

      _ = nodes.toList.traverse_[Effect, Assertion] { node =>
        validateBlockStore(node) { blockStore =>
          for {
            _ <- blockStore.getBlockMessage(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
            _ <- blockStore.getBlockMessage(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
            result <- blockStore.getBlockMessage(signedBlock3.blockHash) shouldBeF Some(
                       signedBlock3
                     )
          } yield result
        }(nodes(0).metricEff, nodes(0).logEff)
      }
    } yield result
  }

  it should "ask peers for blocks it is missing" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(3), genesis, transforms)
      deployDatas = Vector(
        "for(_ <- @1){ Nil } | @1!(1)",
        "@2!(2)"
      ).zipWithIndex
        .map(
          d => ProtoUtil.sourceDeploy(d._1, System.currentTimeMillis() + d._2, Integer.MAX_VALUE)
        )

      createBlockResult1 <- nodes(0).casperEff
                             .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1

      _ <- nodes(0).casperEff.addBlock(signedBlock1)
      _ <- nodes(1).receive()
      _ <- nodes(2).clearMessages() //nodes(2) misses this block

      createBlockResult2 <- nodes(0).casperEff
                             .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
      Created(signedBlock2) = createBlockResult2

      _ <- nodes(0).casperEff.addBlock(signedBlock2)
      _ <- nodes(1).receive() //receives block2
      _ <- nodes(2).receive() //receives block2; asks for block1
      _ <- nodes(1).receive() //receives request for block1; sends block1
      _ <- nodes(2).receive() //receives block1; adds both block1 and block2

      _ <- nodes(2).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(2).casperEff.contains(signedBlock2) shouldBeF true
      // TransportLayer gets 1 block, 1 is missing. GossipService gets 1 hash, 2 block missing.
      _ = nodes(2).logEff.infos
        .count(_ startsWith "Requested missing block") should be >= 1
      // TransportLayer controlled by .receive calls, only node(1) responds. GossipService has unlimited retrieve, goes to node(0).
      result = (0 to 1)
        .flatMap(nodes(_).logEff.infos)
        .count(
          s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
        ) should be >= 1

      _ <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              for {
                _ <- blockStore.getBlockMessage(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
                result <- blockStore.getBlockMessage(signedBlock2.blockHash) shouldBeF Some(
                           signedBlock2
                         )
              } yield result
            }(nodes(0).metricEff, nodes(0).logEff)
          }
    } yield result
  }

  /*
   *   DAG Looks like this:
   *
   *              h1
   *             /  \
   *            g1   g2
   *            |  X |
   *            f1   f2
   *             \  /
   *              e1
   *              |
   *              d1
   *             /  \
   *            c1   c2
   *            |  X |
   *            b1   b2
   *            |  X |
   *            a1   a2
   *             \  /
   *           genesis
   *
   *  f2 has in its justifications list c2. This should be handled properly.
   *
   */
  it should "ask peers for blocks it is missing and add them" in effectTest {
    //TODO: figure out a way to get wasm into deploys for tests
    val deployDatasFs = Vector[() => DeployData](
      () => ProtoUtil.sourceDeploy("1", System.currentTimeMillis, Integer.MAX_VALUE),
      () => ProtoUtil.sourceDeploy("2", System.currentTimeMillis, Integer.MAX_VALUE)
    )

    /** Create a block from a deploy and add it on that node. */
    def deploy(node: HashSetCasperTestNode[Effect], dd: DeployData): Effect[BlockMessage] =
      for {
        createBlockResult1    <- node.casperEff.deploy(dd) *> node.casperEff.createBlock
        Created(signedBlock1) = createBlockResult1

        _ <- node.casperEff.addBlock(signedBlock1)
      } yield signedBlock1

    /** nodes 0 and 1 create blocks in parallel; node 2 misses both, e.g. a1 and a2. */
    def stepSplit(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- deploy(nodes(0), deployDatasFs(0).apply())
        _ <- deploy(nodes(1), deployDatasFs(1).apply())

        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).clearMessages() //nodes(2) misses this block
      } yield ()

    /** node 0 creates a block; node 1 gets it but node 2 doesn't. */
    def stepSingle(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- deploy(nodes(0), deployDatasFs(0).apply())

        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).clearMessages() //nodes(2) misses this block
      } yield ()

    def propagate(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).receive()
      } yield ()

    for {
      nodes <- networkEff(validatorKeys.take(3), genesis, transforms)

      _ <- stepSplit(nodes) // blocks a1 a2
      _ <- stepSplit(nodes) // blocks b1 b2
      _ <- stepSplit(nodes) // blocks c1 c2

      _ <- stepSingle(nodes) // block d1
      _ <- stepSingle(nodes) // block e1

      _ <- stepSplit(nodes) // blocks f1 f2
      _ <- stepSplit(nodes) // blocks g1 g2

      // this block will be propagated to all nodes and force nodes(2) to ask for missing blocks.
      br <- deploy(nodes(0), deployDatasFs(0).apply()) // block h1

      // node(0) just created this block, so it should have it.
      _ <- nodes(0).casperEff.contains(br) shouldBeF true
      // Let every node get everything.
      _ <- List.fill(22)(propagate(nodes)).toList.sequence
      // By now node(2) should have received all dependencies and added block h1
      _ <- nodes(2).casperEff.contains(br) shouldBeF true
      // And if we create one more block on top of h1 it should be the only parent.
      nr <- deploy(nodes(2), deployDatasFs(0).apply())
      _ = nr.header.get.parentsHashList.map(PrettyPrinter.buildString(_)) shouldBe Seq(
        PrettyPrinter.buildString(br.blockHash)
      )

      _ <- nodes.map(_.tearDownNode()).toList.sequence
    } yield ()
  }

  it should "ignore adding equivocation blocks" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis, transforms)

      // Creates a pair that constitutes equivocation blocks
      basicDeployData0 <- ProtoUtil.basicDeployData[Effect](0)
      createBlockResult1 <- nodes(0).casperEff
                             .deploy(basicDeployData0) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      basicDeployData1      <- ProtoUtil.basicDeployData[Effect](1)
      createBlockResult1Prime <- nodes(0).casperEff
                                  .deploy(basicDeployData1) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult1Prime

      _ <- nodes(0).casperEff.addBlock(signedBlock1)
      _ <- nodes(1).receive()
      _ <- nodes(0).casperEff.addBlock(signedBlock1Prime)
      _ <- nodes(1).receive()

      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      result <- nodes(1).casperEff
                 .contains(signedBlock1Prime) shouldBeF false // we still add the equivocation pair

      _ <- nodes(0).tearDownNode()
      _ <- nodes(1).tearDownNode()
      _ <- validateBlockStore(nodes(1)) { blockStore =>
            for {
              _      <- blockStore.getBlockMessage(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
              result <- blockStore.getBlockMessage(signedBlock1Prime.blockHash) shouldBeF None
            } yield result
          }(nodes(0).metricEff, nodes(0).logEff)
    } yield result
  }

  // See [[/docs/casper/images/minimal_equivocation_neglect.png]] but cross out genesis block
  it should "not ignore equivocation blocks that are required for parents of proper nodes" in effectTest {
    for {
      nodes       <- networkEff(validatorKeys.take(3), genesis, transforms)
      deployDatas <- (0 to 5).toList.traverse[Effect, DeployData](ProtoUtil.basicDeployData[Effect])

      // Creates a pair that constitutes equivocation blocks.
      createBlockResult1 <- nodes(0).casperEff
                             .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      // Create a 2nd block without storing the 1st, so they have the same parent.
      createBlockResult1Prime <- nodes(0).casperEff
                                  .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult1Prime

      // NOTE: Adding a block created (but not stored) by node(0) directly to node(1)
      // is not something you can normally achieve with gossiping.
      _ <- nodes(1).casperEff.superAddBlock(signedBlock1)
      _ <- nodes(0).clearMessages() //nodes(0) misses this block
      _ <- nodes(2).clearMessages() //nodes(2) misses this block

      _ <- nodes(0).casperEff.addBlock(signedBlock1Prime)
      _ <- nodes(2).receive()
      _ <- nodes(1).clearMessages() //nodes(1) misses this block

      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(2).casperEff.contains(signedBlock1) shouldBeF false

      _ <- nodes(1).casperEff.contains(signedBlock1Prime) shouldBeF false
      _ <- nodes(2).casperEff.contains(signedBlock1Prime) shouldBeF true

      // Now that node(1) and node(2) have different blocks, they each create one on top of theirs.
      createBlockResult2 <- nodes(1).casperEff
                             .deploy(deployDatas(2)) *> nodes(1).casperEff.createBlock
      Created(signedBlock2) = createBlockResult2
      createBlockResult3 <- nodes(2).casperEff
                             .deploy(deployDatas(3)) *> nodes(2).casperEff.createBlock
      Created(signedBlock3) = createBlockResult3

      _ <- nodes(2).casperEff.addBlock(signedBlock3)
      _ <- nodes(1).casperEff.addBlock(signedBlock2)
      _ <- nodes(2).clearMessages() //nodes(2) ignores block2
      _ <- nodes(1).receive() // receives block3; asks for block1'
      _ <- nodes(2).receive() // receives request for block1'; sends block1'
      _ <- nodes(1).receive() // receives block1'; adds both block3 and block1'

      // node(1) should have both block2 and block3 at this point and recognize the equivocation.
      // Because node(1) will also see that the singedBlock3 is building on top of signedBlock1Prime,
      // it should download signedBlock1Prime as an `AdmissibleEquivocation`; otherwise if it didn't
      // have an offspring it would be an `IgnorableEquivocation` and dropped.
      _ <- nodes(1).casperEff.contains(signedBlock3) shouldBeF true
      _ <- nodes(1).casperEff.contains(signedBlock1Prime) shouldBeF true

      createBlockResult4 <- nodes(1).casperEff
                             .deploy(deployDatas(4)) *> nodes(1).casperEff.createBlock
      Created(signedBlock4) = createBlockResult4
      _                     <- nodes(1).casperEff.addBlock(signedBlock4)

      // Node 1 should contain both blocks constituting the equivocation
      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(1).casperEff.contains(signedBlock1Prime) shouldBeF true

      _ <- nodes(1).casperEff
            .contains(signedBlock4) shouldBeF true // However, in invalidBlockTracker

      _ = nodes(1).logEff.infos.count(_ contains "Added admissible equivocation") should be(1)
      _ = nodes(2).logEff.warns.size should be(0)
      _ = nodes(1).logEff.warns.size should be(1)
      _ = nodes(0).logEff.warns.size should be(0)

      _ <- nodes(1).casperEff
            .normalizedInitialFault(ProtoUtil.weightMap(genesis)) shouldBeF 1f / (1f + 3f + 5f + 7f)
      _ <- nodes.map(_.tearDownNode()).toList.sequence

      _ <- validateBlockStore(nodes(0)) { blockStore =>
            for {
              _ <- blockStore.getBlockMessage(signedBlock1.blockHash) shouldBeF None
              result <- blockStore.getBlockMessage(signedBlock1Prime.blockHash) shouldBeF Some(
                         signedBlock1Prime
                       )
            } yield result
          }(nodes(0).metricEff, nodes(0).logEff)
      _ <- validateBlockStore(nodes(1)) { blockStore =>
            for {
              _ <- blockStore.getBlockMessage(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
              result <- blockStore.getBlockMessage(signedBlock4.blockHash) shouldBeF Some(
                         signedBlock4
                       )
            } yield result
          }(nodes(1).metricEff, nodes(1).logEff)
      result <- validateBlockStore(nodes(2)) { blockStore =>
                 for {
                   _ <- blockStore.getBlockMessage(signedBlock3.blockHash) shouldBeF Some(
                         signedBlock3
                       )
                   result <- blockStore.getBlockMessage(signedBlock1Prime.blockHash) shouldBeF Some(
                              signedBlock1Prime
                            )
                 } yield result
               }(nodes(2).metricEff, nodes(2).logEff)
    } yield result
  }

  it should "prepare to slash a block that includes a invalid block pointer" in effectTest {
    for {
      nodes           <- networkEff(validatorKeys.take(3), genesis, transforms)
      deploys         <- (0 to 5).toList.traverse(i => ProtoUtil.basicDeploy[Effect](i))
      deploysWithCost = deploys.map(d => ProcessedDeploy(deploy = Some(d))).toIndexedSeq

      createBlockResult <- nodes(0).casperEff
                            .deploy(deploys(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      signedInvalidBlock = BlockUtil.resignBlock(
        signedBlock.withSeqNum(-2),
        nodes(0).validatorId.privateKey
      ) // Invalid seq num

      // This is going to be signed by node(1)
      blockWithInvalidJustification <- buildBlockWithInvalidJustification(
                                        nodes,
                                        deploysWithCost,
                                        signedInvalidBlock
                                      )

      // Adding the block that only refers to an invalid node as justification; it will not send notifications.
      _ <- nodes(1).casperEff
            .addBlock(blockWithInvalidJustification)
      _ <- nodes(0)
            .clearMessages() // nodes(0) rejects normal adding process for blockThatPointsToInvalidBlock

      // NOTE: Disabled the following code becasue it relies on the TransportLayer message types.
      // signedInvalidBlockPacketMessage = packet(
      //   nodes(0).local,
      //   transport.BlockMessage,
      //   signedInvalidBlock.toByteString
      // )
      // _ <- nodes(0).transportLayerEff.send(nodes(1).local, signedInvalidBlockPacketMessage)
      // _ <- nodes(1).receive() // receives signedInvalidBlock; attempts to add both blocks
      // NOTE: Instead of the above let's just add it directly to node(1); need to use `superAddBlock` becuase node(0) doesn't have this block.
      _ <- nodes(1).casperEff
            .superAddBlock(signedInvalidBlock)

      result = nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(1)
      _      <- nodes.map(_.tearDown()).toList.sequence
    } yield result
  }

  it should "handle a long chain of block requests appropriately" in effectTest {
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis,
                transforms,
                storageSize = 1024L * 1024 * 10
              )

      _ <- (0 to 9).toList.traverse_[Effect, Unit] { i =>
            for {
              deploy <- ProtoUtil.basicDeployData[Effect](i)
              createBlockResult <- nodes(0).casperEff
                                    .deploy(deploy) *> nodes(0).casperEff.createBlock
              Created(block) = createBlockResult

              _ <- nodes(0).casperEff.addBlock(block)
              _ <- nodes(1).clearMessages() //nodes(1) misses this block
            } yield ()
          }
      deployData10 <- ProtoUtil.basicDeployData[Effect](10)
      createBlock11Result <- nodes(0).casperEff.deploy(deployData10) *> nodes(
                              0
                            ).casperEff.createBlock
      Created(block11) = createBlock11Result
      _                <- nodes(0).casperEff.addBlock(block11)

      // Cycle of requesting and passing blocks until block #9 from nodes(0) to nodes(1)
      _ <- (0 to 8).toList.traverse_[Effect, Unit] { i =>
            nodes(1).receive().void *> nodes(0).receive().void
          }

      // We simulate a network failure here by not allowing block #10 to get passed to nodes(1)
      // And then we assume fetchDependencies eventually gets called
      _ <- nodes(1).casperEff.fetchDependencies
      _ <- nodes(0).receive()

      reqCnt = nodes(1).logEff.infos.count(_ startsWith "Requested missing block")
      _      = reqCnt should be >= 10 // TransportLayer
      _      = reqCnt should be <= 11 // GossipService

      resCnt = nodes(0).logEff.infos.count(
        s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
      )
      _ = resCnt shouldBe reqCnt

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "increment last finalized block as appropriate in round robin" in effectTest {
    val stake      = 10L
    val equalBonds = validators.map(_ -> stake).toMap
    val BlockMsgWithTransform(Some(genesisWithEqualBonds), transformsWithEqualBonds) =
      buildGenesis(Seq.empty, equalBonds, 1L, Long.MaxValue, Faucet.noopFaucet, 0L)

    def checkLastFinalizedBlock(
        node: HashSetCasperTestNode[Effect],
        expected: BlockMessage
    )(implicit pos: org.scalactic.source.Position): Effect[Unit] =
      node.casperEff.lastFinalizedBlock map { block =>
        PrettyPrinter.buildString(block) shouldBe PrettyPrinter.buildString(expected)
        ()
      }

    for {
      nodes <- networkEff(
                validatorKeys.take(3),
                genesisWithEqualBonds,
                transformsWithEqualBonds,
                faultToleranceThreshold = 0f // With equal bonds this should allow the final block to move as expected.
              )
      deployDatas <- (0 to 7).toList.traverse(i => ProtoUtil.basicDeployData[Effect](i))

      createBlock1Result <- nodes(0).casperEff
                             .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(block1) = createBlock1Result
      _               <- nodes(0).casperEff.addBlock(block1)
      _               <- nodes(1).receive()
      _               <- nodes(2).receive()

      createBlock2Result <- nodes(1).casperEff
                             .deploy(deployDatas(1)) *> nodes(1).casperEff.createBlock
      Created(block2) = createBlock2Result
      _               <- nodes(1).casperEff.addBlock(block2)
      _               <- nodes(0).receive()
      _               <- nodes(2).receive()

      createBlock3Result <- nodes(2).casperEff
                             .deploy(deployDatas(2)) *> nodes(2).casperEff.createBlock
      Created(block3) = createBlock3Result
      _               <- nodes(2).casperEff.addBlock(block3)
      _               <- nodes(0).receive()
      _               <- nodes(1).receive()

      createBlock4Result <- nodes(0).casperEff
                             .deploy(deployDatas(3)) *> nodes(0).casperEff.createBlock
      Created(block4) = createBlock4Result
      _               <- nodes(0).casperEff.addBlock(block4)
      _               <- nodes(1).receive()
      _               <- nodes(2).receive()

      createBlock5Result <- nodes(1).casperEff
                             .deploy(deployDatas(4)) *> nodes(1).casperEff.createBlock
      Created(block5) = createBlock5Result
      _               <- nodes(1).casperEff.addBlock(block5)
      _               <- nodes(0).receive()
      _               <- nodes(2).receive()

      _     <- checkLastFinalizedBlock(nodes(0), block1)
      state <- nodes(0).casperState.read
      _     = state.deployBuffer.size should be(1)

      createBlock6Result <- nodes(2).casperEff
                             .deploy(deployDatas(5)) *> nodes(2).casperEff.createBlock
      Created(block6) = createBlock6Result
      _               <- nodes(2).casperEff.addBlock(block6)
      _               <- nodes(0).receive()
      _               <- nodes(1).receive()

      _     <- checkLastFinalizedBlock(nodes(0), block2)
      state <- nodes(0).casperState.read
      _     = state.deployBuffer.size should be(1)

      createBlock7Result <- nodes(0).casperEff
                             .deploy(deployDatas(6)) *> nodes(0).casperEff.createBlock
      Created(block7) = createBlock7Result
      _               <- nodes(0).casperEff.addBlock(block7)
      _               <- nodes(1).receive()
      _               <- nodes(2).receive()

      _ <- checkLastFinalizedBlock(nodes(0), block3)
      _ = state.deployBuffer.size should be(1)

      createBlock8Result <- nodes(1).casperEff
                             .deploy(deployDatas(7)) *> nodes(1).casperEff.createBlock
      Created(block8) = createBlock8Result
      _               <- nodes(1).casperEff.addBlock(block8)
      _               <- nodes(0).receive()
      _               <- nodes(2).receive()

      _     <- checkLastFinalizedBlock(nodes(0), block4)
      state <- nodes(0).casperState.read
      _     = state.deployBuffer.size should be(1)

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "fail when deploying with insufficient gas" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deployData        <- ProtoUtil.basicDeployData[Effect](0).map(_.withGasLimit(1))
      _                 <- node.casperEff.deploy(deployData)
      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
    } yield {
      cancelUntilFixed("FIXME: Implement cost accounting!")
      //assert(block.body.get.deploys.head.errored)
    }
  }

  it should "succeed if given enough gas for deploy" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deployData <- ProtoUtil.basicDeployData[Effect](0).map(_.withGasLimit(100))
      _          <- node.casperEff.deploy(deployData)

      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
    } yield assert(!block.body.get.deploys.head.errored)
  }

  private def buildBlockWithInvalidJustification(
      nodes: IndexedSeq[HashSetCasperTestNode[Effect]],
      deploys: immutable.IndexedSeq[ProcessedDeploy],
      signedInvalidBlock: BlockMessage
  ): Effect[BlockMessage] = {
    val postState =
      RChainState().withBonds(ProtoUtil.bonds(genesis)).withBlockNumber(1)
    val postStateHash = Blake2b256.hash(postState.toByteArray)
    val header = Header()
      .withPostStateHash(ByteString.copyFrom(postStateHash))
      .withParentsHashList(signedInvalidBlock.header.get.parentsHashList)
      .withDeploysHash(ProtoUtil.protoSeqHash(deploys))
    val blockHash = Blake2b256.hash(header.toByteArray)
    val body      = Body().withState(postState).withDeploys(deploys)
    val serializedJustifications =
      Seq(Justification(signedInvalidBlock.sender, signedInvalidBlock.blockHash))
    val serializedBlockHash = ByteString.copyFrom(blockHash)
    val blockThatPointsToInvalidBlock =
      BlockMessage(serializedBlockHash, Some(header), Some(body), serializedJustifications)
    nodes(1).casperEff.blockDag.flatMap { dag =>
      ProtoUtil.signBlock[Effect](
        blockThatPointsToInvalidBlock,
        dag,
        validators(1),
        validatorKeys(1),
        "ed25519",
        "casperlabs"
      )
    }
  }
}

object HashSetCasperTest {
  def validateBlockStore[R](
      node: HashSetCasperTestNode[Effect]
  )(f: BlockStore[Effect] => Effect[R])(implicit metrics: Metrics[Effect], log: Log[Effect]) =
    for {
      bs     <- BlockDagStorageTestFixture.createBlockStorage[Effect](node.blockStoreDir)
      result <- f(bs)
      _      <- bs.close()
      _      <- Sync[Effect].delay { node.blockStoreDir.recursivelyDelete() }
    } yield result

  def blockTuplespaceContents(
      block: BlockMessage
  )(implicit casper: MultiParentCasper[Effect]): Effect[String] = {
    val postStateHash = ProtoUtil.postStateHash(block)
    MultiParentCasper[Effect].storageContents(postStateHash)
  }

  def createBonds(validators: Seq[Array[Byte]]): Map[Array[Byte], Long] =
    validators.zipWithIndex.map { case (v, i) => v -> (2L * i.toLong + 1L) }.toMap

  def createGenesis(bonds: Map[Array[Byte], Long]): BlockMsgWithTransform =
    buildGenesis(Seq.empty, bonds, 1L, Long.MaxValue, Faucet.noopFaucet, 0L)

  def buildGenesis(
      wallets: Seq[PreWallet],
      bonds: Map[Array[Byte], Long],
      minimumBond: Long,
      maximumBond: Long,
      faucetCode: String => String,
      deployTimestamp: Long
  ): BlockMsgWithTransform = {
    implicit val logEff                  = new LogStub[Task]()
    val initial                          = Genesis.withoutContracts(bonds, 1L, deployTimestamp, "casperlabs")
    implicit val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
    val emptyStateHash                   = casperSmartContractsApi.emptyStateHash
    val validators = bonds.map {
      case (id, stake) => ProofOfStakeValidator(id, stake)
    }.toSeq
    val genesis = Genesis
      .withContracts[Task](
        initial,
        ProofOfStakeParams(minimumBond, maximumBond, validators),
        wallets,
        faucetCode,
        emptyStateHash,
        deployTimestamp
      )
      .unsafeRunSync
    genesis
  }
}
