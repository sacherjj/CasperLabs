use contract_ffi::{key::Key, value::Value};
use engine_shared::{
    additive_map::AdditiveMap, gas::Gas, motes::Motes, newtypes::CorrelationId,
    transform::Transform,
};
use engine_storage::global_state::StateReader;

use super::{error, execution_effect::ExecutionEffect, op::Op, CONV_RATE};

#[derive(Debug)]
pub enum ExecutionResult {
    /// An error condition that happened during execution
    Failure {
        error: error::Error,
        effect: ExecutionEffect,
        cost: Gas,
    },
    /// Execution was finished successfully
    Success { effect: ExecutionEffect, cost: Gas },
}

impl ExecutionResult {
    /// Constructs [ExecutionResult::Failure] that has 0 cost and no effects.
    /// This is the case for failures that we can't (or don't want to) charge
    /// for, like `PreprocessingError` or `InvalidNonce`.
    pub fn precondition_failure(error: error::Error) -> ExecutionResult {
        ExecutionResult::Failure {
            error,
            effect: Default::default(),
            cost: Gas::default(),
        }
    }

    pub fn is_success(&self) -> bool {
        match self {
            ExecutionResult::Failure { .. } => false,
            ExecutionResult::Success { .. } => true,
        }
    }

    pub fn is_failure(&self) -> bool {
        match self {
            ExecutionResult::Failure { .. } => true,
            ExecutionResult::Success { .. } => false,
        }
    }

    pub fn cost(&self) -> Gas {
        match self {
            ExecutionResult::Failure { cost, .. } => *cost,
            ExecutionResult::Success { cost, .. } => *cost,
        }
    }

    pub fn effect(&self) -> &ExecutionEffect {
        match self {
            ExecutionResult::Failure { effect, .. } => effect,
            ExecutionResult::Success { effect, .. } => effect,
        }
    }

    pub fn with_cost(self, cost: Gas) -> Self {
        match self {
            ExecutionResult::Failure { error, effect, .. } => ExecutionResult::Failure {
                error,
                effect,
                cost,
            },
            ExecutionResult::Success { effect, .. } => ExecutionResult::Success { effect, cost },
        }
    }

    pub fn with_effect(self, effect: ExecutionEffect) -> Self {
        match self {
            ExecutionResult::Failure { error, cost, .. } => ExecutionResult::Failure {
                error,
                effect,
                cost,
            },
            ExecutionResult::Success { cost, .. } => ExecutionResult::Success { effect, cost },
        }
    }
}

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum ExecutionResultBuilderError {
    MissingPaymentExecutionResult,
    MissingSessionExecutionResult,
    MissingFinalizeExecutionResult,
}

pub struct ExecutionResultBuilder {
    payment_execution_result: Option<ExecutionResult>,
    session_execution_result: Option<ExecutionResult>,
    finalize_execution_result: Option<ExecutionResult>,
}

impl Default for ExecutionResultBuilder {
    fn default() -> Self {
        ExecutionResultBuilder {
            payment_execution_result: None,
            session_execution_result: None,
            finalize_execution_result: None,
        }
    }
}

impl ExecutionResultBuilder {
    pub fn new() -> ExecutionResultBuilder {
        ExecutionResultBuilder::default()
    }

    pub fn set_payment_execution_result(&mut self, payment_result: ExecutionResult) -> &mut Self {
        self.payment_execution_result = Some(payment_result);
        self
    }

    pub fn set_session_execution_result(
        &mut self,
        session_execution_result: ExecutionResult,
    ) -> &mut ExecutionResultBuilder {
        self.session_execution_result = Some(session_execution_result);
        self
    }

    pub fn set_finalize_execution_result(
        &mut self,
        finalize_execution_result: ExecutionResult,
    ) -> &mut ExecutionResultBuilder {
        self.finalize_execution_result = Some(finalize_execution_result);
        self
    }

    pub fn total_cost(&self) -> Gas {
        let payment_cost = self
            .payment_execution_result
            .as_ref()
            .map(ExecutionResult::cost)
            .unwrap_or_default();
        let session_cost = self
            .session_execution_result
            .as_ref()
            .map(ExecutionResult::cost)
            .unwrap_or_default();
        payment_cost + session_cost
    }

