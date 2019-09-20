package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block.{Justification, MessageType}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, HashSetCasperTestNode, StorageFixture}
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtilTest.prepareDeploys
import io.casperlabs.casper.util.execengine.{
  DeploysCheckpoint,
  ExecEngineUtil,
  ExecutionEngineServiceStub
}
import io.casperlabs.casper.validation.Errors.{DropErrorWrapper, ValidateErrorWrapper}
import io.casperlabs.casper.validation.{Validation, ValidationImpl}
import io.casperlabs.crypto.Keys.PrivateKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.models.ArbitraryConsensus
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Time
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.block._
import io.casperlabs.storage.dag._
import io.casperlabs.storage.deploy.DeployStorage
import monix.eval.Task
import monix.execution.Scheduler
import org.scalacheck.Arbitrary.arbitrary
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.collection.immutable.HashMap
import scala.concurrent.duration._

class ValidationTest
    extends FlatSpec
    with Matchers
    with BeforeAndAfterEach
    with BlockGenerator
    with StorageFixture
    with ArbitraryConsensus {
  override implicit val log: LogStub[Task] = new LogStub[Task]
  implicit val raiseValidateErr            = validation.raiseValidateErrorThroughApplicativeError[Task]
  // Necessary because errors are returned via Sync which has an error type fixed to _ <: Throwable.
  // When raise errors we wrap them with Throwable so we need to do the same here.
  implicit def wrapWithThrowable[A <: InvalidBlock](err: A): Throwable =
    ValidateErrorWrapper(err)

  implicit val consensusConfig = ConsensusConfig()

  val ed25519 = "ed25519"

  override def beforeEach(): Unit = {
    log.reset()
    timeEff.reset()
  }

  import DeriveValidation._

  def withoutStorage(t: => Task[_]) = {
    import Scheduler.Implicits.global
    t.runSyncUnsafe(5.seconds)
  }

  def createChain[F[_]: Monad: Time: BlockStorage: IndexedDagStorage](
      length: Int,
      bonds: Seq[Bond] = Seq.empty[Bond],
      creator: Validator = ByteString.EMPTY
  ): F[Block] =
    (0 until length).foldLeft(createAndStoreBlock[F](Seq.empty, bonds = bonds)) {
      case (block, _) =>
        for {
          bprev         <- block
          dag           <- IndexedDagStorage[F].getRepresentation
          latestMsgs    <- dag.latestMessages
          justification = latestMsgs.map { case (v, b) => (v, b.messageHash) }
          bnext <- createAndStoreBlock[F](
                    Seq(bprev.blockHash),
                    creator = creator,
                    bonds = bonds,
                    justifications = justification
                  )
        } yield bnext
    }

  def createChainWithRoundRobinValidators[F[_]: Monad: Time: BlockStorage: IndexedDagStorage: DeployStorage](
      length: Int,
      validatorLength: Int
  ): F[Block] = {
    val validatorRoundRobinCycle = Stream.continually(0 until validatorLength).flatten
    val validators               = List.fill(validatorLength)(generateValidator())
    (0 until length).toList
      .zip(validatorRoundRobinCycle)
      .foldLeft(
        for {
          genesis             <- createAndStoreBlock[F](Seq.empty)
          emptyLatestMessages <- HashMap.empty[Validator, BlockHash].pure[F]
        } yield (genesis, emptyLatestMessages)
      ) {
        case (acc, (_, validatorNum)) =>
          val creator = validators(validatorNum)
          for {
            unwrappedAcc            <- acc
            (block, latestMessages) = unwrappedAcc
            bnext <- createAndStoreBlock[F](
                      parentsHashList = Seq(block.blockHash),
                      creator = creator,
                      justifications = latestMessages
                    )
            latestMessagesNext = latestMessages.updated(
              bnext.getHeader.validatorPublicKey,
              bnext.blockHash
            )
          } yield (bnext, latestMessagesNext)
      }
      .map(_._1)
  }

  def signedBlock(
      i: Int
  )(implicit sk: PrivateKey, dagStorage: IndexedDagStorage[Task]): Task[Block] =
    dagStorage.lookupByIdUnsafe(i).map(block => ProtoUtil.signBlock(block, sk, Ed25519))

  implicit class ChangeBlockOps(b: Block) {
    def changeBlockNumber(n: Long): Block = {
      val header    = b.getHeader
      val newHeader = header.withRank(n)
      // NOTE: blockHash should be recalculated.
      b.withHeader(newHeader)
    }
    def changeSeqNum(n: Int): Block = {
      val header    = b.getHeader
      val newHeader = header.withValidatorBlockSeqNum(n)
      // NOTE: blockHash should be recalculated.
      b.withHeader(newHeader)
    }
    def changeValidator(key: ByteString): Block = {
      val header    = b.getHeader
      val newHeader = header.withValidatorPublicKey(key)
      // NOTE: blockHash should be recalculated.
      b.withHeader(newHeader)
    }
    def changeSigAlgorithm(sigAlgorithm: String): Block =
      b.withSignature(b.getSignature.withSigAlgorithm(sigAlgorithm))
    def changeSig(sig: ByteString): Block =
      b.withSignature(b.getSignature.withSig(sig))

  }

  // Originally validation methods wanted blocks, now they work on summaries.
  implicit def `Block => BlockSummary`(b: Block) =
    BlockSummary(b.blockHash, b.header, b.signature)

  "Block signature validation" should "return false on unknown algorithms" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      for {
        _                <- createChain[Task](2)
        unknownAlgorithm = "unknownAlgorithm"
        rsa              = "RSA"
        block0 <- dagStorage
                   .lookupByIdUnsafe(0)
                   .map(_.changeSigAlgorithm(unknownAlgorithm))
        block1 <- dagStorage
                   .lookupByIdUnsafe(1)
                   .map(_.changeSigAlgorithm(rsa))
        _ <- Validation[Task].blockSignature(block0) shouldBeF false
        _ = log.warns.last
          .contains(s"signature algorithm '$unknownAlgorithm' is unsupported") should be(
          true
        )
        _ <- Validation[Task].blockSignature(block1) shouldBeF false
        result = log.warns.last.contains(s"signature algorithm '$rsa' is unsupported") should be(
          true
        )
      } yield result
  }

  it should "return false on invalid ed25519 signatures" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      implicit val (sk, _) = Ed25519.newKeyPair
      for {
        _            <- createChain[Task](6)
        (_, wrongPk) = Ed25519.newKeyPair
        empty        = ByteString.EMPTY
        invalidKey   = ByteString.copyFrom(Base16.decode("abcdef1234567890"))
        block0       <- signedBlock(0).map(_.changeValidator(empty))
        block1       <- signedBlock(1).map(_.changeValidator(invalidKey))
        block2       <- signedBlock(2).map(_.changeValidator(ByteString.copyFrom(wrongPk)))
        block3       <- signedBlock(3).map(_.changeSig(empty))
        block4       <- signedBlock(4).map(_.changeSig(invalidKey))
        block5       <- signedBlock(5).map(_.changeSig(block0.getSignature.sig)) //wrong sig
        blocks       = Vector(block0, block1, block2, block3, block4, block5)
        _            <- blocks.existsM[Task](b => Validation[Task].blockSignature(b)) shouldBeF false
        _            = log.warns.size should be(blocks.length)
        result       = log.warns.forall(_.contains("signature is invalid")) should be(true)
      } yield result
  }

  it should "return true on valid ed25519 signatures" in withStorage { _ => _ => _ =>
    implicit val (sk, pk) = Ed25519.newKeyPair
    val block = ProtoUtil.block(
      Seq.empty,
      ByteString.EMPTY,
      ByteString.EMPTY,
      Seq.empty,
      Seq.empty,
      ProtocolVersion(1),
      Seq.empty,
      None,
      "casperlabs",
      1,
      0,
      pk,
      sk,
      Ed25519
    )
    Validation[Task].blockSignature(block) shouldBeF true
  }

  "Deploy signature validation" should "return true for valid signatures" in withoutStorage {
    val deploy = sample(arbitrary[consensus.Deploy])
    Validation[Task].deploySignature(deploy) shouldBeF true
  }

  it should "return false if a key in an approval is empty" in withoutStorage {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
    } yield d.withApprovals(d.approvals.map(x => x.withApproverPublicKey(ByteString.EMPTY)))

    val deploy = sample(genDeploy)
    Validation[Task].deploySignature(deploy) shouldBeF false
  }

  it should "return false for missing signatures" in withoutStorage {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
    } yield d.withApprovals(Nil)

    val deploy = sample(genDeploy)
    Validation[Task].deploySignature(deploy) shouldBeF false
  }

  it should "return false for invalid signatures" in withoutStorage {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
      h <- genHash
    } yield d.withApprovals(d.approvals.map(a => a.withSignature(a.getSignature.withSig(h))))

    val deploy = sample(genDeploy)
    Validation[Task].deploySignature(deploy) shouldBeF false
  }

  it should "return false if there are valid and invalid signatures mixed" in withoutStorage {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
      h <- genHash
    } yield d.withApprovals(
      d.approvals ++ d.approvals.take(1).map(a => a.withSignature(a.getSignature.withSig(h)))
    )

    val deploy = sample(genDeploy)
    Validation[Task].deploySignature(deploy) shouldBeF false
  }

  "Timestamp validation" should "not accept blocks with future time" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      for {
        _                       <- createChain[Task](1)
        block                   <- dagStorage.lookupByIdUnsafe(0)
        modifiedTimestampHeader = block.header.get.withTimestamp(99999999)
        _ <- ValidationImpl[Task]
              .timestamp(
                block.withHeader(modifiedTimestampHeader)
              )
              .attempt shouldBeF Left(InvalidUnslashableBlock)
        _      <- ValidationImpl[Task].timestamp(block).attempt shouldBeF Right(())
        _      = log.warns.size should be(1)
        result = log.warns.head.contains("block timestamp") should be(true)
      } yield result
  }

  it should "not accept blocks that were published before parent time" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      for {
        _                       <- createChain[Task](2)
        block                   <- dagStorage.lookupByIdUnsafe(1)
        modifiedTimestampHeader = block.header.get.withTimestamp(-1)
        _ <- ValidationImpl[Task]
              .timestamp(
                block.withHeader(modifiedTimestampHeader)
              )
              .attempt shouldBeF Left(InvalidUnslashableBlock)
        _      <- ValidationImpl[Task].timestamp(block).attempt shouldBeF Right(())
        _      = log.warns.size should be(1)
        result = log.warns.head.contains("block timestamp") should be(true)
      } yield result
  }

  "Block number validation" should "only accept 0 as the number for a block with no parents" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      for {
        _     <- createChain[Task](1)
        block <- dagStorage.lookupByIdUnsafe(0)
        dag   <- dagStorage.getRepresentation
        _ <- ValidationImpl[Task]
              .blockNumber(block.changeBlockNumber(1), dag)
              .attempt shouldBeF Left(
              InvalidBlockNumber
            )
        _ <- ValidationImpl[Task].blockNumber(block, dag) shouldBeF Unit
        _ = log.warns.size should be(1)
        result = log.warns.head.contains("not zero, but block has no justifications") should be(
          true
        )
      } yield result
  }

  it should "return true for sequential numbering" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      val n         = 6
      val validator = generateValidator("Validator")
      for {
        _   <- createChain[Task](n.toInt, bonds = List(Bond(validator, 1)), creator = validator)
        dag <- dagStorage.getRepresentation
        _   <- dagStorage.lookupByIdUnsafe(0) >>= (b => ValidationImpl[Task].blockNumber(b, dag))
        _   <- dagStorage.lookupByIdUnsafe(1) >>= (b => ValidationImpl[Task].blockNumber(b, dag))
        _   <- dagStorage.lookupByIdUnsafe(2) >>= (b => ValidationImpl[Task].blockNumber(b, dag))
        _   <- dagStorage.lookupByIdUnsafe(3) >>= (b => ValidationImpl[Task].blockNumber(b, dag))
        _   <- dagStorage.lookupByIdUnsafe(4) >>= (b => ValidationImpl[Task].blockNumber(b, dag))
        _   <- dagStorage.lookupByIdUnsafe(5) >>= (b => ValidationImpl[Task].blockNumber(b, dag))
        _ <- (0 until n).toList.forallM[Task] { i =>
              (dagStorage.lookupByIdUnsafe(i) >>= (
                  b => ValidationImpl[Task].blockNumber(b, dag)
              )).map(_ => true)
            } shouldBeF true
        result = log.warns should be(Nil)
      } yield result
  }

  it should "correctly validate a multiparent block where the parents have different block numbers" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      def createBlockWithNumber(
          n: Long,
          justificationBlocks: Seq[Block] = Nil
      ): Task[Block] = {
        val blockWithNumber = Block().changeBlockNumber(n)
        val header = blockWithNumber.getHeader
          .withJustifications(
            justificationBlocks.map(b => Justification(b.getHeader.validatorPublicKey, b.blockHash))
          )
        val block = ProtoUtil.unsignedBlockProto(blockWithNumber.getBody, header)
        blockStorage.put(block.blockHash, block, Seq.empty) *>
          block.pure[Task]
      }
      val validator = generateValidator("Validator")

      for {
        _   <- createChain[Task](8, bonds = List(Bond(validator, 1)), creator = validator) // Note we need to create a useless chain to satisfy the assert in TopoSort
        b1  <- createBlockWithNumber(3)
        b2  <- createBlockWithNumber(7)
        b3  <- createBlockWithNumber(8, Seq(b1, b2))
        dag <- dagStorage.getRepresentation
        _   <- ValidationImpl[Task].blockNumber(b3, dag) shouldBeF Unit
        result <- ValidationImpl[Task]
                   .blockNumber(b3.changeBlockNumber(4), dag)
                   .attempt shouldBeF Left(
                   InvalidBlockNumber
                 )
      } yield result
  }

  "Sequence number validation" should "only accept 0 as the number for a block with no parents" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      for {
        _     <- createChain[Task](1)
        block <- dagStorage.lookupByIdUnsafe(0)
        _ = assert(
          block.getHeader.justifications.isEmpty,
          "Justification list of Genesis block should be empty."
        )
        dag <- dagStorage.getRepresentation
        _ <- ValidationImpl[Task]
              .sequenceNumber(
                block.withHeader(block.getHeader.withValidatorBlockSeqNum(1)),
                dag
              )
              .attempt shouldBeF Left(
              InvalidSequenceNumber
            )
        _ <- ValidationImpl[Task].sequenceNumber(
              block.withHeader(block.getHeader.withValidatorBlockSeqNum(0)),
              dag
            ) shouldBeF Unit
      } yield ()
  }

  it should "return false for non-sequential numbering" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage =>
      for {
        _     <- createChainWithRoundRobinValidators[Task](2, 2)
        block <- dagStorage.lookupByIdUnsafe(1)
        dag   <- dagStorage.getRepresentation
        _ <- ValidationImpl[Task]
              .sequenceNumber(
                block.withHeader(block.getHeader.withValidatorBlockSeqNum(2)),
                dag
              )
              .attempt shouldBeF Left(
              InvalidSequenceNumber
            )
        result = log.warns.size should be(1)
      } yield result
  }

  it should "return true for sequential numbering" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage =>
      val n              = 20
      val validatorCount = 3
      for {
        _ <- createChainWithRoundRobinValidators[Task](n, validatorCount)
        _ <- (1 to n).toList.forallM[Task](
              i =>
                for {
                  block <- dagStorage.lookupByIdUnsafe(i)
                  dag   <- dagStorage.getRepresentation
                  _ <- ValidationImpl[Task].sequenceNumber(
                        block,
                        dag
                      )
                } yield true
            ) shouldBeF true
        result = log.warns should be(Nil)
      } yield result
  }

  "Sender validation" should "return true for genesis and blocks from bonded validators and false otherwise" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      val validator = generateValidator("Validator")
      val impostor  = generateValidator("Impostor")
      for {
        _ <- createChain[Task](3, List(Bond(validator, 1)))
        _ <- dagStorage.lookupByIdUnsafe(0)
        validBlock <- dagStorage
                       .lookupByIdUnsafe(1)
                       .map(_.changeValidator(validator))
        invalidBlock <- dagStorage
                         .lookupByIdUnsafe(2)
                         .map(_.changeValidator(impostor))
        _      <- Validation[Task].blockSender(validBlock) shouldBeF true
        result <- Validation[Task].blockSender(invalidBlock) shouldBeF false
      } yield result
  }

  "Parent validation" should "return true for proper justifications and false otherwise" in withStorage {
    implicit blockStorage => implicit dagStorage => implicit deployStorage =>
      val validators = Vector(
        generateValidator("V1"),
        generateValidator("V2"),
        generateValidator("V3")
      )
      val bonds = validators.zipWithIndex.map {
        case (v, i) => Bond(v, 2L * i.toLong + 1L)
      }

      def latestMessages(messages: Seq[Block]): Map[Validator, BlockHash] =
        messages.map(b => b.getHeader.validatorPublicKey -> b.blockHash).toMap

      def createValidatorBlock[F[_]: Monad: Time: BlockStorage: IndexedDagStorage: DeployStorage](
          parents: Seq[Block],
          justifications: Seq[Block],
          validator: Int
      ): F[Block] =
        for {
          deploy <- ProtoUtil.basicProcessedDeploy[F]()
          block <- createAndStoreBlock[F](
                    parents.map(_.blockHash),
                    creator = validators(validator),
                    bonds = bonds,
                    deploys = Seq(deploy),
                    justifications = latestMessages(justifications)
                  )
        } yield block

      for {
        b0 <- createAndStoreBlock[Task](Seq.empty, bonds = bonds)
        b1 <- createValidatorBlock[Task](Seq(b0), Seq(b0), 0)
        b2 <- createValidatorBlock[Task](Seq(b0), Seq(b0), 1)
        b3 <- createValidatorBlock[Task](Seq(b0), Seq(b0), 2)
        b4 <- createValidatorBlock[Task](Seq(b1), Seq(b1), 0)
        b5 <- createValidatorBlock[Task](Seq(b3, b2, b1), Seq(b1, b2, b3), 1)
        b6 <- createValidatorBlock[Task](Seq(b5, b4), Seq(b1, b4, b5), 0)
        b7 <- createValidatorBlock[Task](Seq(b4), Seq(b1, b4, b5), 1) //not highest score parent
        b8 <- createValidatorBlock[Task](Seq(b1, b2, b3), Seq(b1, b2, b3), 2) //parents wrong order
        b9 <- createValidatorBlock[Task](Seq(b6), Seq.empty, 0)
               .map(b => b.withHeader(b.getHeader.withJustifications(Seq.empty))) //empty justification
        b10 <- createValidatorBlock[Task](Seq.empty, Seq.empty, 0) //empty justification
        result <- for {
                   dag              <- dagStorage.getRepresentation
                   genesisBlockHash = b0.blockHash

                   // Valid
                   _ <- Validation[Task].parents(b1, genesisBlockHash, dag)
                   _ <- Validation[Task].parents(b2, genesisBlockHash, dag)
                   _ <- Validation[Task].parents(b3, genesisBlockHash, dag)
                   _ <- Validation[Task].parents(b4, genesisBlockHash, dag)
                   _ <- Validation[Task].parents(b5, genesisBlockHash, dag)
                   _ <- Validation[Task].parents(b6, genesisBlockHash, dag)

                   // Not valid
                   _ <- Validation[Task].parents(b7, genesisBlockHash, dag).attempt
                   _ <- Validation[Task].parents(b8, genesisBlockHash, dag).attempt
                   _ <- Validation[Task].parents(b9, genesisBlockHash, dag).attempt

                   _ = log.warns should have size 3
                   _ = log.warns.forall(
                     _.matches(
                       ".* block parents .* did not match estimate .* based on justification .*"
                     )
                   ) should be(
                     true
                   )

                   result <- Validation[Task]
                              .parents(b10, genesisBlockHash, dag)
                              .attempt shouldBeF Left(ValidateErrorWrapper(InvalidParents))

                 } yield result
      } yield result
  }

  // Creates a block with an invalid block number and sequence number
  "Block validation" should "short circuit after first invalidity" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      for {
        _        <- createChain[Task](2)
        block    <- dagStorage.lookupByIdUnsafe(1)
        dag      <- dagStorage.getRepresentation
        (sk, pk) = Ed25519.newKeyPair
        signedBlock = ProtoUtil.signBlock(
          block.changeBlockNumber(17).changeSeqNum(1),
          sk,
          Ed25519
        )
        result <- Validation[Task]
                   .blockFull(
                     signedBlock,
                     dag,
                     "casperlabs",
                     Block.defaultInstance.some
                   )
                   .attempt
        _ = result shouldBe Left(
          DropErrorWrapper(InvalidUnslashableBlock)
        )

      } yield ()
  }

  "Bonds cache validation" should "succeed on a valid block and fail on modified bonds" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      val (_, validators)                         = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
      val bonds                                   = HashSetCasperTest.createBonds(validators)
      val BlockMsgWithTransform(Some(genesis), _) = HashSetCasperTest.createGenesis(bonds)
      val genesisBonds                            = ProtoUtil.bonds(genesis)
      implicit val casperSmartContractsApi        = ExecutionEngineServiceStub.noOpApi[Task]()
      implicit val log                            = new LogStub[Task]
      for {
        dag <- dagStorage.getRepresentation
        _ <- ExecutionEngineServiceStub
              .validateBlockCheckpoint[Task](
                genesis,
                dag
              )
        _                 <- Validation[Task].bondsCache(genesis, genesisBonds) shouldBeF Unit
        modifiedBonds     = Seq.empty[Bond]
        modifiedPostState = genesis.getHeader.getState.withBonds(modifiedBonds)
        modifiedHeader    = genesis.getHeader.withState(modifiedPostState)
        modifiedGenesis   = genesis.withHeader(modifiedHeader)
        result <- Validation[Task]
                   .bondsCache(modifiedGenesis, genesisBonds)
                   .attempt shouldBeF Left(
                   InvalidBondsCache
                 )
      } yield result
  }

  "Field format validation" should "succeed on a valid block and fail on empty fields" in withStorage {
    _ => _ => _ =>
      implicit val log                          = new LogStub[Task]()
      val (sk, pk)                              = Ed25519.newKeyPair
      val BlockMsgWithTransform(Some(block), _) = HashSetCasperTest.createGenesis(Map(pk -> 1))
      val genesis                               = ProtoUtil.signBlock(block, sk, Ed25519)

      for {
        _ <- Validation[Task].formatOfFields(genesis) shouldBeF true
        _ <- Validation[Task].formatOfFields(genesis.withBlockHash(ByteString.EMPTY)) shouldBeF false
        _ <- Validation[Task].formatOfFields(genesis.clearHeader) shouldBeF false
        _ <- Validation[Task].formatOfFields(genesis.clearBody) shouldBeF true // Body is only checked in `Validate.blockFull`
        _ <- Validation[Task].formatOfFields(
              genesis.withSignature(genesis.getSignature.withSig(ByteString.EMPTY))
            ) shouldBeF false
        _ <- Validation[Task].formatOfFields(
              genesis.withSignature(genesis.getSignature.withSigAlgorithm(""))
            ) shouldBeF false
        _ <- Validation[Task].formatOfFields(
              genesis.withHeader(genesis.getHeader.withChainId(""))
            ) shouldBeF false
        _ <- Validation[Task].formatOfFields(genesis.withHeader(genesis.getHeader.clearState)) shouldBeF false
        _ <- Validation[Task].formatOfFields(
              genesis.withHeader(
                genesis.getHeader
                  .withState(genesis.getHeader.getState.withPostStateHash(ByteString.EMPTY))
              )
            ) shouldBeF false
        _ <- Validation[Task].formatOfFields(
              genesis.withHeader(genesis.getHeader.withBodyHash(ByteString.EMPTY))
            ) shouldBeF false
      } yield ()
  }

  "Deploy hash validation" should "return false for invalid hashes" in {
    val genDeploy = for {
      d <- arbitrary[consensus.Deploy]
      h <- genHash
    } yield d.withDeployHash(h)

    val deploy = sample(genDeploy)
    Validation[Task].deployHash(deploy) shouldBeF false
  }

  it should "return true for valid hashes" in {
    val deploy = sample(arbitrary[consensus.Deploy])
    Validation[Task].deployHash(deploy) shouldBeF true
  }

  "Processed deploy validation" should "fail a block with a deploy having an invalid hash" in withStorage {
    _ => _ => _ =>
      val block = sample {
        for {
          b <- arbitrary[consensus.Block]
          h <- genHash
        } yield b.withBody(
          b.getBody.withDeploys(
            b.getBody.deploys.take(1).map(x => x.withDeploy(x.getDeploy.withDeployHash(h))) ++
              b.getBody.deploys.tail
          )
        )
      }
      for {
        result <- ValidationImpl[Task].deployHashes(block).attempt
        _      = result shouldBe Left(ValidateErrorWrapper(InvalidDeployHash))
      } yield ()
  }

  it should "fail a block with a deploy having no signature" in withStorage { _ => _ => _ =>
    val block = sample {
      for {
        b <- arbitrary[consensus.Block]
      } yield b.withBody(
        b.getBody.withDeploys(
          b.getBody.deploys.take(1).map(x => x.withDeploy(x.getDeploy.withApprovals(Seq.empty))) ++
            b.getBody.deploys.tail
        )
      )
    }
    for {
      result <- ValidationImpl[Task].deploySignatures(block).attempt
      _      = result shouldBe Left(ValidateErrorWrapper(InvalidDeploySignature))
    } yield ()
  }

  it should "fail a block with a deploy having an invalid signature" in withStorage { _ => _ => _ =>
    val block = sample {
      for {
        b <- arbitrary[consensus.Block]
        h <- genHash
      } yield b.withBody(
        b.getBody.withDeploys(
          b.getBody.deploys
            .take(1)
            .map(
              x =>
                x.withDeploy(
                  x.getDeploy.withApprovals(
                    x.getDeploy.approvals.map(a => a.withSignature(a.getSignature.withSig(h)))
                  )
                )
            ) ++
            b.getBody.deploys.tail
        )
      )
    }
    for {
      result <- ValidationImpl[Task].deploySignatures(block).attempt
      _      = result shouldBe Left(ValidateErrorWrapper(InvalidDeploySignature))
    } yield ()
  }

  "Block hash format validation" should "fail on invalid hash" in withStorage { _ => _ => _ =>
    val (sk, pk) = Ed25519.newKeyPair
    val BlockMsgWithTransform(Some(block), _) =
      HashSetCasperTest.createGenesis(Map(pk -> 1))
    val signedBlock = ProtoUtil.signBlock(block, sk, Ed25519)
    for {
      _ <- ValidationImpl[Task].blockHash(signedBlock) shouldBeF Unit
      result <- ValidationImpl[Task]
                 .blockHash(
                   signedBlock.withBlockHash(ByteString.copyFromUtf8("123"))
                 )
                 .attempt shouldBeF Left(InvalidBlockHash)
    } yield result
  }

  "Block deploy count validation" should "fail on invalid number of deploys" in withStorage {
    _ => _ => _ =>
      val (sk, pk) = Ed25519.newKeyPair
      val BlockMsgWithTransform(Some(block), _) =
        HashSetCasperTest.createGenesis(Map(pk -> 1))
      val signedBlock = ProtoUtil.signBlock(block, sk, Ed25519)
      for {
        _ <- ValidationImpl[Task].deployCount(signedBlock) shouldBeF Unit
        result <- ValidationImpl[Task]
                   .deployCount(
                     signedBlock.withHeader(signedBlock.header.get.withDeployCount(100))
                   )
                   .attempt shouldBeF Left(InvalidDeployCount)
      } yield result
  }

  "Block version validation" should "work" in withStorage { _ => _ => _ =>
    val (sk, pk)                              = Ed25519.newKeyPair
    val BlockMsgWithTransform(Some(block), _) = HashSetCasperTest.createGenesis(Map(pk -> 1))
    // Genesis' block version is 1.  `missingProtocolVersionForBlock` will fail ProtocolVersion lookup
    // while `protocolVersionForGenesisBlock` returns proper one (version=1)
    val missingProtocolVersionForBlock: Long => ProtocolVersion = _ => ProtocolVersion(-1)
    val protocolVersionForGenesisBlock: Long => ProtocolVersion = _ => ProtocolVersion(1)
    val signedBlock                                             = ProtoUtil.signBlock(block, sk, Ed25519)

    for {
      _ <- Validation[Task].version(
            signedBlock,
            missingProtocolVersionForBlock
          ) shouldBeF false
      result <- Validation[Task].version(
                 signedBlock,
                 protocolVersionForGenesisBlock
               ) shouldBeF true
    } yield result
  }

  "validateTransactions" should "return InvalidPreStateHash when preStateHash of block is not correct" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      implicit val executionEngineService: ExecutionEngineService[Task] =
        HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
      val contract = ByteString.copyFromUtf8("some contract")

      val genesisDeploysWithCost = prepareDeploys(Vector.empty, 1)
      val b1DeploysWithCost      = prepareDeploys(Vector(contract), 2)
      val b2DeploysWithCost      = prepareDeploys(Vector(contract), 1)
      val b3DeploysWithCost      = prepareDeploys(Vector.empty, 5)
      val invalidHash            = ByteString.copyFromUtf8("invalid")

      for {
        genesis <- createAndStoreBlock[Task](Seq.empty, deploys = genesisDeploysWithCost)
        b1      <- createAndStoreBlock[Task](Seq(genesis.blockHash), deploys = b1DeploysWithCost)
        b2      <- createAndStoreBlock[Task](Seq(genesis.blockHash), deploys = b2DeploysWithCost)
        // set wrong preStateHash for b3
        b3 <- createAndStoreBlock[Task](
               Seq(b1.blockHash, b2.blockHash),
               deploys = b3DeploysWithCost,
               preStateHash = invalidHash
             )
        dag <- dagStorage.getRepresentation

        // calls Validate.transactions internally
        postState <- ExecutionEngineServiceStub.validateBlockCheckpoint[Task](
                      b3,
                      dag
                    )
      } yield postState shouldBe Left(ValidateErrorWrapper(InvalidPreStateHash))
  }

  "deployUniqueness" should "return InvalidRepeatDeploy when a deploy is present in an ancestor" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      val contract        = ByteString.copyFromUtf8("some contract")
      val deploysWithCost = prepareDeploys(Vector(contract), 1)
      for {
        genesis <- createAndStoreBlock[Task](Seq.empty, deploys = deploysWithCost)
        block   <- createAndStoreBlock[Task](Seq(genesis.blockHash), deploys = deploysWithCost)
        dag     <- dagStorage.getRepresentation
        result  <- ValidationImpl[Task].deployUniqueness(block, dag).attempt
      } yield result shouldBe Left(ValidateErrorWrapper(InvalidRepeatDeploy))
  }

  it should "return InvalidRepeatDeploy when a deploy is present in the body twice" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      val contract        = ByteString.copyFromUtf8("some contract")
      val deploysWithCost = prepareDeploys(Vector(contract), 1)
      for {
        genesis <- createAndStoreBlock[Task](
                    Seq.empty,
                    deploys = deploysWithCost ++ deploysWithCost
                  )
        dag    <- dagStorage.getRepresentation
        result <- ValidationImpl[Task].deployUniqueness(genesis, dag).attempt
      } yield result shouldBe Left(ValidateErrorWrapper(InvalidRepeatDeploy))
  }

  it should "return InvalidPostStateHash when postStateHash of block is not correct" in withStorage {
    implicit blockStorage => implicit dagStorage => _ =>
      implicit val executionEngineService: ExecutionEngineService[Task] =
        HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
      val deploys = Vector(ByteString.EMPTY)
        .map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis))
      val processedDeploys = deploys.map(d => Block.ProcessedDeploy().withDeploy(d).withCost(1))
      val invalidHash      = ByteString.copyFromUtf8("invalid")
      for {
        genesis <- createAndStoreBlock[Task](
                    Seq.empty,
                    deploys = processedDeploys,
                    postStateHash = invalidHash
                  )
        dag <- dagStorage.getRepresentation
        // calls Validate.transactions internally
        validateResult <- ExecutionEngineServiceStub.validateBlockCheckpoint[Task](
                           genesis,
                           dag
                         )
      } yield {
        validateResult match {
          case Left(ValidateErrorWrapper(InvalidPostStateHash)) =>
          case Left(ValidateErrorWrapper(other)) =>
            fail(s"Expected InvalidPostStateHash, got $other")
          case other => fail(s"Unexpected result: $other")
        }
      }
  }

  it should "return a checkpoint with the right hash for a valid block" in withStorage {
    implicit val executionEngineService: ExecutionEngineService[Task] =
      HashSetCasperTestNode.simpleEEApi[Task](Map.empty)
    implicit blockStorage => implicit dagStorage => implicit deployStorage =>
      val deploys =
        Vector(
          ByteString.EMPTY
        ).map(ProtoUtil.sourceDeploy(_, System.currentTimeMillis))
      implicit val deploySelection: DeploySelection[Task] = DeploySelection.create[Task](
        5 * 1024 * 1024
      )
      for {
        _ <- deployStorage.addAsPending(deploys.toList)
        deploysCheckpoint <- ExecEngineUtil.computeDeploysCheckpoint[Task](
                              ExecEngineUtil.MergeResult.empty,
                              deploys.map(_.deployHash).toSet,
                              System.currentTimeMillis,
                              ProtocolVersion(1)
                            )
        DeploysCheckpoint(
          preStateHash,
          computedPostStateHash,
          bondedValidators,
          processedDeploys,
          _
        ) = deploysCheckpoint
        block <- createAndStoreBlock[Task](
                  Seq.empty,
                  deploys = processedDeploys,
                  postStateHash = computedPostStateHash,
                  preStateHash = preStateHash,
                  bonds = bondedValidators
                )
        dag2 <- dagStorage.getRepresentation

        // calls Validate.transactions internally
        validateResult <- ExecutionEngineServiceStub.validateBlockCheckpoint[Task](
                           block,
                           dag2
                         )
        Right(postStateHash) = validateResult
      } yield postStateHash should be(computedPostStateHash)
  }

  // TODO: Bring back once there is an easy way to create a _valid_ block.
  ignore should "return InvalidTargetHash for a message of type ballot that has invalid number of parents" in withStorage {
    _ => implicit dagStorage => _ =>
      import io.casperlabs.models.BlockImplicits._
      val chainId = "test"
      for {
        blockA <- createBlock[Task](
                   parentsHashList = Seq.empty,
                   messageType = MessageType.BALLOT,
                   chainId = chainId
                 )
        blockB <- createBlock[Task](
                   parentsHashList =
                     Seq(ByteString.EMPTY, ByteString.copyFrom(Array.ofDim[Byte](32))),
                   messageType = MessageType.BALLOT,
                   chainId = chainId
                 )
        _ <- ValidationImpl[Task]
              .blockSummary(BlockSummary.fromBlock(blockA), chainId)
              .attempt shouldBeF Left(
              ValidateErrorWrapper(InvalidTargetHash)
            )
        _ <- ValidationImpl[Task]
              .blockSummary(BlockSummary.fromBlock(blockB), chainId)
              .attempt shouldBeF Left(
              ValidateErrorWrapper(InvalidTargetHash)
            )
      } yield ()
  }

}
