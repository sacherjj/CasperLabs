use super::error::Error;
use super::execution_effect::ExecutionEffect;
use engine_state::{error, CONV_RATE};
use std::collections::HashMap;

#[derive(Debug)]
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

pub struct ExecutionResultBuilder {
    payment_execution_result: Option<ExecutionResult>,
    pub is_payment_valid: bool,
    pub payment_cost: u64,
    insufficient_payment_execution_result: Option<ExecutionResult>,
    pub is_insufficient_payment_valid: bool,
    session_execution_result: Option<ExecutionResult>,
    pub is_session_valid: bool,
    pub session_cost: u64,
    finalize_execution_result: Option<ExecutionResult>,
    pub is_finalize_valid: bool,
}

impl Default for ExecutionResultBuilder {
    fn default() -> Self {
        ExecutionResultBuilder {
            payment_execution_result: None,
            is_payment_valid: false,
            payment_cost: 0,
            insufficient_payment_execution_result: None,
            is_insufficient_payment_valid: false,
            session_execution_result: None,
            session_cost: 0,
            is_session_valid: false,
            finalize_execution_result: None,
            is_finalize_valid: false,
        }
    }
}

impl ExecutionResultBuilder {
    pub fn new() -> ExecutionResultBuilder {
        ExecutionResultBuilder::default()
    }

    pub fn set_payment_execution_result(
        &mut self,
        payment_execution_result: ExecutionResult,
    ) -> &mut ExecutionResultBuilder {
        let (cost, success) = match payment_execution_result {
            ExecutionResult::Success { cost, .. } => (cost, true),
            ExecutionResult::Failure { cost, .. } => (cost, false),
        };
        self.is_payment_valid = success;
        self.payment_cost = cost;
        self.payment_execution_result = Some(payment_execution_result);
        self
    }

    pub fn set_insufficient_payment_execution_result(
        &mut self,
        insufficient_payment_execution_result: ExecutionResult,
    ) -> &mut ExecutionResultBuilder {
        if let ExecutionResult::Success { .. } = insufficient_payment_execution_result {
            self.is_insufficient_payment_valid = true;
        }
        self.insufficient_payment_execution_result = Some(insufficient_payment_execution_result);
        self
    }

    pub fn set_session_execution_result(
        &mut self,
        session_execution_result: ExecutionResult,
    ) -> &mut ExecutionResultBuilder {
        let (cost, success) = match session_execution_result {
            ExecutionResult::Success { cost, .. } => (cost, true),
            ExecutionResult::Failure { cost, .. } => (cost, false),
        };
        self.is_session_valid = success;
        self.session_cost = cost;
        self.session_execution_result = Some(session_execution_result);
        self
    }

    pub fn set_finalize_execution_result(
        &mut self,
        finalize_execution_result: ExecutionResult,
    ) -> &mut ExecutionResultBuilder {
        if let ExecutionResult::Success { .. } = finalize_execution_result {
            self.is_finalize_valid = true;
        }
        self.finalize_execution_result = Some(finalize_execution_result);
        self
    }

    pub fn total_cost(&self) -> u64 {
        //((gas spent during payment code execution) + (gas spent during session code execution)) * conv_rate
        (self.payment_cost + self.session_cost) * CONV_RATE
    }

    pub fn build(self) -> ExecutionResult {
        if self.is_insufficient_payment_valid {
            let (insufficient_payment_effect, insufficient_payment_cost) =
                match self.insufficient_payment_execution_result.unwrap() {
                    ExecutionResult::Success { effect, cost } => (effect, cost),
                    ExecutionResult::Failure { effect, cost, .. } => (effect, cost),
                };

            return ExecutionResult::Failure {
                effect: insufficient_payment_effect.clone(),
                cost: insufficient_payment_cost,
                error: error::Error::InsufficientPaymentError,
            };
        }

        if !self.is_payment_valid {
            return ExecutionResult::precondition_failure(error::Error::InsufficientPaymentError);
        }

        // payment_code_spec_5_a: FinalizationError should only ever be raised here
        if !self.is_finalize_valid {
            return ExecutionResult::precondition_failure(error::Error::FinalizationError);
        }

        let total_cost = self.total_cost();

        let payment_result_effect = match self.payment_execution_result {
            Some(payment_result) => match payment_result {
                ExecutionResult::Success { effect, .. } => effect,
                ExecutionResult::Failure { effect, .. } => effect,
            },
            None => ExecutionEffect::new(HashMap::new(), HashMap::new()),
        };

        let mut total_ops = HashMap::new();
        let mut total_transforms = HashMap::new();

        total_ops.extend(
            payment_result_effect
                .ops
                .iter()
                .map(|(k, v)| (*k, v.clone())),
        );

        total_transforms.extend(
            payment_result_effect
                .transforms
                .iter()
                .map(|(k, v)| (*k, v.clone())),
        );

        let (session_result_effect, session_result_maybe_error) =
            match self.session_execution_result {
                Some(session_result) => match session_result {
                    ExecutionResult::Success { effect, .. } => (effect, None),
                    ExecutionResult::Failure { effect, error, .. } => (effect, Some(error)),
                },
                None => (
                    ExecutionEffect::new(HashMap::new(), HashMap::new()),
                    Some(Error::DeployError),
                ),
            };

        // session_code_spec_3: only include session exec effects if there is no session exec error
        if session_result_maybe_error.is_none() {
            total_ops.extend(
                session_result_effect
                    .ops
                    .iter()
                    .map(|(k, v)| (*k, v.clone())),
            );
            total_transforms.extend(
                session_result_effect
                    .transforms
                    .iter()
                    .map(|(k, v)| (*k, v.clone())),
            );
        }

        let finalize_result = self.finalize_execution_result.unwrap();
        let finalize_result_effect = match finalize_result {
            ExecutionResult::Success { ref effect, .. } => effect,
            ExecutionResult::Failure { ref effect, .. } => effect,
        };

        total_ops.extend(
            finalize_result_effect
                .ops
                .iter()
                .map(|(k, v)| (*k, v.clone())),
        );

        total_transforms.extend(
            finalize_result_effect
                .transforms
                .iter()
                .map(|(k, v)| (*k, v.clone())),
        );

        let total_effect: ExecutionEffect = ExecutionEffect::new(total_ops, total_transforms);

        if session_result_maybe_error.is_some() {
            return ExecutionResult::Failure {
                error: session_result_maybe_error.unwrap(),
                effect: total_effect,
                cost: 0,
            };
        }

        ExecutionResult::Success {
            effect: total_effect,
            cost: total_cost,
        }
    }
}
