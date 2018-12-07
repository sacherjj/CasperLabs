package coop.rchain.casper.util.rholang

import coop.rchain.casper.protocol._

case class InternalProcessedDeploy(
    deploy: Deploy,
    cost: Double,
    status: DeployStatus
)

object ProcessedDeployUtil {
  def toInternal(pd: ProcessedDeploy): Option[InternalProcessedDeploy] =
    for {
      d <- pd.deploy
      c = 1.0
      s = if (pd.errored) UnknownFailure else Succeeded
    } yield InternalProcessedDeploy(d, c, s)

  def fromInternal(ipd: InternalProcessedDeploy): ProcessedDeploy = ipd match {
    case InternalProcessedDeploy(deploy, cost, status) =>
      ProcessedDeploy(
        deploy = Some(deploy),
        errored = status.isFailed
      )
  }
}
