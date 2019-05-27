use super::error::Error;
use super::execution_effect::ExecutionEffect;

pub enum ExecutionResult {
    /// An error condition that happened during execution
    Failure {
        error: Error,
        effect: ExecutionEffect,
        cost: u64,
    },
    /// Execution was finished successfuly
    Success { effect: ExecutionEffect, cost: u64 },
}
