package io.casperlabs.casper

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore
import io.casperlabs.casper.MultiParentCasper.ignoreDoppelgangerCheck
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.helper.HashSetCasperTestNode.Effect
import io.casperlabs.casper.helper.{BlockDagStorageTestFixture, BlockUtil, HashSetCasperTestNode}
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.rholang.RuntimeManager
import io.casperlabs.casper.util.{BondingUtil, ProtoUtil}
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.comm.rp.ProtocolHelper.packet
import io.casperlabs.comm.transport
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.{Blake2b256, Keccak256}
import io.casperlabs.crypto.signatures.{Ed25519, Secp256k1}
import io.casperlabs.p2p.EffectsTestInstances.{LogStub, LogicalTime}
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared.Log
import io.casperlabs.shared.PathOps.RichPath
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{Assertion, FlatSpec, Matchers}

import scala.collection.immutable

class HashSetCasperTest extends FlatSpec with Matchers {

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
  private val genesis =
    buildGenesis(wallets, bonds, minimumBond, Long.MaxValue, Faucet.basicWalletFaucet, 0L)

  //put a new casper instance at the start of each
  //test since we cannot reset it
  "HashSetCasper" should "accept deploys" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy <- ProtoUtil.basicDeployData[Effect](0)
      _      <- MultiParentCasper[Effect].deploy(deploy)

