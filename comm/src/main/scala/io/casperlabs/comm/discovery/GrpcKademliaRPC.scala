package io.casperlabs.comm.discovery

import cats.effect._
import cats.implicits._
import cats.temp.par._
import com.google.protobuf.ByteString
import io.casperlabs.catscontrib.TaskContrib.ConcurrentOps
import io.casperlabs.catscontrib.ski._
import io.casperlabs.comm.CachedConnections.ConnectionsCache
import io.casperlabs.comm._
import io.casperlabs.comm.discovery.KademliaGrpcMonix.KademliaRPCServiceStub
import io.casperlabs.metrics.Metrics
import io.casperlabs.metrics.implicits._
import io.casperlabs.shared.{Log, LogSource}
import io.grpc._
import io.grpc.netty._
import monix.eval._
import monix.execution._

import scala.concurrent.duration._

class GrpcKademliaRPC[F[_]: Concurrent: TaskLift: Timer: TaskLike: Log: PeerNodeAsk: Metrics: Par](
    port: Int,
    timeout: FiniteDuration
)(
    implicit
    scheduler: Scheduler,
    connectionsCache: ConnectionsCache[F, KademliaConnTag]
) extends KademliaRPC[F] {

  private implicit val logSource: LogSource = LogSource(this.getClass)
  private implicit val metricsSource: Metrics.Source =
    Metrics.Source(CommMetricsSource, "discovery.kademlia.grpc")

  private val cell = connectionsCache(clientChannel)

  def ping(peer: PeerNode): F[Boolean] =
    for {
      _     <- Metrics[F].incrementCounter("ping")
      local <- PeerNodeAsk[F].ask
      ping  = Ping().withSender(node(local))
      pongErr <- withClient(peer)(
                  _.sendPing(ping)
                    .to[F]
                    .timer("ping-time")
                    .nonCancelingTimeout(timeout)
                ).attempt
    } yield pongErr.fold(kp(false), kp(true))

  def lookup(id: NodeIdentifier, peer: PeerNode): F[Option[Seq[PeerNode]]] =
    for {
      _      <- Metrics[F].incrementCounter("protocol-lookup-send")
      local  <- PeerNodeAsk[F].ask
      lookup = Lookup().withId(ByteString.copyFrom(id.key.toArray)).withSender(node(local))
      responseErr <- withClient(peer)(
                      _.sendLookup(lookup)
                        .to[F]
                        .timer("lookup-time")
                        .nonCancelingTimeout(timeout)
                    ).attempt
    } yield responseErr.toOption.map(_.nodes.map(toPeerNode))

  private def disconnect(peer: PeerNode): F[Unit] =
    cell.modify { s =>
      s.connections.get(peer).fold(s.pure[F]) { connection =>
        for {
          _ <- Log[F].info(s"Disconnecting from peer ${peer.toAddress}")
          _ <- Sync[F].delay(connection.shutdownNow()).attempt
        } yield s.copy(connections = s.connections - peer)
      }
    }

  private def withClient[A](peer: PeerNode, enforce: Boolean = false)(
      f: KademliaRPCServiceStub => F[A]
  ): F[A] =
    for {
      channel <- cell.connection(peer, enforce)
      stub    <- Sync[F].delay(KademliaGrpcMonix.stub(channel))
      result  <- f(stub).onError { case _ => disconnect(peer) }
    } yield result

  def receive(
      pingHandler: PeerNode => F[Unit],
      lookupHandler: (PeerNode, NodeIdentifier) => F[Seq[PeerNode]]
  ): F[Unit] =
    cell.modify { s =>
      Sync[F].delay {
        val server = NettyServerBuilder
          .forPort(port)
          .executor(scheduler)
          .addService(
            KademliaGrpcMonix
              .bindService(new SimpleKademliaRPCService(pingHandler, lookupHandler), scheduler)
          )
          .build
          .start

        val c: Cancelable = () => server.shutdown().awaitTermination()
        s.copy(server = Some(c))
      }
    }

  def shutdown(): F[Unit] = {
    def shutdownServer: F[Unit] = cell.modify { s =>
      for {
        _ <- Log[F].info("Shutting down Kademlia RPC server")
        _ <- s.server.fold(().pure[F])(server => Sync[F].delay(server.cancel()))
      } yield s.copy(server = None, shutdown = true)
    }

    def disconnectFromPeers: F[Unit] =
      for {
        peers <- cell.read.map(_.connections.keys.toList)
        _     <- Log[F].info("Disconnecting from all peers")
        _     <- peers.parTraverse(disconnect)
      } yield ()

    cell.read.flatMap { s =>
      if (s.shutdown) ().pure[F]
      else shutdownServer *> disconnectFromPeers
    }
  }

  private def clientChannel(peer: PeerNode): F[ManagedChannel] =
    for {
      _ <- Log[F].info(s"Creating new channel to peer ${peer.toAddress}")
      c <- Sync[F].delay {
            NettyChannelBuilder
              .forAddress(peer.endpoint.host, peer.endpoint.udpPort)
              .executor(scheduler)
              .usePlaintext()
              .build()
          }
    } yield c

  private def node(n: PeerNode): Node =
    Node()
      .withId(ByteString.copyFrom(n.key.toArray))
      .withHost(n.endpoint.host)
      .withDiscoveryPort(n.endpoint.udpPort)
      .withProtocolPort(n.endpoint.tcpPort)

  private def toPeerNode(n: Node): PeerNode =
    PeerNode(NodeIdentifier(n.id.toByteArray), Endpoint(n.host, n.protocolPort, n.discoveryPort))

  class SimpleKademliaRPCService(
      pingHandler: PeerNode => F[Unit],
      lookupHandler: (PeerNode, NodeIdentifier) => F[Seq[PeerNode]]
  ) extends KademliaGrpcMonix.KademliaRPCService {

    def sendLookup(lookup: Lookup): Task[LookupResponse] = {
      val id               = NodeIdentifier(lookup.id.toByteArray)
      val sender: PeerNode = toPeerNode(lookup.sender.get)
      TaskLike[F].toTask(
        lookupHandler(sender, id)
          .map(peers => LookupResponse().withNodes(peers.map(node)))
      )
    }

    def sendPing(ping: Ping): Task[Pong] = {
      val sender: PeerNode = toPeerNode(ping.sender.get)
      TaskLike[F].toTask(pingHandler(sender).as(Pong()))
    }
  }
}
