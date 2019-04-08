package io.casperlabs.comm.rp

import scala.concurrent.duration._
import io.casperlabs.comm.discovery.Node

final case class RPConf(
    local: Node,
    bootstrap: Option[Node],
    defaultTimeout: FiniteDuration,
    clearConnections: ClearConnectionsConf
)
final case class ClearConnectionsConf(maxNumOfConnections: Int, numOfConnectionsPinged: Int)
