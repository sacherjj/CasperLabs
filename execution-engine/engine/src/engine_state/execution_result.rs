use super::error::Error;
use super::execution_effect::ExecutionEffect;

pub struct ExecutionResult {
    pub result: Result<ExecutionEffect, Error>,
    pub cost: u64,
}

impl ExecutionResult {
    pub fn failure(error: Error, cost: u64) -> ExecutionResult {
        ExecutionResult {
            result: Err(error),
            cost,
        }
    }

    pub fn success(effect: ExecutionEffect, cost: u64) -> ExecutionResult {
        ExecutionResult {
            result: Ok(effect),
            cost,
        }
    }
}
