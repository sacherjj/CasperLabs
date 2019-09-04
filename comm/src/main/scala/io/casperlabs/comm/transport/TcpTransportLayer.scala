package io.casperlabs.comm.transport

import java.io.ByteArrayInputStream
import java.nio.file._
import java.util.concurrent.TimeoutException

import cats.implicits._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CachedConnections.ConnectionsCache
import io.casperlabs.comm.CommError._
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.Node
import io.casperlabs.comm.discovery.NodeUtils._
import io.casperlabs.comm.protocol.routing.RoutingGrpcMonix.TransportLayerStub
import io.casperlabs.comm.protocol.routing._
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.shared._
import io.grpc._
import io.grpc.netty._
import io.netty.handler.ssl._
import monix.eval._
import monix.execution._
import monix.reactive._

import scala.concurrent.duration._
import scala.util._
import scala.util.control.NonFatal

class TcpTransportLayer(
    port: Int,
    cert: String,
    key: String,
    maxMessageSize: Int,
    chunkSize: Int,
    tempFolder: Path,
    clientQueueSize: Int
)(
    implicit scheduler: Scheduler,
    log: Log[Task],
    metrics: Metrics[Task],
    connectionsCache: ConnectionsCache[Task, TcpConnTag]
) extends TransportLayer[Task] {

  private val DefaultSendTimeout = 5.seconds
  private val cell               = connectionsCache(clientChannel)

  private implicit val logSource: LogSource = LogSource(this.getClass)
  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "rp.transport")

  private def certInputStream = new ByteArrayInputStream(cert.getBytes())
  private def keyInputStream  = new ByteArrayInputStream(key.getBytes())

  private val streamObservable = new StreamObservable(clientQueueSize, tempFolder)

  private lazy val serverSslContext: SslContext =
    try {
      GrpcSslContexts
        .configure(SslContextBuilder.forServer(certInputStream, keyInputStream))
        .trustManager(HostnameTrustManagerFactory.Instance)
        .clientAuth(ClientAuth.REQUIRE)
        .build()
    } catch {
      case NonFatal(e) =>
        e.printStackTrace()
        throw e
    }

  private lazy val clientSslContext: SslContext =
    try {
      val builder = GrpcSslContexts.forClient
      builder.trustManager(HostnameTrustManagerFactory.Instance)
      builder.keyManager(certInputStream, keyInputStream)
      builder.build
    } catch {
      case NonFatal(e) =>
        println(e.getMessage)
        throw e
    }

  private def clientChannel(peer: Node): Task[ManagedChannel] =
    for {
      _ <- log.debug(s"Creating new channel to peer ${peer.show}")
      c <- Task.delay {
            NettyChannelBuilder
              .forAddress(peer.host, peer.protocolPort)
              .executor(scheduler)
              .maxInboundMessageSize(maxMessageSize)
              .negotiationType(NegotiationType.TLS)
              .sslContext(clientSslContext)
              .intercept(new SslSessionClientInterceptor())
              .overrideAuthority(Base16.encode(peer.id.toByteArray))
              .build()
          }
    } yield c

  def disconnect(peer: Node): Task[Unit] =
    cell.modify { s =>
      for {
        _ <- s.connections.get(peer) match {
              case Some(c) =>
                log
                  .debug(s"Disconnecting from peer ${peer.show}")
                  .map(kp(Try(c.shutdown())))
                  .void
              case _ => Task.unit // ignore if connection does not exists already
            }
      } yield s.copy(connections = s.connections - peer)
    }

  private def withClient[A](peer: Node, enforce: Boolean)(
      f: TransportLayerStub => Task[A]
  ): Task[A] =
    for {
      channel <- cell.connection(peer, enforce)
      stub    <- Task.delay(RoutingGrpcMonix.stub(channel))
      result <- f(stub).doOnFinish {
                 case Some(_) => disconnect(peer)
                 case _       => Task.unit
               }
      _ <- Task.unit.asyncBoundary // return control to caller thread
    } yield result

  private def transport(peer: Node, enforce: Boolean)(
      f: TransportLayerStub => Task[TLResponse]
  ): Task[CommErr[Option[Protocol]]] =
    withClient(peer, enforce)(f).attempt.map(processResponse(peer, _))

  def stream(peers: Seq[Node], blob: Blob): Task[Unit] =
    streamObservable.stream(peers.toList, blob) *> log.info(s"stream to $peers blob")

  private object PeerUnavailable {
    def unapply(e: Throwable): Boolean =
      e.isInstanceOf[StatusRuntimeException] &&
        e.asInstanceOf[StatusRuntimeException].getStatus.getCode == Status.Code.UNAVAILABLE
  }

  private object PeerTimeout {
    def unapply(e: Throwable): Boolean = e.isInstanceOf[TimeoutException]
  }

  private def processResponse(
      peer: Node,
      response: Either[Throwable, TLResponse]
  ): CommErr[Option[Protocol]] =
    response
      .leftMap {
        case PeerTimeout()     => CommError.timeout
        case PeerUnavailable() => peerUnavailable(peer)
        case e                 => protocolException(e)
      }
      .flatMap(
        tlr =>
          tlr.payload match {
            case p if p.isProtocol   => Right(Some(tlr.getProtocol))
            case p if p.isNoResponse => Right(None)
            case TLResponse.Payload.InternalServerError(ise) =>
              Left(internalCommunicationError("Got response: " + ise.error.toStringUtf8))
          }
      )

  def roundTrip(peer: Node, msg: Protocol, timeout: FiniteDuration): Task[CommErr[Protocol]] =
    for {
      _ <- metrics.incrementCounter("round-trip")
      result <- transport(peer, enforce = false)(
                 _.ask(TLRequest(msg.some))
                   .timer("round-trip-time")
                   .nonCancelingTimeout(timeout)
               ).map(_.flatMap {
                 case Some(p) => Right(p)
                 case _ =>
                   Left(internalCommunicationError("Was expecting message, nothing arrived"))
               })
    } yield result

  private def innerSend(
      peer: Node,
      msg: Protocol,
      enforce: Boolean = false,
      timeout: FiniteDuration = DefaultSendTimeout
  ): Task[CommErr[Unit]] =
    for {
      _ <- metrics.incrementCounter("send")
      result <- transport(peer, enforce)(
                 _.ask(TLRequest(msg.some))
                   .timer("send-time")
                   .nonCancelingTimeout(timeout)
               ).map(_.flatMap {
                 case Some(p) =>
                   Left(internalCommunicationError(s"Was expecting no message. Response: $p"))
                 case _ => Right(())
               })
    } yield result

  private def innerBroadcast(
      peers: Seq[Node],
      msg: Protocol,
      enforce: Boolean = false,
      timeOut: FiniteDuration = DefaultSendTimeout
  ): Task[Seq[CommErr[Unit]]] =
    Task.gatherUnordered(peers.map(innerSend(_, msg, enforce, timeOut)))

  def send(peer: Node, msg: Protocol): Task[CommErr[Unit]] =
    innerSend(peer, msg)

  def broadcast(peers: Seq[Node], msg: Protocol): Task[Seq[CommErr[Unit]]] =
    innerBroadcast(peers, msg)

  private def streamToPeer(peer: Node, path: Path, sender: Node): Task[Unit] = {

    def delay[A](a: => Task[A]): Task[A] =
      Task.defer(a).delayExecution(1.second)

    def handle(retryCount: Int): Task[Unit] =
      if (retryCount > 0) {
        PacketOps.restore[Task](path) >>= {
          case Left(error) => log.error(s"Error while streaming packet, error: $error")
          case Right(packet) =>
            withClient(peer, enforce = false) { stub =>
              val blob = Blob(sender, packet)
              stub.stream(Observable.fromIterator(Task(Chunker.chunkIt(blob, chunkSize))))
            }.attempt
              .flatMap {
                case Left(error) =>
                  log.error(s"Error while streaming packet, error: $error") *> delay(
                    handle(retryCount - 1)
                  )
                case Right(_) => log.info(s"Streamed packet $path to $peer")
              }
        }
      } else {
        log.debug(s"Giving up on streaming packet $path to $peer")
      }

    handle(3)
  }

  private def initQueue(
      maybeQueue: Option[Cancelable]
  )(create: Task[Cancelable]): Task[Cancelable] =
    maybeQueue.fold(create) {
      kp(
        Task.raiseError[Cancelable](
          new RuntimeException("TransportLayer server is already started")
        )
      )
    }

  def receive(
      dispatch: Protocol => Task[CommunicationResponse],
      handleStreamed: Blob => Task[Unit]
  ): Task[Unit] = {

    val dispatchInternal: ServerMessage => Task[Unit] = {
      // TODO: consider logging on failure (Left)
      case Tell(protocol) => dispatch(protocol).attemptAndLog.void
      case Ask(protocol, handle) if !handle.complete =>
        dispatch(protocol).attempt.map {
          case Left(e)         => handle.failWith(e)
          case Right(response) => handle.reply(response)
        }.void
      case msg: StreamMessage =>
        StreamHandler.restore(msg) >>= {
          case Left(ex) =>
            Log[Task].error("Could not restore data from file while handling stream", ex)
          case Right(blob) => handleStreamed(blob)
        }
      case _ => Task.unit // sender timeout
    }

    cell.modify { s =>
      val parallelism = Math.max(Runtime.getRuntime.availableProcessors(), 2)
      val queueScheduler =
        Scheduler.fixedPool(
          "tl-dispatcher",
          parallelism,
          reporter = new UncaughtExceptionHandler(1.minute)
        )
      for {
        server <- initQueue(s.server) {
                   Task.delay {
                     new TcpServerObservable(
                       port,
                       serverSslContext,
                       maxMessageSize,
                       tempFolder = tempFolder
                     ).mapParallelUnordered(parallelism)(dispatchInternal)
                       .subscribe()(queueScheduler)
                   }

                 }
        clientQueue <- initQueue(s.clientQueue) {
                        import io.casperlabs.shared.PathOps._
                        Task.delay {
                          streamObservable
                            .flatMap { s =>
                              Observable
                                .fromIterable(s.peers)
                                .mapParallelUnordered(parallelism)(
                                  streamToPeer(_, s.path, s.sender)
                                )
                                .guarantee(s.path.deleteSingleFile[Task]())
                            }
                            .subscribe()(queueScheduler)
                        }
                      }
      } yield s.copy(server = Some(server), clientQueue = Some(clientQueue))
    }

  }

  def shutdown(msg: Protocol): Task[Unit] = {
    def shutdownServer: Task[Unit] = cell.modify { s =>
      for {
        _ <- log.info("Shutting down transport layer server")
        _ <- s.server.fold(Task.unit)(server => Task.delay(server.cancel()))
        _ <- s.clientQueue.fold(Task.unit)(server => Task.delay(server.cancel()))
      } yield s.copy(server = None, shutdown = true)
    }

    def sendShutdownMessages: Task[Unit] =
      for {
        peers <- cell.read.map(_.connections.keys.toSeq)
        _     <- log.info("Sending shutdown message to all peers")
        _     <- innerBroadcast(peers, msg, enforce = true, timeOut = 500.milliseconds)
        _     <- log.info("Disconnecting from all peers")
        _     <- Task.gatherUnordered(peers.map(disconnect))
      } yield ()

    cell.read.flatMap { s =>
      if (s.shutdown) Task.unit
      else shutdownServer *> sendShutdownMessages
    }
  }
}
