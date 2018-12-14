package io.casperlabs.casper.util.rholang

import io.casperlabs.casper.protocol._
import io.casperlabs.models.{InternalProcessedDeploy, Succeeded, UnknownFailure}

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
