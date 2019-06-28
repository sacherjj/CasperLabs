package io.casperlabs.casper.util.comm

import cats.effect.concurrent.Ref
import cats.syntax.show._
import cats.{Applicative, ApplicativeError}
import com.google.protobuf.ByteString
import io.casperlabs.blockstorage.BlockStore.{BlockHash, DeployHash}
import io.casperlabs.blockstorage.{BlockDagRepresentation, InMemBlockDagStorage, InMemBlockStore}
import io.casperlabs.casper.HashSetCasperTest.{buildGenesis, createBonds}
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.BlockSummary
import io.casperlabs.casper.helper.{
  BlockDagStorageTestFixture,
  HashSetCasperTestNode,
  NoOpsCasperEffect,
  NoOpsLastFinalizedBlockHashContainer
}
import io.casperlabs.casper.protocol.{NoApprovedBlockAvailable, _}
import io.casperlabs.casper.util.TestTime
import io.casperlabs.casper.util.comm.CasperPacketHandler.{
  ApprovedBlockReceivedHandler,
  BootstrapCasperHandler,
  CasperPacketHandlerImpl,
  CasperPacketHandlerInternal,
  GenesisValidatorHandler,
  StandaloneCasperHandler
}
import io.casperlabs.casper.util.comm.CasperPacketHandlerSpec._
import io.casperlabs.casper.Estimator.Validator
import io.casperlabs.catscontrib.ApplicativeError_
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.protocol.routing.Packet
import io.casperlabs.comm.rp.Connect.{Connections, ConnectionsCell}
import io.casperlabs.comm.rp.ProtocolHelper
import io.casperlabs.comm.rp.ProtocolHelper._
import io.casperlabs.comm.{transport, _}
import io.casperlabs.crypto.Keys.PublicKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.metrics.Metrics.MetricsNOP
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.Cell
import io.casperlabs.storage.BlockMsgWithTransform
import monix.catnap.Semaphore
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{Matchers, WordSpec}

import scala.concurrent.duration._
import io.casperlabs.shared.FilesAPI

