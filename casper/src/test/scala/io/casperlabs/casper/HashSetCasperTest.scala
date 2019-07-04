package io.casperlabs.casper

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.consensus._
import Block.{Justification, ProcessedDeploy}
import com.github.ghik.silencer.silent
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.helper.HashSetCasperTestNode.Effect
import io.casperlabs.casper.helper._
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.{BondingUtil, ProtoUtil}
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Keccak256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.{Ed25519, Secp256k1}
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances.{LogStub, LogicalTime}
import io.casperlabs.shared.{Cell, FilesAPI, Log}
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.storage.BlockMsgWithTransform
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{Assertion, FlatSpec, Matchers}

import scala.collection.immutable

/** Run tests using the TransportLayer. */
class TransportLayerCasperTest extends HashSetCasperTest with TransportLayerCasperTestNodeFactory

/** Run tests using the GossipService and co. */
class GossipServiceCasperTest extends HashSetCasperTest with GossipServiceCasperTestNodeFactory

/** Run tests against whatever kind of test node the inheriting sets up. */
@silent("match may not be exhaustive")
abstract class HashSetCasperTest extends FlatSpec with Matchers with HashSetCasperTestNodeFactory {

  import HashSetCasperTest._

  implicit val timeEff = new LogicalTime[Effect]

  private val (otherSk, _)                = Ed25519.newKeyPair
  private val (validatorKeys, validators) = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
  //private val (ethPivKeys, ethPubKeys) = (1 to 4).map(_ => Secp256k1.newKeyPair).unzip
  //private val ethAddresses = ethPubKeys.map(pk => "0x" + Base16.encode(Keccak256.hash(pk.drop(1)).takeRight(20)))
  //private val wallets = ethAddresses.map(addr => PreWallet(addr, BigInt(10001)))
  private val wallets     = validators.map(key => PreWallet(key, BigInt(10001)))
  private val bonds       = createBonds(validators)
  private val minimumBond = 100L
  private val BlockMsgWithTransform(Some(genesis), transforms) =
    buildGenesis(wallets, bonds, minimumBond, Long.MaxValue, 0L)

  //put a new casper instance at the start of each
  //test since we cannot reset it
  behavior of "HashSetCasper"

  it should "accept deploys" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy <- ProtoUtil.basicDeploy[Effect](1L)
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
      deploy <- ProtoUtil.basicDeploy[Effect](1L)
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

