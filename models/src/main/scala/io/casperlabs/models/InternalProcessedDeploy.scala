package io.casperlabs.models
import io.casperlabs.casper.protocol.Deploy

final case class InternalProcessedDeploy(
    deploy: Deploy,
    cost: Double,
    result: DeployResult
)
