package io.casperlabs.comm.gossiping

import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.GossipError
import io.casperlabs.shared.Log
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import monix.eval.Task
import monix.execution.Scheduler
import monix.tail.Iterant
import org.scalatest._
import org.scalatest.concurrent._
import org.scalacheck.{Arbitrary, Gen}, Arbitrary.arbitrary
import scala.concurrent.duration._

class DownloadManagerSpec
    extends WordSpecLike
    with Matchers
    with Eventually
    with BeforeAndAfterEach
    with ArbitraryConsensus {

  import DownloadManagerSpec._
  import Scheduler.Implicits.global

  override implicit val patienceConfig = PatienceConfig(1.second, 100.millis)

  // Collect log messages. Reset before each test.
  implicit val log = new LogStub[Task]()

  override def beforeEach() =
    log.reset()

  // Create a random Node so I don't have to repeat this in all tests.
  val source = arbitrary[Node].sample.get

  "DownloadManager" when {
    "scheduled to download a section of the DAG" should {
      "download blocks in topoligical order" in (pending)
      "download siblings in parallel" in (pending)
      "eventually download the full DAG" in (pending)
      "not exceed the limit on parallelism" in (pending)
    }

    "scheduled to download a block with missing dependencies" should {
      val block = arbitrary[Block].suchThat(_.getHeader.parentHashes.nonEmpty).sample.get

      "raise an error" in TestFixture(MockBackend(), _ => MockGossipService(Seq(block))) {
        case (manager, _) =>
          manager.scheduleDownload(summaryOf(block), source, false).attempt.map {
            case Left(GossipError.MissingDependencies(_)) =>
            case other =>
              fail(s"Expected scheduling to fail; got $other")
          }
      }

      "accept it if the dependency has already been scheduled" in (pending)
    }

    "scheduled to download a block which already exists" should {
      "skip the download" in (pending)
    }
    "scheduled to download a block which is already downloading" should {
      "skip the download" in (pending)
    }

    "released as a resource" should {
      val block = arbitrary[Block].sample.map(withoutDependencies).get

      "cancel outstanding downloads" in {
        // Return the chunks slowly so we can shut down the manager before it could store the data.
        var countIn  = 0
        var countOut = 0
        val remote =
          MockGossipService(Seq(block), rechunker = _.mapEval { chunk =>
            for {
              _ <- Task.delay(countIn += 1)
              _ <- Task.sleep(750.millis)
              _ <- Task.delay(countOut += 1)
            } yield chunk
          })
        val backend = MockBackend()

        val test = for {
          alloc <- DownloadManagerImpl[Task](
                    maxParallelDownloads = 1,
                    connectToGossip = _ => remote,
                    backend = backend
                  ).allocated
          (manager, release) = alloc
          _                  <- manager.scheduleDownload(summaryOf(block), source, relay = false)
          _                  <- Task.sleep(500.millis)
          _                  <- release
          _                  <- Task.sleep(500.millis)
        } yield {
          backend.summaries should not contain key(block.blockHash)
          countIn should be > countOut
        }

        test.runSyncUnsafe(2.seconds)
      }

      "reject further schedules" in {
        val test = for {
          alloc <- DownloadManagerImpl[Task](
                    maxParallelDownloads = 1,
                    connectToGossip = _ => MockGossipService(),
                    backend = MockBackend()
                  ).allocated
          (manager, release) = alloc
          _                  <- release
          res                <- manager.scheduleDownload(summaryOf(block), source, relay = false).attempt
        } yield {
          res.isLeft shouldBe true
          res.left.get shouldBe a[java.lang.IllegalStateException]
        }

        test.runSyncUnsafe(1.seconds)
      }
    }

    "fails to download a block for any reason" should {
      "carry on downloading other blocks from other nodes" in (pending)
      "try to download the block from a different source" in (pending)
    }

    "downloaded a valid block" should {
      val block   = arbitrary[Block].sample.map(withoutDependencies).get
      val remote  = MockGossipService(Seq(block))
      def backend = MockBackend()

      def check(test: MockBackend => Unit): TestArgs => Task[Unit] = {
        case (manager, backend) =>
          manager.scheduleDownload(summaryOf(block), source, relay = true).map { _ =>
            eventually {
              test(backend)
            }
          }
      }

      "validate the block" in TestFixture(backend, _ => remote) {
        check(_.validations should contain(block))
      }

      "store the block" in TestFixture(backend, _ => remote) {
        check(_.blocks should contain value block)
      }

      "store the block summary" in TestFixture(backend, _ => remote) {
        check(_.summaries should contain value summaryOf(block))
      }

      "gossip to other nodes" in (pending)
    }

    "cannot validate a block" should {
      val block  = arbitrary[Block].sample.map(withoutDependencies).get
      val remote = MockGossipService(Seq(block))
      def backend =
        MockBackend(_ => Task.raiseError(new java.lang.IllegalArgumentException("Nope.")))

      "not store the block" in TestFixture(backend, _ => remote) {
        case (manager, backend) =>
          manager.scheduleDownload(summaryOf(block), source, relay = true) map { _ =>
            eventually {
              backend.validations should contain(block)
              backend.blocks should not contain key(block.blockHash)
              Inspectors.forExactly(1, log.causes) {
                _.getMessage shouldBe "Nope."
              }
            }
          }
      }

      "not download the dependant blocks" in (pending)
    }

    "cannot connect to a node" should {
      val block = arbitrary[Block].sample.map(withoutDependencies).get
      val remote = MockGossipService(
        Seq(block),
        rechunker = _ => Iterant.raiseError(io.grpc.Status.UNAVAILABLE.asRuntimeException())
      )

      "try again if the same block from the same source is scheduled again" in TestFixture(
        remote = _ => remote
      ) {
        case (manager, _) =>
          def check(i: Int) = manager.scheduleDownload(summaryOf(block), source, false) map { _ =>
            eventually {
              log.causes should have size i.toLong
              Inspectors.forAll(log.causes) { ex =>
                ex shouldBe an[io.grpc.StatusRuntimeException]
              }
            }
          }
          List(1, 2).traverse(check(_)).void
      }

      "try again later with exponential backoff" in (pending)
    }

    "receiving chunks" should {
      // For now just going to verify that exceptions are logged; there is no method
      // to get out the current status with a history of what happened and nothing would use it.
      val block = arbitrary[Block].sample.map(withoutDependencies).get

      def check(args: TestArgs, msg: String): Task[Unit] =
        args._1.scheduleDownload(summaryOf(block), source, false) map { _ =>
          eventually {
            Inspectors.forExactly(1, log.causes) { ex =>
              ex shouldBe a[GossipError.InvalidChunks]
              ex.getMessage should include(msg)
            }
          }
        }

      def withRechunking(f: Iterant[Task, Chunk] => Iterant[Task, Chunk]) = { (node: Node) =>
        MockGossipService(Seq(block), rechunker = f)
      }

      def rewriteHeader(
          f: Chunk.Header => Chunk.Header
      ): Iterant[Task, Chunk] => Iterant[Task, Chunk] = { it =>
        it.take(1).map { chunk =>
          chunk.withHeader(f(chunk.getHeader))
        } ++ it.tail
      }

      "check that they start with the header" in TestFixture(
        remote = withRechunking(_.tail)
      )(check(_, "did not start with a header"))

      "check that the total size doesn't exceed promises" in TestFixture(
        remote = withRechunking(rewriteHeader(_.copy(contentLength = 0)))
      )(check(_, "exceeding the promised content length"))

      "check that the expected compression algorithm is used" in TestFixture(
        remote = withRechunking(rewriteHeader(_.copy(compressionAlgorithm = "gzip")))
      )(check(_, "unexpected algorithm"))

      "handle an empty stream" in TestFixture(
        remote = withRechunking(_.take(0))
      )(check(_, "not receive a header"))
    }

    "cannot query the backend" should {
      val block1 = arbitrary[Block].sample.get
      val block2 = arbitrary[Block].sample.map(withoutDependencies).get
      val remote = MockGossipService(Seq(block1, block2))
      val backend = new MockBackend() {
        override def hasBlock(blockHash: ByteString) =
          if (blockHash == block1.blockHash || dependenciesOf(block1).contains(blockHash))
            Task.raiseError(new RuntimeException("Oh no!"))
          else
            Task.pure(false)
      }

      "raise a scheduling error, but keep processing schedules" in TestFixture(backend, _ => remote) {
        case (manager, _) =>
          for {
            attempt1 <- manager.scheduleDownload(summaryOf(block1), source, false).attempt
            attempt2 <- manager.scheduleDownload(summaryOf(block2), source, false).attempt
          } yield {
            attempt1 match {
              case Left(ex: RuntimeException) =>
                ex.getMessage shouldBe "Oh no!"
              case other =>
                fail(s"Expected scheduling to fail; got $other")
            }
            attempt2 shouldBe Right(())
          }
      }
    }
  }
}

