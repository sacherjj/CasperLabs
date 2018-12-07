package coop.rchain.casper.util.rholang

import coop.rchain.casper.util.EventConverter
import coop.rchain.casper.protocol._

// todo(abner) I should know more about what diffenrence bettwen rspace and our architecture
sealed trait RspaceEvent

case class InternalProcessedDeploy(
    deploy: Deploy,
    cost: Double,
    log: Seq[RspaceEvent],
    status: DeployStatus
)

object ProcessedDeployUtil {

  def toInternal(pd: ProcessedDeploy): Option[InternalProcessedDeploy] =
    for {
      d <- pd.deploy
      c = 1.0
      l = pd.log.map(EventConverter.toRspaceEvent)
      s = if (pd.errored) UnknownFailure else Succeeded
    } yield InternalProcessedDeploy(d, c, l, s)

  def fromInternal(ipd: InternalProcessedDeploy): ProcessedDeploy = ipd match {
    case InternalProcessedDeploy(deploy, cost, log, status) =>
      ProcessedDeploy(
        deploy = Some(deploy),
        log = log.map(EventConverter.toCasperEvent),
        errored = status.isFailed
      )
  }
}
