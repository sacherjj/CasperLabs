package io.casperlabs.node.diagnostics

import cats.effect._
import cats.implicits._
import com.google.protobuf.empty.Empty
import io.casperlabs.comm.discovery.NodeDiscovery
import io.casperlabs.node.api.diagnostics.{DiagnosticsGrpcMonix, Peers}
import io.casperlabs.shared.Log
import monix.eval.{Task, TaskLike}

object GrpcDiagnosticsService {
  def apply[F[_]: Concurrent: TaskLike: Log: NodeDiscovery](): F[DiagnosticsGrpcMonix.Diagnostics] =
    Sync[F].delay {
      new DiagnosticsGrpcMonix.Diagnostics {
        override def listPeers(request: Empty): Task[Peers] =
          TaskLike[F].apply {
            NodeDiscovery[F].recentlyAlivePeersAscendingDistance
              .map(nodes => Peers().withPeers(nodes))
          }
      }
    }
}
