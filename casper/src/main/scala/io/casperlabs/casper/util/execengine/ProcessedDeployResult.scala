package io.casperlabs.casper.util.execengine

import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.ipc

sealed trait ProcessedDeployResult {
  val deploy: Deploy
}

sealed trait DeployEffects extends ProcessedDeployResult {
  val effects: ipc.ExecutionEffect
}

final case class InvalidNonceDeploy(deploy: Deploy, nonce: Long) extends ProcessedDeployResult

// Precondition failures don't have effects or cost.
// They are either errors (like key not found, key not being an public key of the account)
// that we can't charge for, or failures for which we choose not to charge for (like invalid nonce).
final case class PreconditionFailure(deploy: Deploy, error: ipc.DeployError)
    extends ProcessedDeployResult

// Represents errors during execution of the program.
// These errors do have effects in the form of increasing account's nonce.
final case class ExecutionError(
    deploy: Deploy,
    error: ipc.DeployError,
    effects: ipc.ExecutionEffect,
    cost: Long
) extends DeployEffects

final case class ExecutionSuccessful(deploy: Deploy, effects: ipc.ExecutionEffect, cost: Long)
    extends DeployEffects

object ProcessedDeployResult {
  def apply(deploy: Deploy, res: ipc.DeployResult): ProcessedDeployResult =
    res match {
      case ipc.DeployResult(ipc.DeployResult.Result.InvalidNonce(invalid_nonce)) =>
        InvalidNonceDeploy(deploy, invalid_nonce.nonce)
      case ipc.DeployResult(ipc.DeployResult.Result.ExecutionResult(exec_result)) =>
        exec_result match {
          case ipc.DeployResult.ExecutionResult(Some(effects), Some(error), cost) =>
            ExecutionError(deploy, error, effects, cost)
          case ipc.DeployResult.ExecutionResult(None, Some(error), cost) =>
            // Execution error without effects - commutes  with everything.
            ExecutionError(deploy, error, ipc.ExecutionEffect.defaultInstance, cost)
          case ipc.DeployResult.ExecutionResult(Some(effects), None, cost) =>
            ExecutionSuccessful(deploy, effects, cost)
        }
    }
}
