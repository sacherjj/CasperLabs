package io.casperlabs.comm.transport

import java.io.FileNotFoundException
import java.nio.file._
import java.util.UUID

import com.google.protobuf.ByteString
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.comm._
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.shared.Log
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.scalatest._

import scala.util.Random

class StreamHandlerSpec extends FunSpec with Matchers with BeforeAndAfterEach {

  implicit val log = new Log.NOPLog[Task]()

  var tempFolder: Path = null

  override def beforeEach(): Unit =
    tempFolder = Files.createTempDirectory("casperlabs")

  override def afterEach(): Unit =
    tempFolder.toFile.delete()

  describe("StreamHandler.handleStream result") {
    it("should contain file path which parent is temp folder") {
      // given
      val stream = createStream()
      // when
      val msg: StreamMessage = handleStream(stream)
      // then
      msg.path.getParent shouldBe tempFolder
    }

    it("should contain sender and message type information") {
      // given
      val stream = createStream(
        typeId = BlockMessage.id
      )
      // when
      val msg: StreamMessage = handleStream(stream)
      // then
      msg.sender shouldBe peerNode("sender")
      msg.typeId shouldBe BlockMessage.id
    }

    it("should contain content length of the stored file") {
      // given
      val messageSize   = 10 * 1024 // 10 kb
      val contentLength = messageSize * 3 + (messageSize / 2)
      val stream = createStream(
        messageSize = messageSize,
        contentLength = contentLength
      )
      // when
      val msg: StreamMessage = handleStream(stream)
      // then
      msg.contentLength shouldBe contentLength
    }

    it(
      "should create a file in non-existing folder if there are permissions to create that folder or files in it"
    ) {
      // given
      val stream = createStream()
      val nonExistingWithPersmission =
        FileSystems.getDefault.getPath("~/.casperlabstest/" + UUID.randomUUID.toString + "/")
      // when
      val msg: StreamMessage = handleStream(stream, folder = nonExistingWithPersmission)
      // then
      msg.path.getParent shouldBe nonExistingWithPersmission
    }
  }

  private def handleStream(stream: Observable[Chunk], folder: Path = tempFolder): StreamMessage =
    StreamHandler.handleStream(folder, stream).unsafeRunSync.right.get

  private def handleStreamErr(stream: Observable[Chunk], folder: Path): Throwable =
    StreamHandler.handleStream(folder, stream).unsafeRunSync.left.get

  private def createStream(
      messageSize: Int = 10 * 1024,
      contentLength: Int = 30 * 1024,
      sender: String = "sender",
      typeId: String = BlockMessage.id
  ): Observable[Chunk] = {

    val content = Array.fill(contentLength)((Random.nextInt(256) - 128).toByte)
    val packet  = Packet(BlockMessage.id, ByteString.copyFrom(content))
    val sender  = peerNode("sender")
    val blob    = Blob(sender, packet)
    Observable.fromIterator(Task(Chunker.chunkIt(blob, messageSize)))
  }

  private def peerNode(name: String): PeerNode =
    PeerNode(NodeIdentifier(name.getBytes), Endpoint("", 80, 80))

}
