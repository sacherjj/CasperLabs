package io.casperlabs.casper

import java.nio.file.Files

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.{BlockStore, IndexedBlockDagStorage}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.helper.{BlockDagStorageFixture, BlockGenerator, BlockUtil}
import io.casperlabs.casper.helper.BlockGenerator._
import io.casperlabs.casper.protocol._
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.rholang.RuntimeManager
import io.casperlabs.catscontrib.ToAbstractContext
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.signatures.Ed25519
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Time
import io.casperlabs.casper.scalatestcontrib._
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.rholang.Resources.mkRuntimeManager
import io.casperlabs.ipc.TransformEntry
import io.casperlabs.models.BlockMetadata
import io.casperlabs.smartcontracts.ExecutionEngineService
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{BeforeAndAfterEach, FlatSpec, Matchers}

import scala.collection.immutable.HashMap

class ValidateTest
    extends FlatSpec
    with Matchers
    with BeforeAndAfterEach
    with BlockGenerator
    with BlockDagStorageFixture {
  implicit val log              = new LogStub[Task]
  implicit val absId            = ToAbstractContext.idToAbstractContext
  implicit val raiseValidateErr = Validate.raiseValidateErrorThroughSync[Task]
  // Necessary because errors are returned via Sync which has an error type fixed to _ <: Throwable.
  // When raise errors we wrap them with Throwable so we need to do the same here.
  implicit def wrapWithThrowable[A <: InvalidBlock](err: A): Throwable =
    Validate.ValidateErrorWrapper(err)

  val ed25519 = "ed25519"

  override def beforeEach(): Unit = {
    log.reset()
    timeEff.reset()
  }

  def createChain[F[_]: Monad: Time: BlockStore: IndexedBlockDagStorage](
      length: Int,
      bonds: Seq[Bond] = Seq.empty[Bond]
  ): F[BlockMessage] =
    (0 until length).foldLeft(createBlock[F](Seq.empty, bonds = bonds)) {
      case (block, _) =>
        for {
          bprev <- block
          bnext <- createBlock[F](Seq(bprev.blockHash), bonds = bonds)
        } yield bnext
    }

  def createChainWithRoundRobinValidators[F[_]: Monad: Time: BlockStore: IndexedBlockDagStorage](
      length: Int,
      validatorLength: Int
  ): F[BlockMessage] = {
    val validatorRoundRobinCycle = Stream.continually(0 until validatorLength).flatten
    val validators               = List.fill(validatorLength)(generateValidator())
    (0 until length).toList
      .zip(validatorRoundRobinCycle)
      .foldLeft(
        for {
          genesis             <- createBlock[F](Seq.empty)
          emptyLatestMessages <- HashMap.empty[Validator, BlockHash].pure[F]
        } yield (genesis, emptyLatestMessages)
      ) {
        case (acc, (_, validatorNum)) =>
          val creator = validators(validatorNum)
          for {
            unwrappedAcc            <- acc
            (block, latestMessages) = unwrappedAcc
            bnext <- createBlock[F](
                      parentsHashList = Seq(block.blockHash),
                      creator = creator,
                      justifications = latestMessages
                    )
            latestMessagesNext = latestMessages.updated(bnext.sender, bnext.blockHash)
          } yield (bnext, latestMessagesNext)
      }
      .map(_._1)
  }

  def signedBlock(
      i: Int
  )(implicit sk: Array[Byte], blockDagStorage: IndexedBlockDagStorage[Task]): Task[BlockMessage] = {
    val pk = Ed25519.toPublic(sk)
    for {
      block  <- blockDagStorage.lookupByIdUnsafe(i)
      dag    <- blockDagStorage.getRepresentation
      result <- ProtoUtil.signBlock[Task](block, dag, pk, sk, "ed25519", "rchain")
    } yield result
  }

  implicit class ChangeBlockNumber(b: BlockMessage) {
    def withBlockNumber(n: Long): BlockMessage = {
      val body     = b.body.getOrElse(Body())
      val state    = body.getState
      val newState = state.withBlockNumber(n)

      val header    = b.header.getOrElse(Header())
      val newHeader = header.withPostStateHash(ProtoUtil.protoHash(newState))

      b.withBody(body.withState(newState)).withHeader(newHeader)
    }
  }

  "Block signature validation" should "return false on unknown algorithms" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        _                <- createChain[Task](2)
        unknownAlgorithm = "unknownAlgorithm"
        rsa              = "RSA"
        block0           <- blockDagStorage.lookupByIdUnsafe(0).map(_.withSigAlgorithm(unknownAlgorithm))
        block1           <- blockDagStorage.lookupByIdUnsafe(1).map(_.withSigAlgorithm(rsa))
        _                <- Validate.blockSignature[Task](block0) shouldBeF false
        _ = log.warns.last
          .contains(s"signature algorithm $unknownAlgorithm is unsupported") should be(
          true
        )
        _      <- Validate.blockSignature[Task](block1) shouldBeF false
        result = log.warns.last.contains(s"signature algorithm $rsa is unsupported") should be(true)
      } yield result
  }

  it should "return false on invalid ed25519 signatures" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      implicit val (sk, _) = Ed25519.newKeyPair
      for {
        _            <- createChain[Task](6)
        (_, wrongPk) = Ed25519.newKeyPair
        empty        = ByteString.EMPTY
        invalidKey   = ByteString.copyFrom(Base16.decode("abcdef1234567890"))
        block0       <- signedBlock(0).map(_.withSender(empty))
        block1       <- signedBlock(1).map(_.withSender(invalidKey))
        block2       <- signedBlock(2).map(_.withSender(ByteString.copyFrom(wrongPk)))
        block3       <- signedBlock(3).map(_.withSig(empty))
        block4       <- signedBlock(4).map(_.withSig(invalidKey))
        block5       <- signedBlock(5).map(_.withSig(block0.sig)) //wrong sig
        blocks       = Vector(block0, block1, block2, block3, block4, block5)
        _            <- blocks.existsM[Task](Validate.blockSignature[Task]) shouldBeF false
        _            = log.warns.size should be(blocks.length)
        result       = log.warns.forall(_.contains("signature is invalid")) should be(true)
      } yield result
  }

  it should "return true on valid ed25519 signatures" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val n                = 6
      implicit val (sk, _) = Ed25519.newKeyPair
      for {
        _ <- createChain[Task](n)
        condition <- (0 until n).toList.forallM[Task] { i =>
                      for {
                        block  <- signedBlock(i)
                        result <- Validate.blockSignature[Task](block)
                      } yield result
                    }
        _      = condition should be(true)
        result = log.warns should be(Nil)
      } yield result
  }

  "Timestamp validation" should "not accept blocks with future time" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        _                       <- createChain[Task](1)
        block                   <- blockDagStorage.lookupByIdUnsafe(0)
        modifiedTimestampHeader = block.header.get.withTimestamp(99999999)
        dag                     <- blockDagStorage.getRepresentation
        _ <- Validate
              .timestamp[Task](
                block.withHeader(modifiedTimestampHeader),
                dag
              )
              .attempt shouldBeF Left(InvalidUnslashableBlock)
        _      <- Validate.timestamp[Task](block, dag) shouldBeF Valid.asRight[InvalidBlock]
        _      = log.warns.size should be(1)
        result = log.warns.head.contains("block timestamp") should be(true)
      } yield result
  }

  it should "not accept blocks that were published before parent time" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        _                       <- createChain[Task](2)
        block                   <- blockDagStorage.lookupByIdUnsafe(1)
        modifiedTimestampHeader = block.header.get.withTimestamp(-1)
        dag                     <- blockDagStorage.getRepresentation
        _ <- Validate
              .timestamp[Task](
                block.withHeader(modifiedTimestampHeader),
                dag
              )
              .attempt shouldBeF Left(InvalidUnslashableBlock)
        _      <- Validate.timestamp[Task](block, dag).attempt shouldBeF Right(Valid)
        _      = log.warns.size should be(1)
        result = log.warns.head.contains("block timestamp") should be(true)
      } yield result
  }

  "Block number validation" should "only accept 0 as the number for a block with no parents" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        _     <- createChain[Task](1)
        block <- blockDagStorage.lookupByIdUnsafe(0)
        _ <- Validate.blockNumber[Task](block.withBlockNumber(1)).attempt shouldBeF Left(
              InvalidBlockNumber
            )
        _      <- Validate.blockNumber[Task](block) shouldBeF Unit
        _      = log.warns.size should be(1)
        result = log.warns.head.contains("not zero, but block has no parents") should be(true)
      } yield result
  }

  it should "return true for sequential numbering" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val n = 6
      for {
        _ <- createChain[Task](n)
        _ <- blockDagStorage.lookupByIdUnsafe(0) >>= Validate.blockNumber[Task]
        _ <- blockDagStorage.lookupByIdUnsafe(1) >>= Validate.blockNumber[Task]
        _ <- blockDagStorage.lookupByIdUnsafe(2) >>= Validate.blockNumber[Task]
        _ <- blockDagStorage.lookupByIdUnsafe(3) >>= Validate.blockNumber[Task]
        _ <- blockDagStorage.lookupByIdUnsafe(4) >>= Validate.blockNumber[Task]
        _ <- blockDagStorage.lookupByIdUnsafe(5) >>= Validate.blockNumber[Task]
        _ <- (0 until n).toList.forallM[Task] { i =>
              (blockDagStorage.lookupByIdUnsafe(i) >>= Validate.blockNumber[Task])
                .map(_ => true)
            } shouldBeF true
        result = log.warns should be(Nil)
      } yield result
  }

  it should "correctly validate a multiparent block where the parents have different block numbers" in withStorage {
    implicit blockStore => _ =>
      def createBlockWithNumber(
          n: Long,
          parentHashes: Seq[ByteString] = Nil
      ): Task[BlockMessage] = {
        val blockWithNumber = BlockMessage.defaultInstance.withBlockNumber(n)
        val header          = blockWithNumber.getHeader.withParentsHashList(parentHashes)
        val hash            = ProtoUtil.hashUnsignedBlock(header, Nil)
        val block           = blockWithNumber.withHeader(header).withBlockHash(hash)

        blockStore.put(hash, block) *> block.pure[Task]
      }

      for {
        b1 <- createBlockWithNumber(3)
        b2 <- createBlockWithNumber(7)
        b3 <- createBlockWithNumber(8, Seq(b1.blockHash, b2.blockHash))
        _  <- Validate.blockNumber[Task](b3) shouldBeF Unit
        result <- Validate.blockNumber[Task](b3.withBlockNumber(4)).attempt shouldBeF Left(
                   InvalidBlockNumber
                 )
      } yield result
  }

  "Sequence number validation" should "only accept 0 as the number for a block with no parents" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        _     <- createChain[Task](1)
        block <- blockDagStorage.lookupByIdUnsafe(0)
        dag   <- blockDagStorage.getRepresentation
        _ <- Validate.sequenceNumber[Task](block.withSeqNum(1), dag).attempt shouldBeF Left(
              InvalidSequenceNumber
            )
        _      <- Validate.sequenceNumber[Task](block, dag) shouldBeF Unit
        result = log.warns.size should be(1)
      } yield result
  }

  it should "return false for non-sequential numbering" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        _     <- createChain[Task](2)
        block <- blockDagStorage.lookupByIdUnsafe(1)
        dag   <- blockDagStorage.getRepresentation
        _ <- Validate.sequenceNumber[Task](block.withSeqNum(1), dag).attempt shouldBeF Left(
              InvalidSequenceNumber
            )
        result = log.warns.size should be(1)
      } yield result
  }

  it should "return true for sequential numbering" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val n              = 20
      val validatorCount = 3
      for {
        _ <- createChainWithRoundRobinValidators[Task](n, validatorCount)
        _ <- (0 until n).toList.forallM[Task](
              i =>
                for {
                  block <- blockDagStorage.lookupByIdUnsafe(i)
                  dag   <- blockDagStorage.getRepresentation
                  result <- Validate.sequenceNumber[Task](
                             block,
                             dag
                           )
                } yield true
            ) shouldBeF true
        result = log.warns should be(Nil)
      } yield result
  }

  "Sender validation" should "return true for genesis and blocks from bonded validators and false otherwise" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val validator = generateValidator("Validator")
      val impostor  = generateValidator("Impostor")
      for {
        _            <- createChain[Task](3, List(Bond(validator, 1)))
        genesis      <- blockDagStorage.lookupByIdUnsafe(0)
        validBlock   <- blockDagStorage.lookupByIdUnsafe(1).map(_.withSender(validator))
        invalidBlock <- blockDagStorage.lookupByIdUnsafe(2).map(_.withSender(impostor))
        dag          <- blockDagStorage.getRepresentation
        _            <- Validate.blockSender[Task](genesis, genesis, dag) shouldBeF true
        _            <- Validate.blockSender[Task](validBlock, genesis, dag) shouldBeF true
        result       <- Validate.blockSender[Task](invalidBlock, genesis, dag) shouldBeF false
      } yield result
  }

  "Parent validation" should "return true for proper justifications and false otherwise" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      implicit val casperSmartContractsApi = ExecutionEngineService.noOpApi[Task]()
      val validators = Vector(
        generateValidator("Validator 1"),
        generateValidator("Validator 2"),
        generateValidator("Validator 3")
      )
      val bonds = validators.zipWithIndex.map {
        case (v, i) => Bond(v, 2L * i.toLong + 1L)
      }

      def latestMessages(messages: Seq[BlockMessage]): Map[Validator, BlockHash] =
        messages.map(b => b.sender -> b.blockHash).toMap

      def createValidatorBlock[F[_]: Monad: Time: BlockStore: IndexedBlockDagStorage](
          parents: Seq[BlockMessage],
          justifications: Seq[BlockMessage],
          validator: Int
      ): F[BlockMessage] =
        for {
          current <- Time[F].currentMillis
          deploy  <- ProtoUtil.basicProcessedDeploy[F](current.toInt)
          block <- createBlock[F](
                    parents.map(_.blockHash),
                    creator = validators(validator),
                    bonds = bonds,
                    deploys = Seq(deploy),
                    justifications = latestMessages(justifications)
                  )
        } yield block

      for {
        b0 <- createBlock[Task](Seq.empty, bonds = bonds)
        b1 <- createValidatorBlock[Task](Seq(b0), Seq.empty, 0)
        b2 <- createValidatorBlock[Task](Seq(b0), Seq.empty, 1)
        b3 <- createValidatorBlock[Task](Seq(b0), Seq.empty, 2)
        b4 <- createValidatorBlock[Task](Seq(b1), Seq(b1), 0)
        b5 <- createValidatorBlock[Task](Seq(b3, b2, b1), Seq(b1, b2, b3), 1)
        b6 <- createValidatorBlock[Task](Seq(b5, b4), Seq(b1, b4, b5), 0)
        b7 <- createValidatorBlock[Task](Seq(b4), Seq(b1, b4, b5), 1) //not highest score parent
        b8 <- createValidatorBlock[Task](Seq(b1, b2, b3), Seq(b1, b2, b3), 2) //parents wrong order
        b9 <- createValidatorBlock[Task](Seq(b6), Seq.empty, 0) //empty justification
        result <- mkRuntimeManager("casper-util-test")
                   .use { runtimeManager =>
                     for {
                       dag <- blockDagStorage.getRepresentation

                       // Valid
                       _ <- Validate.parents[Task](b0, b0, b0.blockHash, dag)
                       _ <- Validate.parents[Task](b1, b0, b0.blockHash, dag)
                       _ <- Validate.parents[Task](b2, b0, b0.blockHash, dag)
                       _ <- Validate.parents[Task](b3, b0, b0.blockHash, dag)
                       _ <- Validate.parents[Task](b4, b0, b0.blockHash, dag)
                       _ <- Validate.parents[Task](b5, b0, b0.blockHash, dag)
                       _ <- Validate.parents[Task](b6, b0, b0.blockHash, dag)

                       // Not valid
                       _ <- Validate.parents[Task](b7, b0, b0.blockHash, dag).attempt
                       _ <- Validate.parents[Task](b8, b0, b0.blockHash, dag).attempt
                       _ <- Validate.parents[Task](b9, b0, b0.blockHash, dag).attempt

                       _ = log.warns should have size (3)
                       result = log.warns.forall(
                         _.contains("block parents did not match estimate based on justification")
                       ) should be(
                         true
                       )
                     } yield result
                   }
      } yield result
  }

  // Creates a block with an invalid block number and sequence number
  "Block summary validation" should "short circuit after first invalidity" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      for {
        _        <- createChain[Task](2)
        block    <- blockDagStorage.lookupByIdUnsafe(1)
        dag      <- blockDagStorage.getRepresentation
        (sk, pk) = Ed25519.newKeyPair
        signedBlock <- ProtoUtil.signBlock[Task](
                        block.withBlockNumber(17).withSeqNum(1),
                        dag,
                        pk,
                        sk,
                        "ed25519",
                        "rchain"
                      )
        _ <- Validate
              .blockSummary[Task](
                signedBlock,
                BlockMessage.defaultInstance,
                dag,
                "casperlabs",
                BlockMessage.defaultInstance.blockHash
              )
              .attempt shouldBeF Left(InvalidBlockNumber)
        result = log.warns.size should be(1)
      } yield result
  }

  "Justification follow validation" should "return valid for proper justifications and failed otherwise" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val v1     = generateValidator("Validator One")
      val v2     = generateValidator("Validator Two")
      val v1Bond = Bond(v1, 2)
      val v2Bond = Bond(v2, 3)
      val bonds  = Seq(v1Bond, v2Bond)

      for {
        genesis <- createBlock[Task](Seq(), ByteString.EMPTY, bonds)
        b2 <- createBlock[Task](
               Seq(genesis.blockHash),
               v2,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
             )
        b3 <- createBlock[Task](
               Seq(genesis.blockHash),
               v1,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> genesis.blockHash)
             )
        b4 <- createBlock[Task](
               Seq(b2.blockHash),
               v2,
               bonds,
               HashMap(v1 -> genesis.blockHash, v2 -> b2.blockHash)
             )
        b5 <- createBlock[Task](
               Seq(b2.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b3.blockHash, v2 -> b2.blockHash)
             )
        b6 <- createBlock[Task](
               Seq(b4.blockHash),
               v2,
               bonds,
               HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
             )
        b7 <- createBlock[Task](
               Seq(b4.blockHash),
               v1,
               Seq(),
               HashMap(v1 -> b5.blockHash, v2 -> b4.blockHash)
             )
        b8 <- createBlock[Task](
               Seq(b7.blockHash),
               v1,
               bonds,
               HashMap(v1 -> b7.blockHash, v2 -> b4.blockHash)
             )
        _ <- (1 to 6).toList.forallM[Task](
              i =>
                for {
                  block <- blockDagStorage.lookupByIdUnsafe(i)
                  dag   <- blockDagStorage.getRepresentation
                  result <- Validate.justificationFollows[Task](
                             block,
                             genesis,
                             dag
                           )
                } yield true
            ) shouldBeF true
        blockId7 <- blockDagStorage.lookupByIdUnsafe(7)
        dag      <- blockDagStorage.getRepresentation
        _ <- Validate
              .justificationFollows[Task](
                blockId7,
                genesis,
                dag
              )
              .attempt shouldBeF Left(InvalidFollows)
        _      = log.warns.size shouldBe 1
        result = log.warns.forall(_.contains("do not match the bonded validators")) shouldBe true
      } yield result
  }

  "Justification regression validation" should "return valid for proper justifications and justification regression detected otherwise" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val validators = Vector(
        generateValidator("Validator 1"),
        generateValidator("Valdiator 2")
      )
      val bonds = validators.zipWithIndex.map {
        case (v, i) => Bond(v, 2L * i.toLong + 1L)
      }

      def latestMessages(messages: Seq[BlockMessage]): Map[Validator, BlockHash] =
        messages.map(b => b.sender -> b.blockHash).toMap

      def createValidatorBlock[F[_]: Monad: Time: BlockStore: IndexedBlockDagStorage](
          parents: Seq[BlockMessage],
          justifications: Seq[BlockMessage],
          validator: Int
      ): F[BlockMessage] =
        for {
          deploy <- ProtoUtil.basicProcessedDeploy[F](0)
          result <- createBlock[F](
                     parents.map(_.blockHash),
                     creator = validators(validator),
                     bonds = bonds,
                     deploys = Seq(deploy),
                     justifications = latestMessages(justifications)
                   )
        } yield result

      for {
        b0 <- createBlock[Task](Seq.empty, bonds = bonds)
        b1 <- createValidatorBlock[Task](Seq(b0), Seq(b0, b0), 0)
        b2 <- createValidatorBlock[Task](Seq(b1), Seq(b1, b0), 0)
        b3 <- createValidatorBlock[Task](Seq(b0), Seq(b2, b0), 1)
        b4 <- createValidatorBlock[Task](Seq(b3), Seq(b2, b3), 1)
        _ <- (0 to 4).toList.forallM[Task](
              i =>
                for {
                  block <- blockDagStorage.lookupByIdUnsafe(i)
                  dag   <- blockDagStorage.getRepresentation
                  result <- Validate.justificationRegressions[Task](
                             block,
                             b0,
                             dag
                           )
                } yield true
            ) shouldBeF true
        // The justification block for validator 0 should point to b2 or above.
        justificationsWithRegression = Seq(
          Justification(validators(0), b1.blockHash),
          Justification(validators(1), b4.blockHash)
        )
        blockWithJustificationRegression = BlockMessage()
          .withSender(validators(1))
          .withJustifications(justificationsWithRegression)
        dag <- blockDagStorage.getRepresentation
        _ <- Validate
              .justificationRegressions[Task](
                blockWithJustificationRegression,
                b0,
                dag
              )
              .attempt shouldBeF Left(JustificationRegression)
        result = log.warns.size shouldBe 1
      } yield result
  }

  "Bonds cache validation" should "succeed on a valid block and fail on modified bonds" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val (_, validators) = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
      val bonds           = HashSetCasperTest.createBonds(validators)
      val genesis         = HashSetCasperTest.createGenesis(bonds)
      val genesisBonds    = ProtoUtil.bonds(genesis)

      val storageDirectory                 = Files.createTempDirectory(s"hash-set-casper-test-genesis")
      val storageSize: Long                = 1024L * 1024
      implicit val casperSmartContractsApi = ExecutionEngineService.noOpApi[Task]()
      val runtimeManager                   = RuntimeManager[Task](casperSmartContractsApi, bonds)
      implicit val log                     = new LogStub[Task]
      for {
        dag <- blockDagStorage.getRepresentation
        // FIXME: we should insert the TransformEntry into blockStore, now we simply return empty TransformEntry, this is not correct
        _ <- BlockGenerator
              .validateBlockCheckpoint[Task](
                genesis,
                dag,
                (_: BlockMetadata) => Seq.empty[TransformEntry].pure[Task]
              )
        _                 <- Validate.bondsCache[Task](genesis, genesisBonds) shouldBeF Unit
        modifiedBonds     = Seq.empty[Bond]
        modifiedPostState = genesis.getBody.getState.withBonds(modifiedBonds)
        modifiedBody      = genesis.getBody.withState(modifiedPostState)
        modifiedGenesis   = genesis.withBody(modifiedBody)
        result <- Validate.bondsCache[Task](modifiedGenesis, genesisBonds).attempt shouldBeF Left(
                   InvalidBondsCache
                 )
      } yield result
  }

  "Field format validation" should "succeed on a valid block and fail on empty fields" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      implicit val log = new LogStub[Task]()
      val (sk, pk)     = Ed25519.newKeyPair
      val block        = HashSetCasperTest.createGenesis(Map(pk -> 1))
      for {
        dag     <- blockDagStorage.getRepresentation
        genesis <- ProtoUtil.signBlock[Task](block, dag, pk, sk, "ed25519", "casperlabs")
        _       <- Validate.formatOfFields[Task](genesis) shouldBeF true
        _       <- Validate.formatOfFields[Task](genesis.withBlockHash(ByteString.EMPTY)) shouldBeF false
        _       <- Validate.formatOfFields[Task](genesis.clearHeader) shouldBeF false
        _       <- Validate.formatOfFields[Task](genesis.clearBody) shouldBeF false
        _       <- Validate.formatOfFields[Task](genesis.withSig(ByteString.EMPTY)) shouldBeF false
        _       <- Validate.formatOfFields[Task](genesis.withSigAlgorithm("")) shouldBeF false
        _       <- Validate.formatOfFields[Task](genesis.withShardId("")) shouldBeF false
        _       <- Validate.formatOfFields[Task](genesis.withBody(genesis.getBody.clearState)) shouldBeF false
        _ <- Validate.formatOfFields[Task](
              genesis.withHeader(genesis.header.get.withPostStateHash(ByteString.EMPTY))
            ) shouldBeF false
        _ <- Validate.formatOfFields[Task](
              genesis.withHeader(genesis.header.get.withDeploysHash(ByteString.EMPTY))
            ) shouldBeF false
      } yield ()
  }

  "Block hash format validation" should "fail on invalid hash" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val (sk, pk) = Ed25519.newKeyPair
      val block    = HashSetCasperTest.createGenesis(Map(pk -> 1))
      for {
        dag     <- blockDagStorage.getRepresentation
        genesis <- ProtoUtil.signBlock[Task](block, dag, pk, sk, "ed25519", "casperlabs")
        _       <- Validate.blockHash[Task](genesis) shouldBeF Unit
        result <- Validate
                   .blockHash[Task](
                     genesis.withBlockHash(ByteString.copyFromUtf8("123"))
                   )
                   .attempt shouldBeF Left(InvalidBlockHash)
      } yield result
  }

  "Block deploy count validation" should "fail on invalid number of deploys" in withStorage {
    implicit blockStore => implicit blockDagStorage =>
      val (sk, pk) = Ed25519.newKeyPair
      val block    = HashSetCasperTest.createGenesis(Map(pk -> 1))
      for {
        dag     <- blockDagStorage.getRepresentation
        genesis <- ProtoUtil.signBlock[Task](block, dag, pk, sk, "ed25519", "casperlabs")
        _       <- Validate.deployCount[Task](genesis) shouldBeF Unit
        result <- Validate
                   .deployCount[Task](
                     genesis.withHeader(genesis.header.get.withDeployCount(100))
                   )
                   .attempt shouldBeF Left(InvalidDeployCount)
      } yield result
  }

  "Block version validation" should "work" in withStorage { _ => implicit blockDagStorage =>
    val (sk, pk) = Ed25519.newKeyPair
    val block    = HashSetCasperTest.createGenesis(Map(pk -> 1))
    for {
      dag     <- blockDagStorage.getRepresentation
      genesis <- ProtoUtil.signBlock(block, dag, pk, sk, "ed25519", "casperlabs")
      _       <- Validate.version[Task](genesis, -1) shouldBeF false
      result  <- Validate.version[Task](genesis, 1) shouldBeF true
    } yield result
  }
}
