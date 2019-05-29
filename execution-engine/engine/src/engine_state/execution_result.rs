use super::error::Error;
use super::execution_effect::ExecutionEffect;

pub enum ExecutionResult {
    /// An error condition that happened during execution
    Failure {
        error: Error,
        effect: ExecutionEffect,
        cost: u64,
    },
    /// Execution was finished successfully
    Success { effect: ExecutionEffect, cost: u64 },
}

impl ExecutionResult {
    /// Constructs [ExecutionResult::Failure] that has 0 cost and no effects.
    /// This is the case for failures that we can't (or don't want to) charge for,
    /// like `PreprocessingError` or `InvalidNonce`.
    pub fn precondition_failure(error: Error) -> ExecutionResult {
        ExecutionResult::Failure {
            error,
            effect: Default::default(),
            cost: 0,
        }
    }
}