      _      = logEff.infos.size should be(2)
      result = logEff.infos(1).contains("Received Deploy") should be(true)
      _      <- node.tearDown()
    } yield result
  }

  it should "not allow multiple threads to process the same block" in {
    val scheduler = Scheduler.fixedPool("three-threads", 3)
    val node      = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)(scheduler)
    val casper    = node.casperEff

    val testProgram = for {
      deploy <- ProtoUtil.basicDeployData[Effect](0)
      _      <- casper.deploy(deploy)
      block  <- casper.createBlock.map { case Created(block) => block }
      result <- EitherT(
                 Task
                   .racePair(
                     casper.addBlock(block, ignoreDoppelgangerCheck[Effect]).value,
                     casper.addBlock(block, ignoreDoppelgangerCheck[Effect]).value
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
    val node            = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
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
      _ = deploys.head.raw should contain(deploy)
      _ = pendingUntilFixed {
        storage.contains("@{0}!(0)") shouldBe true
      }
      _ <- node.tearDown()
    } yield ()
  }

  it should "accept signed blocks" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy               <- ProtoUtil.basicDeployData[Effect](0)
      _                    <- MultiParentCasper[Effect].deploy(deploy)
      createBlockResult    <- MultiParentCasper[Effect].createBlock
      Created(signedBlock) = createBlockResult
      _                    <- MultiParentCasper[Effect].addBlock(signedBlock, ignoreDoppelgangerCheck[Effect])
      _                    = logEff.warns.isEmpty should be(true)
      dag                  <- MultiParentCasper[Effect].blockDag
      result               <- MultiParentCasper[Effect].estimator(dag) shouldBeF IndexedSeq(signedBlock)
      _                    = node.tearDown()
    } yield result
  }

  //Todo bring back this test once we implement the blocking function in RuntimeManager
  it should "be able to create a chain of blocks from different deploys" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
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
      _                     <- MultiParentCasper[Effect].addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      createBlockResult2 <- MultiParentCasper[Effect].deploy(deployDatas(1)) *> MultiParentCasper[
                             Effect
                           ].createBlock
      Created(signedBlock2) = createBlockResult2
      _                     <- MultiParentCasper[Effect].addBlock(signedBlock2, ignoreDoppelgangerCheck[Effect])
      storage               <- blockTuplespaceContents(signedBlock2)
      _                     = logEff.warns shouldBe empty
      _                     = ProtoUtil.parentHashes(signedBlock2) should be(Seq(signedBlock1.blockHash))
      dag                   <- MultiParentCasper[Effect].blockDag
      _                     <- MultiParentCasper[Effect].estimator(dag) shouldBeF IndexedSeq(signedBlock2)
      _ = pendingUntilFixed {
        storage.contains("!(12)") should be(true)
      }
      _ <- node.tearDown()
    } yield ()
  }

  it should "allow multiple deploys in a single block" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._

    val startTime = System.currentTimeMillis()
    val source    = " for(@x <- @0){ @0!(x) } | @0!(0) "
    val deploys = (source #:: source #:: Stream.empty[String]).zipWithIndex
      .map(s => ProtoUtil.sourceDeploy(s._1, startTime + s._2, Integer.MAX_VALUE))
    for {
      _                 <- deploys.traverse_(MultiParentCasper[Effect].deploy(_))
      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
      _                 <- MultiParentCasper[Effect].addBlock(block, ignoreDoppelgangerCheck[Effect])
      result            <- MultiParentCasper[Effect].contains(block) shouldBeF true
      _                 <- node.tearDown()
    } yield result
  }

  it should "reject unsigned blocks" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      basicDeployData <- ProtoUtil.basicDeployData[Effect](0)
      createBlockResult <- MultiParentCasper[Effect].deploy(basicDeployData) *> MultiParentCasper[
                            Effect
                          ].createBlock
      Created(block) = createBlockResult
      invalidBlock   = block.withSig(ByteString.EMPTY)
      _              <- MultiParentCasper[Effect].addBlock(invalidBlock, ignoreDoppelgangerCheck[Effect])
      _              = logEff.warns.count(_.contains("because block signature")) should be(1)
      _              <- node.tearDownNode()
      result <- validateBlockStore(node) { blockStore =>
                 blockStore.get(block.blockHash) shouldBeF None
               }
    } yield result
  }

  it should "not request invalid blocks from peers" in effectTest {
    val dummyContract =
      ByteString.readFrom(getClass.getResourceAsStream("/helloname.wasm"))

    val List(data0, data1) =
      (0 to 1)
        .flatMap(i => ProtoUtil.sourceDeploy(dummyContract, i.toLong, Long.MaxValue).raw)
        .toList

    for {
      nodes              <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)
      List(node0, node1) = nodes.toList

      unsignedBlock <- (node0.casperEff.deploy(data0) *> node0.casperEff.createBlock)
                        .map {
                          case Created(block) =>
                            block.copy(sigAlgorithm = "invalid", sig = ByteString.EMPTY)
                        }

      _ <- node0.casperEff.addBlock(unsignedBlock, ignoreDoppelgangerCheck[Effect])
      _ <- node1.transportLayerEff.clear(node1.local) //node1 misses this block

      signedBlock <- (node0.casperEff.deploy(data1) *> node0.casperEff.createBlock)
                      .map { case Created(block) => block }

      _ <- node0.casperEff.addBlock(signedBlock, ignoreDoppelgangerCheck[Effect])
      _ <- node1.receive() //receives block1; should not ask for block0

      _ <- node0.casperEff.contains(unsignedBlock) shouldBeF false
      _ <- node1.casperEff.contains(unsignedBlock) shouldBeF false

    } yield ()
  }

  it should "reject blocks not from bonded validators" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, otherSk)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      basicDeployData <- ProtoUtil.basicDeployData[Effect](0)
      createBlockResult <- MultiParentCasper[Effect].deploy(basicDeployData) *> MultiParentCasper[
                            Effect
                          ].createBlock
      Created(signedBlock) = createBlockResult
      _                    <- MultiParentCasper[Effect].addBlock(signedBlock, ignoreDoppelgangerCheck[Effect])
      _                    = exactly(1, logEff.warns) should include("Ignoring block")
      _                    <- node.tearDownNode()
      result <- validateBlockStore(node) { blockStore =>
                 blockStore.get(signedBlock.blockHash) shouldBeF None
               }
    } yield result
  }

  it should "propose blocks it adds to peers" in effectTest {
    for {
      nodes                <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)
      deployData           <- ProtoUtil.basicDeployData[Effect](0)
      createBlockResult    <- nodes(0).casperEff.deploy(deployData) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      _                    <- nodes(0).casperEff.addBlock(signedBlock, ignoreDoppelgangerCheck[Effect])
      _                    <- nodes(1).receive()
      result               <- nodes(1).casperEff.contains(signedBlock) shouldBeF true
      _                    <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              blockStore.get(signedBlock.blockHash) shouldBeF Some(signedBlock)
            }(nodes(0).metricEff, nodes(0).logEff)
          }
    } yield result
  }

  it should "add a valid block from peer" in effectTest {
    for {
      nodes                      <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)
      deployData                 <- ProtoUtil.basicDeployData[Effect](1)
      createBlockResult          <- nodes(0).casperEff.deploy(deployData) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult
      _                          <- nodes(0).casperEff.addBlock(signedBlock1Prime, ignoreDoppelgangerCheck[Effect])
      _                          <- nodes(1).receive()
      _                          = nodes(1).logEff.infos.count(_ startsWith "Added") should be(1)
      result                     = nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(0)
      _                          <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              blockStore.get(signedBlock1Prime.blockHash) shouldBeF Some(signedBlock1Prime)
            }(nodes(0).metricEff, nodes(0).logEff)
          }
    } yield result
  }

  it should "handle multi-parent blocks correctly" in effectTest {
    for {
      nodes       <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)
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
      _                  <- nodes(0).casperEff.addBlock(block0, ignoreDoppelgangerCheck[Effect])
      _                  <- nodes(1).casperEff.addBlock(block1, ignoreDoppelgangerCheck[Effect])
      _                  <- nodes(0).receive()
      _                  <- nodes(1).receive()
      _                  <- nodes(0).receive()
      _                  <- nodes(1).receive()

      //multiparent block joining block0 and block1 since they do not conflict
      multiparentCreateBlockResult <- nodes(0).casperEff
                                       .deploy(deploys(2)) *> nodes(0).casperEff.createBlock
      Created(multiparentBlock) = multiparentCreateBlockResult
      _                         <- nodes(0).casperEff.addBlock(multiparentBlock, ignoreDoppelgangerCheck[Effect])
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
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
    import node.{casperEff, logEff}

    implicit val runtimeManager = node.runtimeManager
    val (sk, pk)                = Ed25519.newKeyPair
    val pkStr                   = Base16.encode(pk)
    val amount                  = 314L
    val forwardCode             = BondingUtil.bondingForwarderDeploy(pkStr, pkStr)
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
      block1Status       <- casperEff.addBlock(block1, ignoreDoppelgangerCheck[Effect])
      createBlockResult2 <- casperEff.deploy(bondingDeploy) *> casperEff.createBlock
      Created(block2)    = createBlockResult2
      block2Status       <- casperEff.addBlock(block2, ignoreDoppelgangerCheck[Effect])

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
    val localGenesis =
      buildGenesis(Nil, localBonds, 1L, Long.MaxValue, Faucet.basicWalletFaucet, 0L)
    for {
      nodes <- HashSetCasperTestNode.networkEff(localValidators, localGenesis)

      rm          = nodes.head.runtimeManager
      (sk, pk)    = Ed25519.newKeyPair
      pkStr       = Base16.encode(pk)
      forwardCode = BondingUtil.bondingForwarderDeploy(pkStr, pkStr)
      bondingCode <- BondingUtil.faucetBondDeploy[Effect](50, "ed25519", pkStr, sk)(
                      Sync[Effect],
                      rm
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
                            .addBlock(bondedBlock, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).receive()
      _ <- nodes.head.receive()
      _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses bonding

      createBlockResult2 <- {
        val n = nodes(1)
        import n.casperEff._
        (ProtoUtil.basicDeployData[Effect](0) >>= deploy) *> createBlock
      }
      Created(block2) = createBlockResult2
      status2         <- nodes(1).casperEff.addBlock(block2, ignoreDoppelgangerCheck[Effect])
      _               <- nodes.head.receive()
      _               <- nodes(1).receive()
      _               <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses block built on bonding

      createBlockResult3 <- { //nodes(2) proposes a block
        val n = nodes(2)
        import n.casperEff._
        (ProtoUtil.basicDeployData[Effect](1) >>= deploy) *> createBlock
      }
      Created(block3) = createBlockResult3
      status3         <- nodes(2).casperEff.addBlock(block3, ignoreDoppelgangerCheck[Effect])
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
      status4         <- nodes.head.casperEff.addBlock(block4, ignoreDoppelgangerCheck[Effect])
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
      nodes <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)
      deployDatas <- (0 to 2).toList
                      .traverse[Effect, DeployData](i => ProtoUtil.basicDeployData[Effect](i))
      deployPrim0 = deployDatas(1)
        .withTimestamp(deployDatas(0).timestamp)
        .withUser(deployDatas(0).user) // deployPrim0 has the same (user, millisecond timestamp) with deployDatas(0)
      createdBlockResult1 <- nodes(0).casperEff
                              .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createdBlockResult1
      _                     <- nodes(0).casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      _                     <- nodes(1).receive() // receive block1

      createBlockResult2 <- nodes(0).casperEff
                             .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
      Created(signedBlock2) = createBlockResult2
      _                     <- nodes(0).casperEff.addBlock(signedBlock2, ignoreDoppelgangerCheck[Effect])
      _                     <- nodes(1).receive() // receive block2

      createBlockResult3 <- nodes(0).casperEff
                             .deploy(deployDatas(2)) *> nodes(0).casperEff.createBlock
      Created(signedBlock3) = createBlockResult3
      _                     <- nodes(0).casperEff.addBlock(signedBlock3, ignoreDoppelgangerCheck[Effect])
      _                     <- nodes(1).receive() // receive block3

      _ <- nodes(1).casperEff.contains(signedBlock3) shouldBeF true

      createBlockResult4 <- nodes(1).casperEff
                             .deploy(deployPrim0) *> nodes(1).casperEff.createBlock
      Created(signedBlock4) = createBlockResult4
      _ <- nodes(1).casperEff
            .addBlock(signedBlock4, ignoreDoppelgangerCheck[Effect]) // should succeed
      _ <- nodes(0).receive() // still receive signedBlock4

      result <- nodes(1).casperEff
                 .contains(signedBlock4) shouldBeF true // Invalid blocks are still added
      // TODO: Fix with https://rchain.atlassian.net/browse/RHOL-1048
      // nodes(0).casperEff.contains(signedBlock4) should be(false)
      //
      // nodes(0).logEff.warns
      //   .count(_ contains "found deploy by the same (user, millisecond timestamp) produced") should be(
      //   1
      // )
      _ <- nodes.map(_.tearDownNode()).toList.sequence

      _ = nodes.toList.traverse_[Effect, Assertion] { node =>
        validateBlockStore(node) { blockStore =>
          for {
            _      <- blockStore.get(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
            _      <- blockStore.get(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
            result <- blockStore.get(signedBlock3.blockHash) shouldBeF Some(signedBlock3)
          } yield result
        }(nodes(0).metricEff, nodes(0).logEff)
      }
    } yield result
  }

  it should "ask peers for blocks it is missing" in effectTest {
    for {
      nodes <- HashSetCasperTestNode.networkEff(validatorKeys.take(3), genesis)
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

      _ <- nodes(0).casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).receive()
      _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block

      createBlockResult2 <- nodes(0).casperEff
                             .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
      Created(signedBlock2) = createBlockResult2

      _ <- nodes(0).casperEff.addBlock(signedBlock2, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).receive() //receives block2
      _ <- nodes(2).receive() //receives block2; asks for block1
      _ <- nodes(1).receive() //receives request for block1; sends block1
      _ <- nodes(2).receive() //receives block1; adds both block1 and block2

      _ <- nodes(2).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(2).casperEff.contains(signedBlock2) shouldBeF true

      _ = nodes(2).logEff.infos
        .count(_ startsWith "Requested missing block") should be(1)
      result = nodes(1).logEff.infos.count(
        s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
      ) should be(1)

      _ <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            validateBlockStore(node) { blockStore =>
              for {
                _      <- blockStore.get(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
                result <- blockStore.get(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
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

    def deploy(node: HashSetCasperTestNode[Effect], dd: DeployData): Effect[BlockMessage] =
      for {
        createBlockResult1    <- node.casperEff.deploy(dd) *> node.casperEff.createBlock
        Created(signedBlock1) = createBlockResult1

        _ <- node.casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      } yield signedBlock1

    def stepSplit(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- deploy(nodes(0), deployDatasFs(0).apply())
        _ <- deploy(nodes(1), deployDatasFs(1).apply())

        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block
      } yield ()

    def stepSingle(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- deploy(nodes(0), deployDatasFs(0).apply())

        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block
      } yield ()

    def propagate(nodes: Seq[HashSetCasperTestNode[Effect]]) =
      for {
        _ <- nodes(0).receive()
        _ <- nodes(1).receive()
        _ <- nodes(2).receive()
      } yield ()

    for {
      nodes <- HashSetCasperTestNode.networkEff(validatorKeys.take(3), genesis)

      _ <- stepSplit(nodes) // blocks a1 a2
      _ <- stepSplit(nodes) // blocks b1 b2
      _ <- stepSplit(nodes) // blocks c1 c2

      _ <- stepSingle(nodes) // block d1
      _ <- stepSingle(nodes) // block e1

      _ <- stepSplit(nodes) // blocks f1 f2
      _ <- stepSplit(nodes) // blocks g1 g2

      // this block will be propagated to all nodes and force nodes(2) to ask for missing blocks.
      br <- deploy(nodes(0), deployDatasFs(0).apply()) // block h1
      _  <- nodes(0).casperEff.contains(br) shouldBeF true

      _ <- List.fill(22)(propagate(nodes)).toList.sequence // force the network to communicate

      _ <- nodes(2).casperEff.contains(br) shouldBeF true

      nr <- deploy(nodes(2), deployDatasFs(0).apply())
      _  = nr.header.get.parentsHashList shouldBe Seq(br.blockHash)
      _  <- nodes.map(_.tearDownNode()).toList.sequence
    } yield ()
  }

  it should "ignore adding equivocation blocks" in effectTest {
    for {
      nodes <- HashSetCasperTestNode.networkEff(validatorKeys.take(2), genesis)

      // Creates a pair that constitutes equivocation blocks
      basicDeployData0 <- ProtoUtil.basicDeployData[Effect](0)
      createBlockResult1 <- nodes(0).casperEff
                             .deploy(basicDeployData0) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      basicDeployData1      <- ProtoUtil.basicDeployData[Effect](1)
      createBlockResult1Prime <- nodes(0).casperEff
                                  .deploy(basicDeployData1) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult1Prime

      _ <- nodes(0).casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).receive()
      _ <- nodes(0).casperEff.addBlock(signedBlock1Prime, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).receive()

      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      result <- nodes(1).casperEff
                 .contains(signedBlock1Prime) shouldBeF false // we still add the equivocation pair

      _ <- nodes(0).tearDownNode()
      _ <- nodes(1).tearDownNode()
      _ <- validateBlockStore(nodes(1)) { blockStore =>
            for {
              _      <- blockStore.get(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
              result <- blockStore.get(signedBlock1Prime.blockHash) shouldBeF None
            } yield result
          }(nodes(0).metricEff, nodes(0).logEff)
    } yield result
  }

  // See [[/docs/casper/images/minimal_equivocation_neglect.png]] but cross out genesis block
  it should "not ignore equivocation blocks that are required for parents of proper nodes" in effectTest {
    for {
      nodes       <- HashSetCasperTestNode.networkEff(validatorKeys.take(3), genesis)
      deployDatas <- (0 to 5).toList.traverse[Effect, DeployData](ProtoUtil.basicDeployData[Effect])

      // Creates a pair that constitutes equivocation blocks
      createBlockResult1 <- nodes(0).casperEff
                             .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      createBlockResult1Prime <- nodes(0).casperEff
                                  .deploy(deployDatas(1)) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult1Prime

      _ <- nodes(1).casperEff.addBlock(signedBlock1, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(0).transportLayerEff.clear(nodes(0).local) //nodes(0) misses this block
      _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) misses this block

      _ <- nodes(0).casperEff.addBlock(signedBlock1Prime, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(2).receive()
      _ <- nodes(1).transportLayerEff.clear(nodes(1).local) //nodes(1) misses this block

      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(2).casperEff.contains(signedBlock1) shouldBeF false

      _ <- nodes(1).casperEff.contains(signedBlock1Prime) shouldBeF false
      _ <- nodes(2).casperEff.contains(signedBlock1Prime) shouldBeF true

      createBlockResult2 <- nodes(1).casperEff
                             .deploy(deployDatas(2)) *> nodes(1).casperEff.createBlock
      Created(signedBlock2) = createBlockResult2
      createBlockResult3 <- nodes(2).casperEff
                             .deploy(deployDatas(3)) *> nodes(2).casperEff.createBlock
      Created(signedBlock3) = createBlockResult3

      _ <- nodes(2).casperEff.addBlock(signedBlock3, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(1).casperEff.addBlock(signedBlock2, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(2).transportLayerEff.clear(nodes(2).local) //nodes(2) ignores block2
      _ <- nodes(1).receive() // receives block3; asks for block1'
      _ <- nodes(2).receive() // receives request for block1'; sends block1'
      _ <- nodes(1).receive() // receives block1'; adds both block3 and block1'

      _ <- nodes(1).casperEff.contains(signedBlock3) shouldBeF true
      _ <- nodes(1).casperEff.contains(signedBlock1Prime) shouldBeF true

      createBlockResult4 <- nodes(1).casperEff
                             .deploy(deployDatas(4)) *> nodes(1).casperEff.createBlock
      Created(signedBlock4) = createBlockResult4
      _                     <- nodes(1).casperEff.addBlock(signedBlock4, ignoreDoppelgangerCheck[Effect])

      // Node 1 should contain both blocks constituting the equivocation
      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      _ <- nodes(1).casperEff.contains(signedBlock1Prime) shouldBeF true

      _ <- nodes(1).casperEff
            .contains(signedBlock4) shouldBeF true // However, in invalidBlockTracker

      _ = nodes(1).logEff.infos.count(_ startsWith "Added admissible equivocation") should be(1)
      _ = nodes(2).logEff.warns.size should be(0)
      _ = nodes(1).logEff.warns.size should be(1)
      _ = nodes(0).logEff.warns.size should be(0)

      _ <- nodes(1).casperEff
            .normalizedInitialFault(ProtoUtil.weightMap(genesis)) shouldBeF 1f / (1f + 3f + 5f + 7f)
      _ <- nodes.map(_.tearDownNode()).toList.sequence

      _ <- validateBlockStore(nodes(0)) { blockStore =>
            for {
              _ <- blockStore.get(signedBlock1.blockHash) shouldBeF None
              result <- blockStore.get(signedBlock1Prime.blockHash) shouldBeF Some(
                         signedBlock1Prime
                       )
            } yield result
          }(nodes(0).metricEff, nodes(0).logEff)
      _ <- validateBlockStore(nodes(1)) { blockStore =>
            for {
              _      <- blockStore.get(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
              result <- blockStore.get(signedBlock4.blockHash) shouldBeF Some(signedBlock4)
            } yield result
          }(nodes(1).metricEff, nodes(1).logEff)
      result <- validateBlockStore(nodes(2)) { blockStore =>
                 for {
                   _ <- blockStore.get(signedBlock3.blockHash) shouldBeF Some(signedBlock3)
                   result <- blockStore.get(signedBlock1Prime.blockHash) shouldBeF Some(
                              signedBlock1Prime
                            )
                 } yield result
               }(nodes(2).metricEff, nodes(2).logEff)
    } yield result
  }

  it should "prepare to slash an block that includes a invalid block pointer" in effectTest {
    for {
      nodes           <- HashSetCasperTestNode.networkEff(validatorKeys.take(3), genesis)
      deploys         <- (0 to 5).toList.traverse(i => ProtoUtil.basicDeploy[Effect](i))
      deploysWithCost = deploys.map(d => ProcessedDeploy(deploy = Some(d))).toIndexedSeq

      createBlockResult <- nodes(0).casperEff
                            .deploy(deploys(0).raw.get) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      signedInvalidBlock = BlockUtil.resignBlock(
        signedBlock.withSeqNum(-2),
        nodes(0).validatorId.privateKey
      ) // Invalid seq num

      blockWithInvalidJustification <- buildBlockWithInvalidJustification(
                                        nodes,
                                        deploysWithCost,
                                        signedInvalidBlock
                                      )

      _ <- nodes(1).casperEff
            .addBlock(blockWithInvalidJustification, ignoreDoppelgangerCheck[Effect])
      _ <- nodes(0).transportLayerEff
            .clear(nodes(0).local) // nodes(0) rejects normal adding process for blockThatPointsToInvalidBlock

      signedInvalidBlockPacketMessage = packet(
        nodes(0).local,
        transport.BlockMessage,
        signedInvalidBlock.toByteString
      )
      _ <- nodes(0).transportLayerEff.send(nodes(1).local, signedInvalidBlockPacketMessage)
      _ <- nodes(1).receive() // receives signedInvalidBlock; attempts to add both blocks

      result = nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(1)
      _      <- nodes.map(_.tearDown()).toList.sequence
    } yield result
  }

  it should "handle a long chain of block requests appropriately" in effectTest {
    for {
      nodes <- HashSetCasperTestNode.networkEff(
                validatorKeys.take(2),
                genesis,
                storageSize = 1024L * 1024 * 10
              )

      _ <- (0 to 9).toList.traverse_[Effect, Unit] { i =>
            for {
              deploy <- ProtoUtil.basicDeployData[Effect](i)
              createBlockResult <- nodes(0).casperEff
                                    .deploy(deploy) *> nodes(0).casperEff.createBlock
              Created(block) = createBlockResult

              _ <- nodes(0).casperEff.addBlock(block, ignoreDoppelgangerCheck[Effect])
              _ <- nodes(1).transportLayerEff.clear(nodes(1).local) //nodes(1) misses this block
            } yield ()
          }
      deployData10 <- ProtoUtil.basicDeployData[Effect](10)
      createBlock11Result <- nodes(0).casperEff.deploy(deployData10) *> nodes(
                              0
                            ).casperEff.createBlock
      Created(block11) = createBlock11Result
      _                <- nodes(0).casperEff.addBlock(block11, ignoreDoppelgangerCheck[Effect])

      // Cycle of requesting and passing blocks until block #3 from nodes(0) to nodes(1)
      _ <- (0 to 8).toList.traverse_[Effect, Unit] { i =>
            nodes(1).receive() *> nodes(0).receive()
          }

      // We simulate a network failure here by not allowing block #2 to get passed to nodes(1)

      // And then we assume fetchDependencies eventually gets called
      _ <- nodes(1).casperEff.fetchDependencies
      _ <- nodes(0).receive()

      _ = nodes(1).logEff.infos.count(_ startsWith "Requested missing block") should be(10)
      result = nodes(0).logEff.infos.count(
        s => (s startsWith "Received request for block") && (s endsWith "Response sent.")
      ) should be(10)

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield result
  }

  it should "increment last finalized block as appropriate in round robin" in effectTest {
    val stake      = 10L
    val equalBonds = validators.map(_ -> stake).toMap
    val genesisWithEqualBonds =
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
      nodes <- HashSetCasperTestNode.networkEff(
                validatorKeys.take(3),
                genesisWithEqualBonds,
                faultToleranceThreshold = 0f // With equal bonds this should allow the final block to move as expected.
              )
      deployDatas <- (0 to 7).toList.traverse(i => ProtoUtil.basicDeployData[Effect](i))

      createBlock1Result <- nodes(0).casperEff
                             .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      Created(block1) = createBlock1Result
      _               <- nodes(0).casperEff.addBlock(block1, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(1).receive()
      _               <- nodes(2).receive()

      createBlock2Result <- nodes(1).casperEff
                             .deploy(deployDatas(1)) *> nodes(1).casperEff.createBlock
      Created(block2) = createBlock2Result
      _               <- nodes(1).casperEff.addBlock(block2, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(2).receive()

      createBlock3Result <- nodes(2).casperEff
                             .deploy(deployDatas(2)) *> nodes(2).casperEff.createBlock
      Created(block3) = createBlock3Result
      _               <- nodes(2).casperEff.addBlock(block3, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(1).receive()

      createBlock4Result <- nodes(0).casperEff
                             .deploy(deployDatas(3)) *> nodes(0).casperEff.createBlock
      Created(block4) = createBlock4Result
      _               <- nodes(0).casperEff.addBlock(block4, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(1).receive()
      _               <- nodes(2).receive()

      createBlock5Result <- nodes(1).casperEff
                             .deploy(deployDatas(4)) *> nodes(1).casperEff.createBlock
      Created(block5) = createBlock5Result
      _               <- nodes(1).casperEff.addBlock(block5, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(2).receive()

      _     <- checkLastFinalizedBlock(nodes(0), block1)
      state <- nodes(0).casperState.read
      _     = state.deployHistory.size should be(1)

      createBlock6Result <- nodes(2).casperEff
                             .deploy(deployDatas(5)) *> nodes(2).casperEff.createBlock
      Created(block6) = createBlock6Result
      _               <- nodes(2).casperEff.addBlock(block6, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(1).receive()

      _     <- checkLastFinalizedBlock(nodes(0), block2)
      state <- nodes(0).casperState.read
      _     = state.deployHistory.size should be(1)

      createBlock7Result <- nodes(0).casperEff
                             .deploy(deployDatas(6)) *> nodes(0).casperEff.createBlock
      Created(block7) = createBlock7Result
      _               <- nodes(0).casperEff.addBlock(block7, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(1).receive()
      _               <- nodes(2).receive()

      _ <- checkLastFinalizedBlock(nodes(0), block3)
      _ = state.deployHistory.size should be(1)

      createBlock8Result <- nodes(1).casperEff
                             .deploy(deployDatas(7)) *> nodes(1).casperEff.createBlock
      Created(block8) = createBlock8Result
      _               <- nodes(1).casperEff.addBlock(block8, ignoreDoppelgangerCheck[Effect])
      _               <- nodes(0).receive()
      _               <- nodes(2).receive()

      _     <- checkLastFinalizedBlock(nodes(0), block4)
      state <- nodes(0).casperState.read
      _     = state.deployHistory.size should be(1)

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "fail when deploying with insufficient gas" in effectTest {
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
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
    val node = HashSetCasperTestNode.standaloneEff(genesis, validatorKeys.head)
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
    val postState     = RChainState().withBonds(ProtoUtil.bonds(genesis)).withBlockNumber(1)
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
    val tsHash = ProtoUtil.postStateHash(block)
    MultiParentCasper[Effect].storageContents(tsHash)
  }

  def createBonds(validators: Seq[Array[Byte]]): Map[Array[Byte], Long] =
    validators.zipWithIndex.map { case (v, i) => v -> (2L * i.toLong + 1L) }.toMap

  def createGenesis(bonds: Map[Array[Byte], Long]): BlockMessage =
    buildGenesis(Seq.empty, bonds, 1L, Long.MaxValue, Faucet.noopFaucet, 0L)

  def buildGenesis(
      wallets: Seq[PreWallet],
      bonds: Map[Array[Byte], Long],
      minimumBond: Long,
      maximumBond: Long,
      faucetCode: String => String,
      deployTimestamp: Long
  ): BlockMessage = {
    implicit val logEff         = new LogStub[Task]()
    val initial                 = Genesis.withoutContracts(bonds, 1L, deployTimestamp, "casperlabs")
    val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task]()
    val runtimeManager          = RuntimeManager.fromExecutionEngineService(casperSmartContractsApi)
    val emptyStateHash          = casperSmartContractsApi.emptyStateHash
    val validators              = bonds.map(bond => ProofOfStakeValidator(bond._1, bond._2)).toSeq
    val genesis = Genesis
      .withContracts(
        initial,
        ProofOfStakeParams(minimumBond, maximumBond, validators),
        wallets,
        faucetCode,
        emptyStateHash,
        runtimeManager,
        deployTimestamp
      )
      .unsafeRunSync
    genesis
  }
}
