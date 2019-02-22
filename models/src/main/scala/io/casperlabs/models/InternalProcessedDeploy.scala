package io.casperlabs.models
import io.casperlabs.casper.protocol.Deploy

final case class InternalProcessedDeploy(
    deploy: Deploy,
    //TODO: `cost` should be a Long, not a Double
    cost: Double,
    result: DeployResult
)
