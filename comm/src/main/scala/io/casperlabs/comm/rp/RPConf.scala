package io.casperlabs.comm.rp

import scala.concurrent.duration._
import io.casperlabs.comm.discovery.Node

final case class RPConf(
    local: Node,
    bootstraps: List[Node],
    defaultTimeout: FiniteDuration,
    clearConnections: ClearConnectionsConf
)
final case class ClearConnectionsConf(maxNumOfConnections: Int, numOfConnectionsPinged: Int)
