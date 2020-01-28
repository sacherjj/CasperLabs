package io.casperlabs.casper.util.execengine

import io.casperlabs.casper.consensus.Deploy
import io.casperlabs.ipc

sealed trait ProcessedDeployResult {
  val deploy: Deploy
}

sealed trait DeployEffects extends ProcessedDeployResult {
  val effects: ipc.ExecutionEffect
}

// Precondition failures don't have effects or cost.
// They are errors that we can't charge for (like key not found, key not being an public key of the account).
final case class PreconditionFailure(deploy: Deploy, errorMessage: String)
    extends ProcessedDeployResult

// Represents errors during execution of the program.
// These errors do have effects in the form of payment code execution.
final case class ExecutionError(
    deploy: Deploy,
    error: ipc.DeployError,
    effects: ipc.ExecutionEffect,
    cost: Long
) extends DeployEffects

final case class ExecutionSuccessful(deploy: Deploy, effects: ipc.ExecutionEffect, cost: Long)
    extends DeployEffects

object ProcessedDeployResult {
  def apply(deploy: Deploy, result: ipc.DeployResult): ProcessedDeployResult =
    // XXX: EE reports costs in BigInt but we turn them to Long. Unlikely to hit limits.
    result match {
      case ipc.DeployResult(ipc.DeployResult.Value.PreconditionFailure(value)) =>
        PreconditionFailure(deploy, value.message)
      case ipc.DeployResult(ipc.DeployResult.Value.ExecutionResult(exec_result)) =>
        exec_result match {
          case ipc.DeployResult.ExecutionResult(Some(effects), Some(error), cost) =>
            ExecutionError(deploy, error, effects, cost.fold(0L)(_.value.toLong))
          case ipc.DeployResult.ExecutionResult(None, Some(error), cost) =>
            // Execution error without effects.
            // Once we add payment code execution this will never happen as every
            // correct deploy will at least have effects in the form of payment transfer.
            ExecutionError(
              deploy,
              error,
              ipc.ExecutionEffect.defaultInstance,
              cost.fold(0L)(_.value.toLong)
            )
          case ipc.DeployResult.ExecutionResult(Some(effects), None, cost) =>
            ExecutionSuccessful(deploy, effects, cost.fold(0L)(_.value.toLong))
          case ipc.DeployResult.ExecutionResult(None, None, _) => ???
        }
      case ipc.DeployResult(ipc.DeployResult.Value.Empty) => ???
    }

  // All the deploys that do not change the global state in a way that can conflict with others,
  // which can only be a `PreconditionFailure`, has been filtered out when creating block.
  // When we're validating block it shouldn't include those either.
  def split(l: List[ProcessedDeployResult]): (List[PreconditionFailure], List[DeployEffects]) =
    l.foldRight(
      (List.empty[PreconditionFailure], List.empty[DeployEffects])
    ) {
      case (pdr: DeployEffects, (noEffects, effectful)) =>
        (noEffects, pdr :: effectful)
      case (pdr: PreconditionFailure, (noEffects, effectful)) =>
        (pdr :: noEffects, effectful)
    }
}