object DownloadManagerSpec {

  def summaryOf(block: Block): BlockSummary =
    BlockSummary()
      .withBlockHash(block.blockHash)
      .withHeader(block.getHeader)
      .withSignature(block.getSignature)

  def dependenciesOf(block: Block): Seq[ByteString] =
    block.getHeader.parentHashes ++ block.getHeader.justifications.map(_.latestBlockHash)

  def withoutDependencies(block: Block): Block =
    block.withHeader(block.getHeader.copy(parentHashes = Seq.empty, justifications = Seq.empty))

  type TestArgs = (DownloadManager[Task], MockBackend)

  object TestFixture {
    def apply(
        backend: MockBackend = MockBackend.default,
        remote: Node => Task[GossipService[Task]] = _ => MockGossipService.default,
        maxParallelDownloads: Int = 1
    )(
        test: TestArgs => Task[Unit]
    )(implicit scheduler: Scheduler, log: Log[Task]): Unit = {

      val managerR = DownloadManagerImpl[Task](
        maxParallelDownloads = maxParallelDownloads,
        connectToGossip = remote(_),
        backend = backend
      )

      val runTest = managerR.use { manager =>
        test(manager, backend)
      }

      runTest.runSyncUnsafe(5.seconds)
    }
  }

  class MockBackend(validate: Block => Task[Unit] = _ => Task.unit)
      extends DownloadManagerImpl.Backend[Task] {
    var validations = Vector.empty[Block]
    var blocks      = Map.empty[ByteString, Block]
    var summaries   = Map.empty[ByteString, BlockSummary]

    def hasBlock(blockHash: ByteString): Task[Boolean] =
      Task.now(blocks.contains(blockHash))

    def validateBlock(block: Block): Task[Unit] =
      Task.delay {
        validations = validations :+ block
      } *> validate(block)

    def storeBlock(block: Block): Task[Unit] = Task.delay {
      blocks = blocks + (block.blockHash -> block)
    }

    def storeBlockSummary(summary: BlockSummary): Task[Unit] = Task.delay {
      summaries = summaries + (summary.blockHash -> summary)
    }
  }
  object MockBackend {
    def default                                               = apply()
    def apply(validate: Block => Task[Unit] = _ => Task.unit) = new MockBackend(validate)
  }

  /** Test implementation of the remote GossipService to download the blocks from. */
  object MockGossipService {
    val default = Task.now {
      new GossipServiceServer[Task](
        getBlock = hash => Task.now(None),
        getBlockSummary = hash => ???,
        maxChunkSize = 100 * 1024
      )
    }
    def apply(
        blocks: Seq[Block] = Seq.empty,
        rechunker: Iterant[Task, Chunk] => Iterant[Task, Chunk] = identity
    ) = Task.now {
      val blockMap = blocks.groupBy(_.blockHash).mapValues(_.head)
      new GossipServiceServer[Task](
        getBlock = hash => Task.now(blockMap.get(hash)),
        getBlockSummary = hash => ???,
        maxChunkSize = 100 * 1024
      ) {
        override def getBlockChunked(request: GetBlockChunkedRequest) =
          rechunker(super.getBlockChunked(request))
      }
    }
  }
}
