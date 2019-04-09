package io.casperlabs.models
import io.casperlabs.casper.protocol.DeployData

final case class InternalProcessedDeploy(
    deploy: DeployData,
    cost: Long,
    result: DeployResult
)
