package io.casperlabs.casper

import cats.data.EitherT
import cats.effect.Sync
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.Block.{Justification, ProcessedDeploy}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.genesis.Genesis
import io.casperlabs.casper.genesis.contracts._
import io.casperlabs.casper.helper.HashSetCasperTestNode.Effect
import io.casperlabs.casper.helper._
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.{BondingUtil, ProtoUtil}
import io.casperlabs.catscontrib.TaskContrib.TaskOps
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.p2p.EffectsTestInstances.{LogStub, LogicalTime}
import io.casperlabs.shared.FilesAPI
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
  private val wallets = validators.map(key => PreWallet(key, BigInt(10001)))
  private val bonds   = createBonds(validators)
  private val BlockMsgWithTransform(Some(genesis), transforms) =
    buildGenesis(wallets, bonds, 0L)

  //put a new casper instance at the start of each
  //test since we cannot reset it
  behavior of "HashSetCasper"

  it should "accept deploys" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy <- ProtoUtil.basicDeploy[Effect]()
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
      deploy <- ProtoUtil.basicDeploy[Effect]()
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
      deploy <- ProtoUtil.basicDeploy[Effect]()
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
      deploy               <- ProtoUtil.basicDeploy[Effect]()
      _                    <- MultiParentCasper[Effect].deploy(deploy)
      createBlockResult    <- MultiParentCasper[Effect].createBlock
      Created(signedBlock) = createBlockResult
      _                    <- MultiParentCasper[Effect].addBlock(signedBlock)
      _                    = logEff.warns.isEmpty should be(true)
      dag                  <- MultiParentCasper[Effect].dag
      estimate             <- MultiParentCasper[Effect].estimator(dag)
      _                    = estimate shouldBe IndexedSeq(signedBlock.blockHash)
      _                    = node.tearDown()
    } yield ()
  }

  def deploysFromString(start: Long, strs: List[String]): List[Deploy] =
    strs.zipWithIndex.map(
      s => ProtoUtil.basicDeploy(start + s._2, ByteString.copyFromUtf8(s._1))
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
      dag                   <- MultiParentCasper[Effect].dag
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
      basicDeployData <- ProtoUtil.basicDeploy[Effect]()
      createBlockResult <- MultiParentCasper[Effect].deploy(basicDeployData) *> MultiParentCasper[
                            Effect
                          ].createBlock
      Created(block) = createBlockResult
      invalidBlock   = block.withSignature(block.getSignature.withSig((ByteString.EMPTY)))
      _              <- MultiParentCasper[Effect].addBlock(invalidBlock) shouldBeF InvalidUnslashableBlock
      _              = logEff.warns.count(_.contains("because block signature")) should be(1)
      _              <- node.tearDownNode()
      result <- node.validateBlockStorage { blockStorage =>
                 blockStorage.getBlockMessage(block.blockHash) shouldBeF None
               }
    } yield result
  }

  it should "not request invalid blocks from peers" in effectTest {
    val dummyContract =
      ByteString.readFrom(getClass.getResourceAsStream("/helloname.wasm"))

    val data0 = ProtoUtil.basicDeploy(1, dummyContract)
    val data1 = ProtoUtil.basicDeploy(2, dummyContract)

    for {
      nodes              <- networkEff(validatorKeys.take(2), genesis, transforms)
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

      // NOTE: It can include both data0 and data1 because they don't conflict.
      _ = signedBlock.getBody.deploys.map(_.getDeploy) should contain only (data0, data1)

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
      basicDeployData <- ProtoUtil.basicDeploy[Effect]()
      createBlockResult <- MultiParentCasper[Effect].deploy(basicDeployData) *> MultiParentCasper[
                            Effect
                          ].createBlock
      Created(signedBlock) = createBlockResult
      _                    <- MultiParentCasper[Effect].addBlock(signedBlock)
      _                    = exactly(1, logEff.warns) should include("Ignoring block")
      _                    <- node.tearDownNode()
      result <- node.validateBlockStorage { blockStorage =>
                 blockStorage.getBlockMessage(signedBlock.blockHash) shouldBeF None
               }
    } yield result
  }

  it should "propose blocks it adds to peers" in effectTest {
    for {
      nodes                <- networkEff(validatorKeys.take(2), genesis, transforms)
      deploy               <- ProtoUtil.basicDeploy[Effect]()
      createBlockResult    <- nodes(0).casperEff.deploy(deploy) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      _                    <- nodes(0).casperEff.addBlock(signedBlock)
      _                    <- nodes(1).receive()
      result               <- nodes(1).casperEff.contains(signedBlock) shouldBeF true
      _                    <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            node.validateBlockStorage(
              _.getBlockMessage(signedBlock.blockHash)
                .map(_.map(_.toProtoString)) shouldBeF Some(
                signedBlock.toProtoString
              )
            )
          }
    } yield result
  }

  it should "add a valid block from peer" in effectTest {
    for {
      nodes                      <- networkEff(validatorKeys.take(2), genesis, transforms)
      deploy                     <- ProtoUtil.basicDeploy[Effect]()
      createBlockResult          <- nodes(0).casperEff.deploy(deploy) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult
      _                          <- nodes(0).casperEff.addBlock(signedBlock1Prime)
      _                          <- nodes(1).receive()
      _                          = nodes(1).logEff.infos.count(_ startsWith "Added") should be(1)
      result                     = nodes(1).logEff.warns.count(_ startsWith "Recording invalid block") should be(0)
      _                          <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            node.validateBlockStorage(
              _.getBlockMessage(signedBlock1Prime.blockHash) shouldBeF Some(
                signedBlock1Prime
              )
            )
          }
    } yield result
  }

  it should "handle multi-parent blocks correctly" in effectTest {
    for {
      nodes       <- networkEff(validatorKeys.take(2), genesis, transforms)
      deployData0 <- ProtoUtil.basicDeploy[Effect]()
      deployData1 <- ProtoUtil.basicDeploy[Effect]()
      deployData2 <- ProtoUtil.basicDeploy[Effect]()
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

  it should "not fail if the forkchoice changes after a bonding event" in {
    val localValidators = validatorKeys.take(3)
    val localBonds =
      localValidators.map(Ed25519.tryToPublic(_).get).zip(List(10L, 30L, 5000L)).toMap
    val BlockMsgWithTransform(Some(localGenesis), localTransforms) =
      buildGenesis(Nil, localBonds, 0L)
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
        (ProtoUtil.basicDeploy[Effect]() >>= deploy) *> createBlock
      }
      Created(block2) = createBlockResult2
      status2         <- nodes(1).casperEff.addBlock(block2)
      _               <- nodes.head.receive()
      _               <- nodes(1).receive()
      _               <- nodes(2).clearMessages() //nodes(2) misses block built on bonding

      createBlockResult3 <- { //nodes(2) proposes a block
        val n = nodes(2)
        import n.casperEff._
        (ProtoUtil.basicDeploy[Effect]() >>= deploy) *> createBlock
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
        (ProtoUtil.basicDeploy[Effect]() >>= deploy) *> createBlock
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
                      .traverse[Effect, Deploy](_ => ProtoUtil.basicDeploy[Effect]())
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
        node.validateBlockStorage { blockStorage =>
          for {
            _ <- blockStorage.getBlockMessage(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
            _ <- blockStorage.getBlockMessage(signedBlock2.blockHash) shouldBeF Some(signedBlock2)
            result <- blockStorage.getBlockMessage(signedBlock3.blockHash) shouldBeF Some(
                       signedBlock3
                     )
          } yield result
        }
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
            node.validateBlockStorage { blockStorage =>
              for {
                _ <- blockStorage.getBlockMessage(signedBlock1.blockHash) shouldBeF Some(
                      signedBlock1
                    )
                result <- blockStorage.getBlockMessage(signedBlock2.blockHash) shouldBeF Some(
                           signedBlock2
                         )
              } yield result
            }
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
      private val accountPk = ProtoUtil.randomAccountAddress()
      def apply(): Deploy = synchronized {
        ProtoUtil.basicDeploy(
          System.currentTimeMillis(),
          ByteString.copyFromUtf8("A"),
          accountPk
        )
      }
    }

    object makeDeployB {
      private val accountPk = ProtoUtil.randomAccountAddress()
      def apply(): Deploy = synchronized {
        ProtoUtil.basicDeploy(
          System.currentTimeMillis(),
          ByteString.copyFromUtf8("B"),
          accountPk
        )
      }
    }

    /** Create a block from a deploy and add it on that node. */
    def deploy(node: HashSetCasperTestNode[Effect], dd: Deploy): Effect[Block] =
      for {
        _                     <- node.casperEff.deploy(dd)
        Created(signedBlock1) <- node.casperEff.createBlock
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
      _ = nr.header.get.parentHashes.map(PrettyPrinter.buildString) shouldBe Seq(
        PrettyPrinter.buildString(br.blockHash)
      )

      _ <- nodes.map(_.tearDownNode()).toList.sequence
    } yield ()
  }

  it should "adding equivocation blocks" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis, transforms)

      // Creates a pair that constitutes equivocation blocks
      basicDeployData0 <- ProtoUtil.basicDeploy[Effect]()
      createBlockResult1 <- nodes(0).casperEff
                             .deploy(basicDeployData0) *> nodes(0).casperEff.createBlock
      Created(signedBlock1) = createBlockResult1
      basicDeployData1      <- ProtoUtil.basicDeploy[Effect]()
      createBlockResult1Prime <- nodes(0).casperEff
                                  .deploy(basicDeployData1) *> nodes(0).casperEff.createBlock
      Created(signedBlock1Prime) = createBlockResult1Prime

      _ <- nodes(0).casperEff.addBlock(signedBlock1)
      _ <- nodes(1).receive()
      _ <- nodes(0).casperEff.addBlock(signedBlock1Prime)
      _ <- nodes(1).receive()

      _ <- nodes(1).casperEff.contains(signedBlock1) shouldBeF true
      result <- nodes(1).casperEff
                 .contains(signedBlock1Prime) shouldBeF true // we still add the equivocated block to dag

      _ <- nodes(0).tearDownNode()
      _ <- nodes(1).tearDownNode()
      _ <- nodes(1).validateBlockStorage { blockStorage =>
            for {
              _ <- blockStorage.getBlockMessage(signedBlock1.blockHash) shouldBeF Some(signedBlock1)
              result <- blockStorage.getBlockMessage(signedBlock1Prime.blockHash) shouldBeF Some(
                         signedBlock1Prime
                       )
            } yield result
          }
    } yield result
  }

  it should "not ignore adding equivocation blocks when a child is revealed later" in effectTest {
    for {
      nodes <- networkEff(validatorKeys.take(2), genesis, transforms)

      makeDeploy = (n: Int) => {
        for {
          deploy         <- ProtoUtil.basicDeploy[Effect]()
          result         <- nodes(n).casperEff.deploy(deploy) *> nodes(n).casperEff.createBlock
          Created(block) = result
        } yield block
      }

      // Creates a pair that constitutes equivocation blocks
      block1      <- makeDeploy(0)
      block1Prime <- makeDeploy(0)

      _ <- nodes(0).casperEff.addBlock(block1) shouldBeF Valid
      _ <- nodes(0).casperEff.addBlock(block1Prime) shouldBeF EquivocatedBlock
      _ <- nodes(1).clearMessages()
      _ <- nodes(1).casperEff.addBlock(block1Prime) shouldBeF Valid
      _ <- nodes(0).receive()

      _ <- nodes(0).casperEff.contains(block1) shouldBeF true
      _ <- nodes(0).casperEff.contains(block1Prime) shouldBeF true

      block2Prime <- makeDeploy(1)
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
      deploys         <- (1L to 6L).toList.traverse(_ => ProtoUtil.basicDeploy[Effect]())
      deploysWithCost = deploys.map(d => ProcessedDeploy(deploy = Some(d))).toIndexedSeq

      createBlockResult <- nodes(0).casperEff
                            .deploy(deploys(0)) *> nodes(0).casperEff.createBlock
      Created(signedBlock) = createBlockResult
      signedInvalidBlock = BlockUtil.resignBlock(
        signedBlock.withHeader(signedBlock.getHeader.withValidatorBlockSeqNum(-2)),
        nodes(0).validatorId.privateKey
      ) // Invalid seq num

      // This is going to be signed by node(1)
      blockWithInvalidJustification = buildBlockWithInvalidJustification(
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

      _ <- (1L to 10L).toList.traverse_[Effect, Unit] { _ =>
            for {
              deploy <- ProtoUtil.basicDeploy[Effect]()
              createBlockResult <- nodes(0).casperEff
                                    .deploy(deploy) *> nodes(0).casperEff.createBlock
              Created(block) = createBlockResult

              _ <- nodes(0).casperEff.addBlock(block)
              _ <- nodes(1).clearMessages() //nodes(1) misses this block
            } yield ()
          }
      deployData10 <- ProtoUtil.basicDeploy[Effect]()
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
                transforms
              )
      deployData1 <- ProtoUtil.basicDeploy[Effect]()
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
      deployData2 <- ProtoUtil.basicDeploy[Effect]()
      createBlock2Result <- nodes(1).casperEff
                             .deploy(deployData2) *> nodes(1).casperEff.createBlock
      Created(block2) = createBlock2Result
      _               <- nodes(1).casperEff.addBlock(block2)
      _               <- nodes(0).receive()
      _               <- nodes.map(_.tearDownNode()).toList.sequence
      _ <- nodes.toList.traverse_[Effect, Assertion] { node =>
            node.validateBlockStorage { blockStorage =>
              for {
                _      <- blockStorage.getBlockMessage(invalidBlock1.blockHash) shouldBeF None
                result <- blockStorage.getBlockMessage(block2.blockHash) shouldBeF Some(block2)
              } yield result
            }
          }
    } yield ()
  }

  it should "increment last finalized block as appropriate in round robin" in effectTest {
    val stake      = 10L
    val equalBonds = validators.map(_ -> stake).toMap
    val BlockMsgWithTransform(Some(genesisWithEqualBonds), transformsWithEqualBonds) =
      buildGenesis(Seq.empty, equalBonds, 0L)

    def checkLastFinalizedBlock(
        node: HashSetCasperTestNode[Effect],
        expected: Block
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
      deployDatas <- (1L to 10L).toList.traverse(_ => ProtoUtil.basicDeploy[Effect]())

      Created(block1) <- nodes(0).casperEff
                          .deploy(deployDatas(0)) *> nodes(0).casperEff.createBlock
      _ <- nodes(0).casperEff.addBlock(block1)
      _ <- nodes(1).receive()
      _ <- nodes(2).receive()

      Created(block2) <- nodes(1).casperEff
                          .deploy(deployDatas(1)) *> nodes(1).casperEff.createBlock
      _ <- nodes(1).casperEff.addBlock(block2)
      _ <- nodes(0).receive()
      _ <- nodes(2).receive()

      Created(block3) <- nodes(2).casperEff
                          .deploy(deployDatas(2)) *> nodes(2).casperEff.createBlock
      _ <- nodes(2).casperEff.addBlock(block3)
      _ <- nodes(0).receive()
      _ <- nodes(1).receive()

      Created(block4) <- nodes(0).casperEff
                          .deploy(deployDatas(3)) *> nodes(0).casperEff.createBlock
      _ <- nodes(0).casperEff.addBlock(block4)
      _ <- nodes(1).receive()
      _ <- nodes(2).receive()

      Created(block5) <- nodes(1).casperEff
                          .deploy(deployDatas(4)) *> nodes(1).casperEff.createBlock
      _ <- nodes(1).casperEff.addBlock(block5)
      _ <- nodes(0).receive()
      _ <- nodes(2).receive()

      Created(block6) <- nodes(2).casperEff
                          .deploy(deployDatas(5)) *> nodes(2).casperEff.createBlock
      _ <- nodes(2).casperEff.addBlock(block6)
      _ <- nodes(0).receive()
      _ <- nodes(1).receive()

      _                     <- checkLastFinalizedBlock(nodes(0), block1)
      pendingOrProcessedNum <- nodes(0).deployStorage.sizePendingOrProcessed()
      _                     = pendingOrProcessedNum should be(1)

      Created(block7) <- nodes(0).casperEff
                          .deploy(deployDatas(6)) *> nodes(0).casperEff.createBlock
      _ <- nodes(0).casperEff.addBlock(block7)
      _ <- nodes(1).receive()
      _ <- nodes(2).receive()

      _                     <- checkLastFinalizedBlock(nodes(0), block2)
      pendingOrProcessedNum <- nodes(0).deployStorage.sizePendingOrProcessed()
      _                     = pendingOrProcessedNum should be(2) // deploys contained in block 4 and block 7

      Created(block8) <- nodes(1).casperEff
                          .deploy(deployDatas(7)) *> nodes(1).casperEff.createBlock
      _ <- nodes(1).casperEff.addBlock(block8)
      _ <- nodes(0).receive()
      _ <- nodes(2).receive()

      _                     <- checkLastFinalizedBlock(nodes(0), block3)
      pendingOrProcessedNum <- nodes(0).deployStorage.sizePendingOrProcessed()
      _                     = pendingOrProcessedNum should be(2) // deploys contained in block 4 and block 7

      Created(block9) <- nodes(2).casperEff
                          .deploy(deployDatas(8)) *> nodes(2).casperEff.createBlock
      _ <- nodes(2).casperEff.addBlock(block9)
      _ <- nodes(0).receive()
      _ <- nodes(1).receive()

      _                     <- checkLastFinalizedBlock(nodes(0), block4)
      pendingOrProcessedNum <- nodes(0).deployStorage.sizePendingOrProcessed()
      _                     = pendingOrProcessedNum should be(1) // deploys contained in block 7

      Created(block10) <- nodes(0).casperEff
                           .deploy(deployDatas(9)) *> nodes(0).casperEff.createBlock
      _ <- nodes(0).casperEff.addBlock(block10)
      _ <- nodes(1).receive()
      _ <- nodes(2).receive()

      _                     <- checkLastFinalizedBlock(nodes(0), block5)
      pendingOrProcessedNum <- nodes(0).deployStorage.sizePendingOrProcessed()
      _                     = pendingOrProcessedNum should be(2) // deploys contained in block 7 and block 10

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "fail when deploying with insufficient gas" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._
    implicit val timeEff = new LogicalTime[Effect]

    for {
      deploy <- ProtoUtil.basicDeploy[Effect]().map { d =>
                 d.withBody(
                   d.getBody.withPayment(
                     Deploy.Code().withWasm(ByteString.copyFromUtf8("some payment code"))
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
      deploy <- ProtoUtil.basicDeploy[Effect]()
      _      <- node.casperEff.deploy(deploy)

      createBlockResult <- MultiParentCasper[Effect].createBlock
      Created(block)    = createBlockResult
    } yield assert(!block.body.get.deploys.head.isError)
  }

  it should "put orphaned deploys back into the pending deploy buffer" in effectTest {
    // Make a network where we don't validate nonces, I just want the merge conflict.
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis,
                transforms,
                maybeMakeEE =
                  Some(HashSetCasperTestNode.simpleEEApi[Effect](_, generateConflict = true))
              )

      deployA          <- ProtoUtil.basicDeploy[Effect]()
      _                <- nodes(0).casperEff.deploy(deployA)
      createA          <- nodes(0).casperEff.createBlock
      Created(blockA)  = createA
      _                <- nodes(0).casperEff.addBlock(blockA) shouldBeF Valid
      processedDeploys <- nodes(0).deployStorage.readProcessed
      _                = processedDeploys should contain(deployA)

      deployB         <- ProtoUtil.basicDeploy[Effect]()
      _               <- nodes(1).casperEff.deploy(deployB)
      createB         <- nodes(1).casperEff.createBlock
      Created(blockB) = createB
      // nodes(1) should have more weight then nodes(0) so it should take over
      _              <- nodes(0).casperEff.addBlock(blockB) shouldBeF Valid
      pendingDeploys <- nodes(0).deployStorage.readPending
      _              = pendingDeploys should contain(deployA)
      _              <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "not execute deploys which are already in the past" in effectTest {
    val node =
      standaloneEff(genesis, transforms, validatorKeys.head, faultToleranceThreshold = -1.0f)
    for {
      deploy          <- ProtoUtil.basicDeploy[Effect]()
      _               <- node.casperEff.deploy(deploy)
      create1         <- node.casperEff.createBlock
      Created(block1) = create1
      _               <- node.casperEff.addBlock(block1) shouldBeF Valid

      // Should be finalized, so not stop it appearing again as pending.
      processedDeploys <- node.deployStorage.readProcessed
      _                = processedDeploys shouldBe empty

      // Should be able to enquee the deploy again.
      _               <- node.casperEff.deploy(deploy)
      pendingDeploys1 <- node.deployStorage.readPending
      _               = pendingDeploys1 should not be empty

      // Should not put it in a block.
      createB <- node.casperEff.createBlock
      _       = createB shouldBe CreateBlockStatus.noNewDeploys

      // Should discard the deploy.
      pendingDeploys2 <- node.deployStorage.readPending
      _               = pendingDeploys2 shouldBe empty

      _ <- node.tearDown()
    } yield ()
  }

  it should "not merge blocks which have the same deploy in their history" in effectTest {
    // Make a network where we don't validate nonces, I just want the merge conflict.
    for {
      nodes <- networkEff(
                validatorKeys.take(2),
                genesis,
                transforms
              )

      deployA          <- ProtoUtil.basicDeploy[Effect]()
      _                <- nodes(0).casperEff.deploy(deployA)
      createA0         <- nodes(0).casperEff.createBlock
      Created(blockA0) = createA0
      _                <- nodes(0).casperEff.addBlock(blockA0) shouldBeF Valid

      _                <- nodes(1).casperEff.deploy(deployA)
      createA1         <- nodes(1).casperEff.createBlock
      Created(blockA1) = createA1
      _                <- nodes(1).casperEff.addBlock(blockA1) shouldBeF Valid

      // Tell nodes(1) about the block from nodes(0) which has the same deploy.
      _ <- nodes(1).casperEff.addBlock(blockA0) shouldBeF Valid

      // Try to build a new block on top of both, they shouldn't merge.
      deployB          <- ProtoUtil.basicDeploy[Effect]()
      _                <- nodes(1).casperEff.deploy(deployB)
      createB1         <- nodes(1).casperEff.createBlock
      Created(blockB1) = createB1

      // nodes(1) has more weight then nodes(0)
      _ = blockB1.getHeader.parentHashes should have size 1
      _ = blockB1.getHeader.parentHashes.head shouldBe blockA1.blockHash

      _ <- nodes.map(_.tearDown()).toList.sequence
    } yield ()
  }

  it should "return NoNewDeploys status if there are no deploys to put into the block after executing contracts" in effectTest {
    val node = standaloneEff(genesis, transforms, validatorKeys.head)
    import node._

    val deploy = ProtoUtil.basicDeploy(timestamp = 1L)

    for {
      Created(block) <- node.casperEff.deploy(deploy) *> MultiParentCasper[Effect].createBlock
      _              <- MultiParentCasper[Effect].addBlock(block)
      _              <- MultiParentCasper[Effect].createBlock shouldBeF io.casperlabs.casper.NoNewDeploys
    } yield ()
  }

  private def buildBlockWithInvalidJustification(
      deploys: immutable.IndexedSeq[ProcessedDeploy],
      signedInvalidBlock: Block
  ): Block = {
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
    ProtoUtil.signBlock(
      blockThatPointsToInvalidBlock,
      validatorKeys(1),
      Ed25519
    )
  }
}

object HashSetCasperTest {
  def createBonds(validators: Seq[PublicKey]): Map[PublicKey, Long] =
    validators.zipWithIndex.map { case (v, i) => v -> (2L * i.toLong + 1L) }.toMap

  def createGenesis(bonds: Map[PublicKey, Long]): BlockMsgWithTransform =
    buildGenesis(Seq.empty, bonds, 0L)

  def buildGenesis(
      wallets: Seq[PreWallet],
      bonds: Map[PublicKey, Long],
      timestamp: Long
  ): BlockMsgWithTransform = {
    implicit val logEff                  = new LogStub[Task]()
    val initial                          = Genesis.withoutContracts(bonds, timestamp, "casperlabs")
    implicit val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
    implicit val filesApi                = FilesAPI.create[Task]

    (for {
      blessed <- Genesis.defaultBlessedTerms[Task](
                  accountPublicKeyPath = None,
                  initialMotes = BigInt(0),
                  wallets,
                  mintCodePath = None,
                  posCodePath = None,
                  bondsFile = None
                )
      genenis <- Genesis
                  .withContracts[Task](
                    initial,
                    blessed
                  )
    } yield genenis).unsafeRunSync
  }
}