    pub fn check_forced_transfer(
        &mut self,
        max_payment_cost: Motes,
        account_main_purse_balance: Motes,
        payment_purse_balance: Motes,
        account_main_purse: Key,
        rewards_purse: Key,
    ) -> Option<ExecutionResult> {
        let payment_result = match self.payment_execution_result.as_ref() {
            Some(result) => result,
            None => return None,
        };
        let payment_result_cost = payment_result.cost();
        let payment_result_is_failure = payment_result.is_failure();

        // payment_code_spec_3_b_ii: if (balance of PoS pay purse) < (gas spent during
        // payment code execution) * conv_rate, no session
        let insufficient_balance_to_continue =
            payment_purse_balance < Motes::from_gas(payment_result_cost, CONV_RATE)?;

        // payment_code_spec_4: insufficient payment
        if !(insufficient_balance_to_continue || payment_result_is_failure) {
            return None;
        }

        let mut ops = AdditiveMap::new();
        let mut transforms = AdditiveMap::new();

        let new_balance = account_main_purse_balance - max_payment_cost;

        let account_main_purse_normalize = account_main_purse.normalize();
        let rewards_purse_normalize = rewards_purse.normalize();

        ops.insert(account_main_purse_normalize, Op::Write);
        transforms.insert(
            account_main_purse_normalize,
            Transform::Write(Value::UInt512(new_balance.value())),
        );

        ops.insert(rewards_purse_normalize, Op::Add);
        transforms.insert(
            rewards_purse_normalize,
            Transform::AddUInt512(max_payment_cost.value()),
        );

        let error = error::Error::InsufficientPaymentError;
        let effect = ExecutionEffect::new(ops, transforms);
        let cost = Gas::from_motes(max_payment_cost, CONV_RATE).unwrap_or_default();

        Some(ExecutionResult::Failure {
            error,
            effect,
            cost,
        })
    }

    pub fn build<R: StateReader<Key, Value>>(
        self,
        reader: &R,
        correlation_id: CorrelationId,
    ) -> Result<ExecutionResult, ExecutionResultBuilderError> {
        let cost = self.total_cost();
        let mut ops = AdditiveMap::new();
        let mut transforms = AdditiveMap::new();

        let mut ret: ExecutionResult = ExecutionResult::Success {
            effect: Default::default(),
            cost,
        };

        match self.payment_execution_result {
            Some(result) => {
                if result.is_failure() {
                    return Ok(result);
                } else {
                    Self::add_effects(&mut ops, &mut transforms, result.effect());
                }
            }
            None => return Err(ExecutionResultBuilderError::MissingPaymentExecutionResult),
        };

        // session_code_spec_3: only include session exec effects if there is no session
        // exec error
        match self.session_execution_result {
            Some(result) => {
                if result.is_failure() {
                    ret = result.with_cost(cost);
                } else {
                    Self::add_effects(&mut ops, &mut transforms, result.effect());
                }
            }
            None => return Err(ExecutionResultBuilderError::MissingSessionExecutionResult),
        };

        match self.finalize_execution_result {
            Some(result) => {
                if result.is_failure() {
                    // payment_code_spec_5_a: FinalizationError should only ever be raised here
                    return Ok(ExecutionResult::precondition_failure(
                        error::Error::FinalizationError,
                    ));
                } else {
                    Self::add_effects(&mut ops, &mut transforms, result.effect());
                }
            }
            None => return Err(ExecutionResultBuilderError::MissingFinalizeExecutionResult),
        }

        // Remove redundant writes to allow more opportunity to commute
        let reduced_effect = Self::reduce_identity_writes(ops, transforms, reader, correlation_id);

        Ok(ret.with_effect(reduced_effect))
    }

    fn add_effects(
        ops: &mut AdditiveMap<Key, Op>,
        transforms: &mut AdditiveMap<Key, Transform>,
        effect: &ExecutionEffect,
    ) {
        for (k, op) in effect.ops.iter() {
            ops.insert_add(*k, op.clone());
        }
        for (k, t) in effect.transforms.iter() {
            transforms.insert_add(*k, t.clone())
        }
    }

    /// In the case we are writing the same value as was there originally,
    /// it is equivalent to having a `Transform::Identity` and `Op::Read`.
    /// This function makes that reduction before returning the `ExecutionEffect`.
    fn reduce_identity_writes<R: StateReader<Key, Value>>(
        mut ops: AdditiveMap<Key, Op>,
        mut transforms: AdditiveMap<Key, Transform>,
        reader: &R,
        correlation_id: CorrelationId,
    ) -> ExecutionEffect {
        let kvs: Vec<(Key, Value)> = transforms
            .keys()
            .filter_map(|k| match transforms.get(k) {
                Some(Transform::Write(_)) => reader
                    .read(correlation_id, k)
                    .ok()
                    .and_then(|maybe_v| maybe_v.map(|v| (*k, v.clone()))),
                _ => None,
            })
            .collect();

        for (k, old_value) in kvs {
            if let Some(Transform::Write(new_value)) = transforms.remove(&k) {
                if new_value == old_value {
                    transforms.insert(k, Transform::Identity);
                    ops.insert(k, Op::Read);
                } else {
                    transforms.insert(k, Transform::Write(new_value));
                }
            }
        }

        ExecutionEffect::new(ops, transforms)
    }
}