class CasperPacketHandlerSpec extends WordSpec with Matchers {
  private def setup() = new {
    val scheduler                  = Scheduler.io("test")
    val runtimeDir                 = BlockDagStorageTestFixture.blockStorageDir
    val (genesisSk, genesisPk)     = Ed25519.newKeyPair
    val (validatorSk, validatorPk) = Ed25519.newKeyPair
    val bonds                      = createBonds(Seq(validatorPk))
    val requiredSigs               = 1
    val deployTimestamp            = 1L
    val BlockMsgWithTransform(Some(genesis), transforms) =
      buildGenesis(Seq.empty, bonds, 1L, Long.MaxValue, deployTimestamp)
    val validatorId       = ValidatorIdentity(validatorPk, validatorSk, Ed25519)
    val storageSize: Long = 1024L * 1024

    implicit val casperSmartContractsApi = HashSetCasperTestNode.simpleEEApi[Task](bonds)

    val bap = new BlockApproverProtocol(
      validatorId,
      deployTimestamp,
      bonds,
      Seq.empty,
      BlockApproverProtocol.GenesisConf(
        minimumBond = 1L,
        maximumBond = Long.MaxValue,
        requiredSigs = requiredSigs,
        genesisAccountPublicKeyPath = None,
        initialTokens = 0L,
        mintCodePath = None,
        posCodePath = None
      )
    )
    val local: Node = peerNode("src", 40400)
    val chainId     = "test-chainId"

    implicit val nodeDiscovery = new NodeDiscoveryStub[Task]
    implicit val connectionsCell: ConnectionsCell[Task] =
      Cell.unsafe[Task, Connections](List(local))
    implicit val transportLayer = new TransportLayerStub[Task]
    implicit val rpConf         = createRPConfAsk[Task](local)
    implicit val time           = TestTime.instance
    implicit val log            = new LogStub[Task]
    implicit val filesApi       = FilesAPI.create[Task]
    implicit val errHandler =
      ApplicativeError_.applicativeError(new ApplicativeError[Task, CommError] {
        override def raiseError[A](e: CommError): Task[A] =
          Task.raiseError(new Exception(s"CommError: $e"))
        override def handleErrorWith[A](fa: Task[A])(f: CommError => Task[A]): Task[A] =
          fa.onErrorHandleWith(th => f(UnknownCommError(th.getMessage)))
        override def pure[A](x: A): Task[A]                           = Task.pure(x)
        override def ap[A, B](ff: Task[A => B])(fa: Task[A]): Task[B] = Applicative[Task].ap(ff)(fa)
      })
    implicit val metrics = new MetricsNOP[Task]
    implicit val lab =
      LastApprovedBlock.of[Task].unsafeRunSync(monix.execution.Scheduler.Implicits.global)
    implicit val blockMap =
      Ref.unsafe[Task, Map[BlockHash, (BlockMsgWithTransform, BlockSummary)]](Map.empty)
    implicit val deployHashMap    = Ref.unsafe[Task, Map[DeployHash, Seq[BlockHash]]](Map.empty)
    implicit val approvedBlockRef = Ref.unsafe[Task, Option[ApprovedBlock]](None)
    implicit val lock             = Semaphore[Task](1).unsafeRunSync(monix.execution.Scheduler.Implicits.global)
    implicit val blockStore       = InMemBlockStore.create[Task]
    implicit val blockDagStorage = InMemBlockDagStorage
      .create[Task]
      .unsafeRunSync(monix.execution.Scheduler.Implicits.global)
    implicit val casperRef = MultiParentCasperRef.unsafe[Task](None)
    implicit val safetyOracle = new FinalityDetector[Task] {
      override def normalizedFaultTolerance(
          blockDag: BlockDagRepresentation[Task],
          estimateBlockHash: BlockHash
      ): Task[Float] = Task.pure(1.0f)
    }
  }