    threadStatuses should matchPattern { case (Processed, Valid) | (Valid, Processed) => }
    node.tearDown().value.unsafeRunSync
  }

  it should "create blocks based on deploys" in effectTest {
    val node            = standaloneEff(genesis, transforms, validatorKeys.head)
    implicit val casper = node.casperEff

    for {
      deploy <- ProtoUtil.basicDeploy[Effect](1L)
      _      <- MultiParentCasper[Effect].deploy(deploy)

      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
      deploys           = block.body.get.deploys.flatMap(_.deploy)
      parents           = ProtoUtil.parentHashes(block)

      _ = parents should have size 1
      _ = parents.head shouldBe genesis.blockHash
      _ = deploys should have size 1
      _ = deploys.head shouldEqual deploy
      _ <- node.tearDown()
    } yield ()
  }

  it should "accept signed blocks" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy               <- ProtoUtil.basicDeploy[Effect](1L)
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

  def deploysFromString(start: Long, strs: List[String]): List[Deploy] =
    strs.zipWithIndex.map(
      s => ProtoUtil.basicDeploy(start + s._2, ByteString.copyFromUtf8(s._1), (s._2 + 1).toLong)
    )

  it should "be able to create a chain of blocks from different deploys" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._

    val start = System.currentTimeMillis()

    val deployDatas = deploysFromString(
      start,
      List(
        "contract @\"add\"(@x, @y, ret) = { ret!(x + y) }",
        "new unforgable in { @\"add\"!(5, 7, *unforgable) }"
      )
    )

    for {
      createBlockResult1 <- MultiParentCasper[Effect].deploy(deployDatas(0)) *> MultiParentCasper[
                             Effect
                           ].createBlock
      Created(signedBlock1) = createBlockResult1
      _                     <- MultiParentCasper[Effect].addBlock(signedBlock1)
      createBlockResult2 <- MultiParentCasper[Effect].deploy(deployDatas(1)) *> MultiParentCasper[
                             Effect
                           ].createBlock
      Created(signedBlock2) = createBlockResult2
      _                     <- MultiParentCasper[Effect].addBlock(signedBlock2)
      _                     = logEff.warns shouldBe empty
      _                     = ProtoUtil.parentHashes(signedBlock2) should be(Seq(signedBlock1.blockHash))
      dag                   <- MultiParentCasper[Effect].blockDag
      estimate              <- MultiParentCasper[Effect].estimator(dag)

      _ = estimate shouldBe IndexedSeq(signedBlock2.blockHash)
      _ <- node.tearDown()
    } yield ()
  }

  it should "allow multiple deploys in a single block" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._

    val startTime = System.currentTimeMillis()
    val source    = " for(@x <- @0){ @0!(x) } | @0!(0) "
    val deploys   = deploysFromString(startTime, List(source, source))

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
      basicDeployData <- ProtoUtil.basicDeploy[Effect](1L)
      createBlockResult <- MultiParentCasper[Effect].deploy(basicDeployData) *> MultiParentCasper[
                            Effect
                          ].createBlock
      Created(block) = createBlockResult
      invalidBlock   = block.withSignature(block.getSignature.withSig((ByteString.EMPTY)))
      _              <- MultiParentCasper[Effect].addBlock(invalidBlock) shouldBeF InvalidUnslashableBlock
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

    val data0 = ProtoUtil.basicDeploy(1, dummyContract, 1)
    val data1 = ProtoUtil.basicDeploy(2, dummyContract, 2)

    for {
      // NOTE: Not validating nonces because after creating the 1st block it expects the nonce will increase,
      // but instead we make the block invalid and try creating another again with the same deploy still in the buffer.
      // It would work if the nonces were tracked per pre-state-hash.
      nodes              <- networkEff(validatorKeys.take(2), genesis, transforms, validateNonces = false)
      List(node0, node1) = nodes.toList
      unsignedBlock <- (node0.casperEff.deploy(data0) *> node0.casperEff.createBlock)
                        .map {
                          case Created(block) =>
                            block.withSignature(
                              block.getSignature
                                .withSigAlgorithm("invalid")
                                .withSig(ByteString.EMPTY)
                            )
                        }
      _ <- node0.casperEff.addBlock(unsignedBlock)
      _ <- node1.clearMessages() //node1 misses this block

      signedBlock <- (node0.casperEff.deploy(data1) *> node0.casperEff.createBlock)
                      .map { case Created(block) => block }

      // NOTE: It won't actually include `data1` in the block because it still has `data0`.
      _ = signedBlock.getBody.deploys.map(_.getDeploy) should contain only (data0)

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
      basicDeployData <- ProtoUtil.basicDeploy[Effect](1L)
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
      deploy               <- ProtoUtil.basicDeploy[Effect](1L)
      createBlockResult    <- nodes(0).casperEff.deploy(deploy) *> nodes(0).casperEff.createBlock
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
      deploy                     <- ProtoUtil.basicDeploy[Effect](1L)
      createBlockResult          <- nodes(0).casperEff.deploy(deploy) *> nodes(0).casperEff.createBlock
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
      deployData0 <- ProtoUtil.basicDeploy[Effect](1L)
      deployData1 <- ProtoUtil.basicDeploy[Effect](1L)
      deployData2 <- ProtoUtil.basicDeploy[Effect](2L)
      deploys = Vector(
        deployData0,
        deployData1,
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
      _ = multiparentBlock.header.get.parentHashes.size shouldBe 2
      _ = nodes(0).casperEff.contains(multiparentBlock) shouldBeF true
      _ = nodes(1).casperEff.contains(multiparentBlock) shouldBeF true

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
      forwardDeploy = ProtoUtil.basicDeploy(
        System.currentTimeMillis(),
        ByteString.copyFromUtf8(forwardCode),
        1
      )
      bondingDeploy = ProtoUtil.basicDeploy(
        forwardDeploy.getHeader.timestamp + 1,
        ByteString.copyFromUtf8(bondingCode),
        2
      )
      createBlockResult1 <- casperEff.deploy(forwardDeploy) *> casperEff.createBlock
      Created(block1)    = createBlockResult1
      block1Status       <- casperEff.addBlock(block1)
      createBlockResult2 <- casperEff.deploy(bondingDeploy) *> casperEff.createBlock
      Created(block2)    = createBlockResult2
      block2Status       <- casperEff.addBlock(block2)

      _        = logEff.warns shouldBe empty
      oldBonds = block1.getHeader.getState.bonds
      newBonds = block2.getHeader.getState.bonds
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
    val localBonds =
      localValidators.map(Ed25519.tryToPublic(_).get).zip(List(10L, 30L, 5000L)).toMap
    val BlockMsgWithTransform(Some(localGenesis), localTransforms) =
      buildGenesis(Nil, localBonds, 1L, Long.MaxValue, 0L)
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
        System.currentTimeMillis()
      )
      bondingDeploy = ProtoUtil.sourceDeploy(
        bondingCode,
        forwardDeploy.getHeader.timestamp + 1
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
        (ProtoUtil.basicDeploy[Effect](1L) >>= deploy) *> createBlock
      }
      Created(block2) = createBlockResult2
      status2         <- nodes(1).casperEff.addBlock(block2)
      _               <- nodes.head.receive()
      _               <- nodes(1).receive()
      _               <- nodes(2).clearMessages() //nodes(2) misses block built on bonding

      createBlockResult3 <- { //nodes(2) proposes a block
        val n = nodes(2)
        import n.casperEff._
        (ProtoUtil.basicDeploy[Effect](1L) >>= deploy) *> createBlock
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
        (ProtoUtil.basicDeploy[Effect](2L) >>= deploy) *> createBlock
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
      deployDatas <- (0L to 2L).toList
                      .traverse[Effect, Deploy](i => ProtoUtil.basicDeploy[Effect](i))
      deployPrim0 = deployDatas(1)
        .withHeader(
          deployDatas(1).getHeader
            .withTimestamp(deployDatas(0).getHeader.timestamp)
            .withAccountPublicKey(deployDatas(0).getHeader.accountPublicKey)
        ) // deployPrim0 has the same (user, millisecond timestamp) with deployDatas(0)
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
      deployDatas = deploysFromString(
        System.currentTimeMillis(),
        List("for(_ <- @1){ Nil } | @1!(1)", "@2!(2)")
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
        .count(_ startsWith "Requested missing block") should (be >= 1 and be <= 2)
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
    object makeDeployA {
      private var nonce: Long = 1
      private val accountPk   = ProtoUtil.randomAccountAddress()
      def apply(): Deploy = synchronized {
        val nonce_tmp = nonce
        nonce += 1
        ProtoUtil.basicDeploy(
          System.currentTimeMillis(),
          ByteString.copyFromUtf8("A"),
          nonce_tmp,
          accountPk
        )
      }
    }

    object makeDeployB {
      private var nonce: Long = 1
      private val accountPk   = ProtoUtil.randomAccountAddress()
      def apply(): Deploy = synchronized {
        val nonce_tmp = nonce
        nonce += 1
        ProtoUtil.basicDeploy(
          System.currentTimeMillis(),
          ByteString.copyFromUtf8("B"),
          nonce_tmp,
          accountPk
        )
      }
    }

    /** Create a block from a deploy and add it on that node. */
    def deploy(node: HashSetCasperTestNode[Effect], dd: Deploy): Effect[Block] =
      for {
        createBlockResult1    <- node.casperEff.deploy(dd) *> node.casperEff.createBlock
        Created(signedBlock1) = createBlockResult1
        _                     <- node.casperEff.addBlock(signedBlock1)
      } yield signedBlock1

    /** nodes 0 and 1 create blocks in parallel; node 2 misses both, e.g. a1 and a2. */
    def stepSplit(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- deploy(nodes(0), makeDeployA())
        _ <- deploy(nodes(1), makeDeployB())

        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).clearMessages() //nodes(2) misses this block
      } yield ()

    /** node 0 creates a block; node 1 gets it but node 2 doesn't. */
    def stepSingle(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- deploy(nodes(0), makeDeployA())

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
      br <- deploy(nodes(0), makeDeployA()) // block h1

      // node(0) just created this block, so it should have it.
      _ <- nodes(0).casperEff.contains(br) shouldBeF true
      // Let every node get everything.
      _ <- List.fill(22)(propagate(nodes)).toList.sequence
      // By now node(2) should have received all dependencies and added block h1
      _ <- nodes(2).casperEff.contains(br) shouldBeF true
      // And if we create one more block on top of h1 it should be the only parent.
      nr <- deploy(nodes(2), makeDeployA())
      _ = nr.header.get.parentHashes.map(PrettyPrinter.buildString(_)) shouldBe Seq(
        PrettyPrinter.buildString(br.blockHash)
      )

      _ <- nodes.map(_.tearDownNode()).toList.sequence
    } yield ()
  }

  it should "ignore adding equivocation blocks" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis, transforms, validateNonces = false)

      // Creates a pair that constitutes equivocation blocks
      basicDeployData0 <- ProtoUtil.basicDeploy[Effect](1L)
      createBlockResult1 <- nodes(0).casperEff
                             .deploy(basicDeployData0) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      basicDeployData1      <- ProtoUtil.basicDeploy[Effect](1L)
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
      nodes       <- networkEff(validatorKeys.take(3), genesis, transforms, validateNonces = false)
      deployDatas <- (0L to 5L).toList.traverse[Effect, Deploy](ProtoUtil.basicDeploy[Effect])

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
      _ <- nodes(1).casperEff.addBlock(signedBlock1)
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
      // Because node(1) will also see that the signedBlock3 is building on top of signedBlock1Prime,
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
            .contains(signedBlock4) shouldBeF false // Since we have enough evidence that it is equivocation, we no longer add it to dag.

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
              _      <- blockStore.getBlockMessage(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
              result <- blockStore.getBlockMessage(signedBlock4.blockHash) shouldBeF None
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

  it should "not ignore adding equivocation blocks when a child is revealed later" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis, transforms, validateNonces = false)

      makeDeploy = (n: Int, i: Long) => {
        for {
          deploy         <- ProtoUtil.basicDeploy[Effect](i)
          result         <- nodes(n).casperEff.deploy(deploy) *> nodes(n).casperEff.createBlock
          Created(block) = result
        } yield block
      }

      // Creates a pair that constitutes equivocation blocks
      block1      <- makeDeploy(0, 0L)
      block1Prime <- makeDeploy(0, 1L)

      _ <- nodes(0).casperEff.addBlock(block1) shouldBeF Valid
      _ <- nodes(0).casperEff.addBlock(block1Prime) shouldBeF IgnorableEquivocation
      _ <- nodes(1).clearMessages()
      _ <- nodes(1).casperEff.addBlock(block1Prime) shouldBeF Valid
      _ <- nodes(0).receive()

      _ <- nodes(0).casperEff.contains(block1) shouldBeF true
      _ <- nodes(0).casperEff.contains(block1Prime) shouldBeF false

      block2Prime <- makeDeploy(1, 2L)
      _           <- nodes(1).casperEff.addBlock(block2Prime) shouldBeF Valid
      _           <- nodes(0).receive()
      // Process dependencies
      _ <- nodes(1).receive()
      _ <- nodes(0).receive()

      _ <- nodes(0).casperEff.contains(block2Prime) shouldBeF true
      _ <- nodes(0).casperEff.contains(block1Prime) shouldBeF true

      _ <- nodes.toList.traverse(_.tearDown())
    } yield ()
  }

  it should "reject blocks that include a invalid block pointer" in effectTest {
    for {
      nodes           <- networkEff(validatorKeys.take(3), genesis, transforms)
      deploys         <- (1L to 6L).toList.traverse(i => ProtoUtil.basicDeploy[Effect](i))
      deploysWithCost = deploys.map(d => ProcessedDeploy(deploy = Some(d))).toIndexedSeq

      createBlockResult <- nodes(0).casperEff
                            .deploy(deploys(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      signedInvalidBlock = BlockUtil.resignBlock(
        signedBlock.withHeader(signedBlock.getHeader.withValidatorBlockSeqNum(-2)),
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
      //   transport.Block,
      //   signedInvalidBlock.toByteString
      // )
      // _ <- nodes(0).transportLayerEff.send(nodes(1).local, signedInvalidBlockPacketMessage)
      // _ <- nodes(1).receive() // receives signedInvalidBlock; attempts to add both blocks
      // NOTE: Instead of the above let's just add it directly to node(1); node(0) doesn't have this block.
      _ <- nodes(1).casperEff
            .addBlock(signedInvalidBlock)

      _ = nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(1)
      _ <- nodes(1).casperEff.contains(signedInvalidBlock) shouldBeF false
      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "handle a long chain of block requests appropriately" in effectTest {
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis,
                transforms,
                storageSize = 1024L * 1024 * 10
              )

      _ <- (1L to 10L).toList.traverse_[Effect, Unit] { i =>
            for {
              deploy <- ProtoUtil.basicDeploy[Effect](i)
              createBlockResult <- nodes(0).casperEff
                                    .deploy(deploy) *> nodes(0).casperEff.createBlock
              Created(block) = createBlockResult

              _ <- nodes(0).casperEff.addBlock(block)
              _ <- nodes(1).clearMessages() //nodes(1) misses this block
            } yield ()
          }
      deployData10 <- ProtoUtil.basicDeploy[Effect](11L)
      createBlock11Result <- nodes(0).casperEff.deploy(deployData10) *> nodes(
                              0
                            ).casperEff.createBlock
      Created(block11) = createBlock11Result
      _                <- nodes(0).casperEff.addBlock(block11)

      // Cycle of requesting and passing blocks until block #9 from nodes(0) to nodes(1)
      _ <- (0 to 8).toList.traverse_[Effect, Unit] { _ =>
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

  it should "create valid block when receives an invalid block" in effectTest {
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis,
                transforms,
                validateNonces = false
              )
      deployData1 <- ProtoUtil.basicDeploy[Effect](1L)
      createBlock1Result <- nodes(0).casperEff
                             .deploy(deployData1) *> nodes(0).casperEff.createBlock
      Created(block1) = createBlock1Result
      invalidBlock1 = BlockUtil.resignBlock(
        block1.withHeader(block1.getHeader.withTimestamp(Long.MaxValue)),
        nodes(0).validatorId.privateKey
      )
      _ <- nodes(0).casperEff.addBlock(invalidBlock1)
      _ = nodes(0).logEff.warns.count(_ startsWith "Recording invalid block") should be(1)
      // nodes(0) won't send invalid blocks
      _ <- nodes(1).receive()
      _ <- nodes(1).casperEff
            .contains(invalidBlock1) shouldBeF false
      // we manually add this invalid block to node1
      _ <- nodes(1).casperEff.addBlock(invalidBlock1)
      _ <- nodes(1).casperEff
            .contains(invalidBlock1) shouldBeF false
      deployData2 <- ProtoUtil.basicDeploy[Effect](2L)
      createBlock2Result <- nodes(1).casperEff
                             .deploy(deployData2) *> nodes(1).casperEff.createBlock
      Created(block2) = createBlock2Result
      _               <- nodes(1).casperEff.addBlock(block2)
      _               <- nodes(0).receive()
      _               <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              for {
                _      <- blockStore.getBlockMessage(invalidBlock1.blockHash) shouldBeF None
                result <- blockStore.getBlockMessage(block2.blockHash) shouldBeF Some(block2)
              } yield result
            }(nodes(0).metricEff, nodes(0).logEff)
          }
    } yield ()
  }

  it should "increment last finalized block as appropriate in round robin" in effectTest {
    val stake      = 10L
    val equalBonds = validators.map(_ -> stake).toMap
    val BlockMsgWithTransform(Some(genesisWithEqualBonds), transformsWithEqualBonds) =
      buildGenesis(Seq.empty, equalBonds, 1L, Long.MaxValue, 0L)

    def checkLastFinalizedBlock(
        node: HashSetCasperTestNode[Effect],
        expected: Block
    )(implicit pos: org.scalactic.source.Position): Effect[Unit] =
      node.casperEff.lastFinalizedBlock map { block =>
        PrettyPrinter.buildString(block) shouldBe PrettyPrinter.buildString(expected)
        ()
      }

    import ProtoUtil.DeployOps

    for {
      nodes <- networkEff(
                validatorKeys.take(3),
                genesisWithEqualBonds,
                transformsWithEqualBonds,
                faultToleranceThreshold = 0f // With equal bonds this should allow the final block to move as expected.
              )
      deployDatas <- (1L to 8L).toList.traverse(i => ProtoUtil.basicDeploy[Effect](i))

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
      deploy <- ProtoUtil.basicDeploy[Effect](1L).map { d =>
                 d.withBody(
                   d.getBody.withPayment(
                     Deploy.Code().withCode(ByteString.copyFromUtf8("some payment code"))
                   )
                 )
               }
      _                 <- node.casperEff.deploy(deploy)
      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
    } yield {
      cancelUntilFixed("FIXME: Implement cost accounting!")
      //assert(block.body.get.deploys.head.isError)
    }
  }

  it should "succeed if given enough gas for deploy" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy <- ProtoUtil.basicDeploy[Effect](1L)
      _      <- node.casperEff.deploy(deploy)

      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
    } yield assert(!block.body.get.deploys.head.isError)
  }

  it should "put deploys with invalid nonce back into the deploy buffer" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    // Choosing obviously invalid nonce as there's no way to extract current account's nonce from the GlobalState
    val invalidNonce = 1000L

    for {
      invalidDeploy     <- ProtoUtil.basicDeploy[Effect](invalidNonce)
      _                 <- node.casperEff.deploy(invalidDeploy)
      validDeploy       <- ProtoUtil.basicDeploy[Effect](1L)
      _                 <- node.casperEff.deploy(validDeploy)
      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
      casperState       <- Cell[Effect, CasperState].read
      deployBuffer      = casperState.deployBuffer
    } yield {
      assert(block.body.get.deploys.flatMap(_.deploy).contains(validDeploy))
      assert(deployBuffer.contains(invalidDeploy))
    }
  }

  it should "put orphaned deploys back into the pending deploy buffer" in effectTest {
    // Make a network where we don't validate nonces, I just want the merge conflict.
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis,
                transforms,
                validateNonces = false,
                maybeMakeEE =
                  Some(HashSetCasperTestNode.simpleEEApi[Effect](_, _, generateConflict = true))
              )

      deployA         <- ProtoUtil.basicDeploy[Effect](1L)
      _               <- nodes(0).casperEff.deploy(deployA)
      createA         <- nodes(0).casperEff.createBlock
      Created(blockA) = createA
      _               <- nodes(0).casperEff.addBlock(blockA) shouldBeF Valid
      s0              <- nodes(0).casperState.read
      _               = s0.deployBuffer.processedDeploys should contain key (deployA.deployHash)

      deployB         <- ProtoUtil.basicDeploy[Effect](1L)
      _               <- nodes(1).casperEff.deploy(deployB)
      createB         <- nodes(1).casperEff.createBlock
      Created(blockB) = createB
      // nodes(1) should have more weight then nodes(0) so it should take over
      _  <- nodes(0).casperEff.addBlock(blockB) shouldBeF Valid
      s1 <- nodes(0).casperState.read
      _  = s1.deployBuffer.pendingDeploys should contain key (deployA.deployHash)
      _  <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "remove deploys with lower than expected nonces from the buffer" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)

    for {
      deploy1           <- ProtoUtil.basicDeploy[Effect](1L)
      _                 <- node.casperEff.deploy(deploy1)
      createBlockResult <- node.casperEff.createBlock
      Created(block)    = createBlockResult
      _                 <- node.casperEff.addBlock(block)

      // Add a deploy that is not in the future but in the past.
      // Clear out the other deploy so this invalid one isn't rejected straight away.
      _ <- node.casperState.modify { s =>
            s.copy(deployBuffer = s.deployBuffer.remove(Set(deploy1.deployHash)))
          }

      deploy0     <- ProtoUtil.basicDeploy[Effect](0L)
      _           <- node.casperEff.deploy(deploy0)
      stateBefore <- node.casperState.read
      _           = stateBefore.deployBuffer.contains(deploy0) shouldBe true
      _           <- node.casperEff.createBlock shouldBeF NoNewDeploys
      stateAfter  <- node.casperState.read
      _           = stateAfter.deployBuffer.contains(deploy0) shouldBe false
    } yield ()
  }

  it should "reject deploys with nonces already processed but still in the buffer" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)

    for {
      deployA           <- ProtoUtil.basicDeploy[Effect](1L)
      _                 <- node.casperEff.deploy(deployA)
      createBlockResult <- node.casperEff.createBlock
      Created(block)    = createBlockResult
      _                 <- node.casperEff.addBlock(block)

      deployB <- ProtoUtil.basicDeploy[Effect](1L)
      r       <- node.casperEff.deploy(deployB)
      _       = r.isLeft shouldBe true
      _       = r.left.get shouldBe an[IllegalArgumentException]
      state   <- node.casperState.read
      _       = state.deployBuffer.contains(deployB) shouldBe false
    } yield ()
  }

  it should "accept deploys with nonces already seen but only in the pending buffer" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)

    for {
      deployA <- ProtoUtil.basicDeploy[Effect](1L)
      _       <- node.casperEff.deploy(deployA)
      deployB <- ProtoUtil.basicDeploy[Effect](1L)
      _       <- node.casperEff.deploy(deployB)
      state   <- node.casperState.read
      _       = state.deployBuffer.contains(deployB) shouldBe true
    } yield ()
  }

  it should "return NoNewDeploys status if there are no deploys to put into the block after executing contracts" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    // Choosing obviously invalid nonce as there's no way to extract current account's nonce from the GlobalState
    val invalidNonce = 1000L

    for {
      invalidDeploy <- ProtoUtil.basicDeploy[Effect](invalidNonce)
      _             <- node.casperEff.deploy(invalidDeploy)
      _             <- MultiParentCasper[Effect].createBlock shouldBeF io.casperlabs.casper.NoNewDeploys
    } yield ()
  }

  it should "only execute the next deploy per account in one proposal" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    def propose() =
      for {
        create         <- node.casperEff.createBlock
        Created(block) = create
        _              <- node.casperEff.addBlock(block)
      } yield block

    for {
      deploy1 <- ProtoUtil.basicDeploy[Effect](1L)
      deploy2 <- ProtoUtil.basicDeploy[Effect](2L)
      _       <- node.casperEff.deploy(deploy1)
      _       <- node.casperEff.deploy(deploy2)

      block1 <- propose()
      _      = block1.getBody.deploys.map(_.getDeploy) should contain only (deploy1)
      state1 <- node.casperState.read
      _      = state1.deployBuffer.processedDeploys.keySet should contain only (deploy1.deployHash)
      _      = state1.deployBuffer.pendingDeploys.keySet should contain only (deploy2.deployHash)

      block2 <- propose()
      _      = block2.getBody.deploys.map(_.getDeploy) should contain only (deploy2)
      state2 <- node.casperState.read
      _ = state2.deployBuffer.processedDeploys.keySet should contain theSameElementsAs List(
        deploy1.deployHash,
        deploy2.deployHash
      )
      _ = state2.deployBuffer.pendingDeploys shouldBe empty

    } yield ()
  }

  private def buildBlockWithInvalidJustification(
      nodes: IndexedSeq[HashSetCasperTestNode[Effect]],
      deploys: immutable.IndexedSeq[ProcessedDeploy],
      signedInvalidBlock: Block
  ): Effect[Block] = {
    val postState =
      Block.GlobalState().withBonds(ProtoUtil.bonds(genesis))
    val serializedJustifications =
      Seq(
        Justification(signedInvalidBlock.getHeader.validatorPublicKey, signedInvalidBlock.blockHash)
      )
    val body = Block.Body().withDeploys(deploys)
    val header = Block
      .Header()
      .withParentHashes(signedInvalidBlock.header.get.parentHashes)
      .withJustifications(serializedJustifications)
      .withBodyHash(ProtoUtil.protoHash(body))
      .withState(postState)
      .withRank(1)
    val blockHash = ProtoUtil.protoHash(header)
    val blockThatPointsToInvalidBlock =
      Block()
        .withBlockHash(blockHash)
        .withHeader(header)
        .withBody(body)
    nodes(1).casperEff.blockDag.flatMap { dag =>
      ProtoUtil.signBlock[Effect](
        blockThatPointsToInvalidBlock,
        dag,
        validators(1),
        validatorKeys(1),
        Ed25519
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

  def createBonds(validators: Seq[PublicKey]): Map[PublicKey, Long] =
    validators.zipWithIndex.map { case (v, i) => v -> (2L * i.toLong + 1L) }.toMap

  def createGenesis(bonds: Map[PublicKey, Long]): BlockMsgWithTransform =
    buildGenesis(Seq.empty, bonds, 1L, Long.MaxValue, 0L)

  def buildGenesis(
      wallets: Seq[PreWallet],
      bonds: Map[PublicKey, Long],
      minimumBond: Long,
      maximumBond: Long,
      timestamp: Long
  ): BlockMsgWithTransform = {
    implicit val logEff                  = new LogStub[Task]()
    val initial                          = Genesis.withoutContracts(bonds, timestamp, "casperlabs")
    implicit val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
    implicit val filesApi                = FilesAPI.create[Task]
    val validators = bonds.map {
      case (id, stake) => ProofOfStakeValidator(id, stake)
    }.toSeq

    (for {
      blessed <- Genesis.defaultBlessedTerms[Task](
                  accountPublicKeyPath = None,
                  initialTokens = BigInt(0),
                  ProofOfStakeParams(minimumBond, maximumBond, validators),
                  wallets,
                  mintCodePath = None,
                  posCodePath = None
                )
      genenis <- Genesis
                  .withContracts[Task](
                    initial,
                    blessed
                  )
    } yield genenis).unsafeRunSync
  }
}
