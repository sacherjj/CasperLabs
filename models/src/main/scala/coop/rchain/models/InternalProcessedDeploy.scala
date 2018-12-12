package coop.rchain.models
import coop.rchain.casper.protocol.Deploy

final case class InternalProcessedDeploy(
    deploy: Deploy,
    cost: Double,
    result: DeployResult
)