  "CasperPacketHandler" when {
    "in GenesisValidator state" should {

      "respond on UnapprovedBlock messages with BlockApproval" in {
        implicit val ctx = Scheduler.global
        val fixture      = setup()
        import fixture._

        implicit val lastFinalizedBlockHashContainer =
          NoOpsLastFinalizedBlockHashContainer.create[Task](genesis.blockHash)
        val ref =
          Ref.unsafe[Task, CasperPacketHandlerInternal[Task]](
            new GenesisValidatorHandler(validatorId, chainId, bap)
          )
        val packetHandler = new CasperPacketHandlerImpl[Task](ref, Some(validatorId))
        val expectedCandidate =
          ApprovedBlockCandidate(Some(LegacyConversions.fromBlock(genesis)), requiredSigs)

        val unapprovedBlock = BlockApproverProtocolTest.createUnapproved(
          requiredSigs,
          LegacyConversions.fromBlock(genesis)
        )
        val unapprovedPacket = BlockApproverProtocolTest.unapprovedToPacket(unapprovedBlock)
        val test = for {
          _             <- packetHandler.handle(local).apply(unapprovedPacket)
          blockApproval = BlockApproverProtocol.getBlockApproval(expectedCandidate, validatorId)
          expectedPacket = ProtocolHelper.packet(
            local,
            transport.BlockApproval,
            blockApproval.toByteString
          )
          _ = {
            log.warns.size shouldBe 1
            log.warns.head should include("Unable to construct blessed terms")
            transportLayer.requests should not be empty
            val lastMessage = transportLayer.requests.last
            assert(lastMessage.peer == local && lastMessage.msg == expectedPacket)
          }
        } yield ()
        test.unsafeRunSync
      }

      "should not respond to any other message" in {
        implicit val ctx = Scheduler.global
        val fixture      = setup()
        import fixture._

        implicit val lastFinalizedBlockHashContainer =
          NoOpsLastFinalizedBlockHashContainer.create[Task](genesis.blockHash)

        val ref =
          Ref.unsafe[Task, CasperPacketHandlerInternal[Task]](
            new GenesisValidatorHandler(validatorId, chainId, bap)
          )
        val packetHandler = new CasperPacketHandlerImpl[Task](ref, Some(validatorId))

        val approvedBlockRequest = ApprovedBlockRequest("test")
        val packet1              = Packet(transport.ApprovedBlockRequest.id, approvedBlockRequest.toByteString)
        val test = for {
          _    <- packetHandler.handle(local)(packet1)
          head = transportLayer.requests.head
          response = packet(
            local,
            transport.NoApprovedBlockAvailable,
            NoApprovedBlockAvailable(approvedBlockRequest.identifier, local.show).toByteString
          )
          _            = assert(head.peer == local && head.msg == response)
          _            = transportLayer.reset()
          blockRequest = BlockRequest("base16Hash", ByteString.copyFromUtf8("base16Hash"))
          packet2      = Packet(transport.BlockRequest.id, blockRequest.toByteString)
          _            <- packetHandler.handle(local)(packet2)
          _            = assert(transportLayer.requests.isEmpty)
        } yield ()
        test.unsafeRunSync
      }
    }

    "in StandaloneCasperHandler state" should {
      "make a transition to ApprovedBlockReceivedHandler state after block has been approved" in {
        import monix.execution.Scheduler.Implicits.global
        val fixture = setup()
        import fixture._

        val requiredSigns = 0
        // interval and duration don't really matter since we don't require and signs from validators
        val interval  = 1.millis
        val duration  = 1.second
        val startTime = System.currentTimeMillis()

        def waitUtilCasperIsDefined: Task[MultiParentCasper[Task]] =
          for {
            casperO <- MultiParentCasperRef[Task].get
            casper <- casperO match {
                       case None         => Task.sleep(3.seconds).flatMap(_ => waitUtilCasperIsDefined)
                       case Some(casper) => Task.pure(casper)
                     }
          } yield casper

        val test = for {
          sigs <- Ref.of[Task, Set[Signature]](Set.empty)
          abp = ApproveBlockProtocol.unsafe[Task](
            LegacyConversions.fromBlock(genesis),
            transforms,
            Set(ByteString.copyFrom(validatorPk)),
            requiredSigns,
            duration,
            interval,
            sigs,
            startTime
          )
          standaloneCasper    = new StandaloneCasperHandler[Task](abp)
          refCasper           <- Ref.of[Task, CasperPacketHandlerInternal[Task]](standaloneCasper)
          casperPacketHandler = new CasperPacketHandlerImpl[Task](refCasper, Some(validatorId))
          c1                  = abp.run().forkAndForget.runToFuture
          implicit0(lastFinalizedBlockHashContainer: LastFinalizedBlockHashContainer[Task]) = NoOpsLastFinalizedBlockHashContainer
            .create[Task](genesis.blockHash)
          c2 = StandaloneCasperHandler
            .approveBlockInterval(
              interval,
              chainId,
              Some(validatorId),
              refCasper
            )
            .forkAndForget
            .runToFuture
          blockApproval = ApproveBlockProtocolTest.approval(
            ApprovedBlockCandidate(Some(LegacyConversions.fromBlock(genesis)), requiredSigns),
            validatorSk,
            validatorPk
          )
          blockApprovalPacket = Packet(transport.BlockApproval.id, blockApproval.toByteString)
          _                   <- casperPacketHandler.handle(local)(blockApprovalPacket)
          //wait until casper is defined, with 1 minute timeout (indicating failure)
          possiblyCasper  <- Task.racePair(Task.sleep(1.minute), waitUtilCasperIsDefined)
          _               = assert(possiblyCasper.isRight)
          blockO          <- blockStore.getBlockMessage(genesis.blockHash)
          _               = assert(blockO.isDefined)
          _               = assert(blockO.contains(genesis))
          handlerInternal <- refCasper.get
          _               = assert(handlerInternal.isInstanceOf[ApprovedBlockReceivedHandler[Task]])
          // assert that we really serve last approved block
          lastApprovedBlock <- LastApprovedBlock[Task].get
          _                 = assert(lastApprovedBlock.isDefined)
          _                 <- casperPacketHandler.handle(local)(approvedBlockRequestPacket)
          head              = transportLayer.requests.head
          _ = assert(
            ApprovedBlock
              .parseFrom(head.msg.message.packet.get.content.toByteArray) == lastApprovedBlock.get.approvedBlock
          )
        } yield ()

        test.unsafeRunSync
      }
    }

    "in  BootstrapCasperHandler state" should {
      "make a transition to ApprovedBlockReceivedHandler once ApprovedBlock has been received" in {
        import monix.execution.Scheduler.Implicits.global
        val fixture = setup()
        import fixture._

        val validators = Set(PublicKey(ByteString.copyFrom(validatorPk)))

        implicit val lastFinalizedBlockHashContainer =
          NoOpsLastFinalizedBlockHashContainer.create[Task](genesis.blockHash)
        // interval and duration don't really matter since we don't require and signs from validators
        val bootstrapCasper =
          new BootstrapCasperHandler[Task](
            chainId,
            Some(validatorId),
            validators
          )

        val approvedBlockCandidate =
          ApprovedBlockCandidate(block = Some(LegacyConversions.fromBlock(genesis)))

        val approvedBlock: ApprovedBlock = ApprovedBlock(
          candidate = Some(approvedBlockCandidate),
          sigs = Seq(
            Signature(
              ByteString.copyFrom(validatorPk),
              "ed25519",
              ByteString.copyFrom(
                Ed25519.sign(Blake2b256.hash(approvedBlockCandidate.toByteArray), validatorSk)
              )
            )
          )
        )

        val approvedPacket = Packet(transport.ApprovedBlock.id, approvedBlock.toByteString)

        val test = for {
          refCasper           <- Ref.of[Task, CasperPacketHandlerInternal[Task]](bootstrapCasper)
          casperPacketHandler = new CasperPacketHandlerImpl[Task](refCasper, Some(validatorId))
          _                   <- casperPacketHandler.handle(local).apply(approvedPacket)
          casperO             <- MultiParentCasperRef[Task].get
          _                   = assert(casperO.isDefined)
          blockO              <- blockStore.getBlockMessage(genesis.blockHash)
          _                   = assert(blockO.isDefined)
          _                   = assert(blockO.contains(genesis))
          handlerInternal     <- refCasper.get
          _                   = assert(handlerInternal.isInstanceOf[ApprovedBlockReceivedHandler[Task]])
          _ = assert(
            transportLayer.requests.head.msg == packet(
              local,
              transport.ForkChoiceTipRequest,
              ByteString.EMPTY
            )
          )
          _ = transportLayer.reset()
          // assert that we really serve last approved block
          lastApprovedBlockO <- LastApprovedBlock[Task].get
          _                  = assert(lastApprovedBlockO.isDefined)
          _                  <- casperPacketHandler.handle(local)(approvedBlockRequestPacket)
          head               = transportLayer.requests.head
          _                  = assert(head.msg.message.packet.get.content == approvedBlock.toByteString)
        } yield ()

        test.unsafeRunSync
      }
    }

    "in ApprovedBlockReceivedHandler state" should {
      import monix.execution.Scheduler.Implicits.global
      val fixture = setup()
      import fixture._

      val (_, validators)                                  = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
      val bonds                                            = HashSetCasperTest.createBonds(validators)
      val BlockMsgWithTransform(Some(genesis), transforms) = HashSetCasperTest.createGenesis(bonds)
      val approvedBlockCandidate =
        ApprovedBlockCandidate(block = Some(LegacyConversions.fromBlock(genesis)))
      val approvedBlock: ApprovedBlock = ApprovedBlock(
        candidate = Some(approvedBlockCandidate),
        sigs = Seq(
          Signature(
            ByteString.copyFrom(validatorPk),
            "ed25519",
            ByteString.copyFrom(
              Ed25519.sign(Blake2b256.hash(approvedBlockCandidate.toByteArray), validatorSk)
            )
          )
        )
      )

      implicit val casper = NoOpsCasperEffect[Task]().unsafeRunSync

      val refCasper = Ref.unsafe[Task, CasperPacketHandlerInternal[Task]](
        new ApprovedBlockReceivedHandler[Task](casper, approvedBlock, Some(validatorId))
      )
      val casperPacketHandler = new CasperPacketHandlerImpl[Task](refCasper, Some(validatorId))

      transportLayer.setResponses(_ => p => Right(p))

      "respond to BlockMessage messages " in {
        val blockMessage = BlockMessage(ByteString.copyFrom("Test BlockMessage", "UTF-8"))
        val packet       = Packet(transport.BlockMessage.id, blockMessage.toByteString)
        val test: Task[Unit] = for {
          _ <- casperPacketHandler.handle(local)(packet)
          _ = assert(casper.store.contains(blockMessage.blockHash))
        } yield ()

        transportLayer.reset()
        test.unsafeRunSync
      }

      "respond to BlockRequest messages" in {
        val blockRequest =
          BlockRequest(Base16.encode(genesis.blockHash.toByteArray), genesis.blockHash)
        val requestPacket = Packet(transport.BlockRequest.id, blockRequest.toByteString)
        val test = for {
          _    <- blockStore.put(genesis.blockHash, genesis, transforms)
          _    <- casperPacketHandler.handle(local)(requestPacket)
          head = transportLayer.requests.head
          block = packet(
            local,
            transport.BlockMessage,
            LegacyConversions.fromBlock(genesis).toByteString
          )
          _ = assert(head.peer == local && head.msg == block)
        } yield ()

        transportLayer.reset()
        test.unsafeRunSync
      }

      "respond to ApprovedBlockRequest messages" in {
        val approvedBlockRequest = ApprovedBlockRequest("test")
        val requestPacket =
          Packet(transport.ApprovedBlockRequest.id, approvedBlockRequest.toByteString)

        val test: Task[Unit] = for {
          _    <- casperPacketHandler.handle(local)(requestPacket)
          head = transportLayer.requests.head
          _    = assert(head.peer == local)
          _ = assert(
            ApprovedBlock
              .parseFrom(head.msg.message.packet.get.content.toByteArray) == approvedBlock
          )
        } yield ()

        transportLayer.reset()
        test.unsafeRunSync
      }

      "respond to ForkChoiceTipRequest messages" in {
        val request = ForkChoiceTipRequest()
        val requestPacket =
          Packet(transport.ForkChoiceTipRequest.id, request.toByteString)

        val test: Task[Unit] = for {
          tip  <- MultiParentCasper.forkChoiceTip[Task]
          _    <- casperPacketHandler.handle(local)(requestPacket)
          head = transportLayer.requests.head
          _    = assert(head.peer == local)
          _ = assert(
            head.msg.message.packet.get == Packet(
              transport.BlockMessage.id,
              LegacyConversions.fromBlock(tip).toByteString
            )
          )
        } yield ()

        transportLayer.reset()
        test.unsafeRunSync
      }
    }
  }

}

object CasperPacketHandlerSpec {
  def approvedBlockRequestPacket: Packet = {
    val approvedBlockReq = ApprovedBlockRequest("test")
    Packet(transport.ApprovedBlockRequest.id, approvedBlockReq.toByteString)
  }

  private def peerNode(name: String, port: Int): Node =
    Node(ByteString.copyFrom(name.getBytes), "host", port, port)
}
