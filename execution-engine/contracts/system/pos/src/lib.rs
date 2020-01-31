#![cfg_attr(not(test), no_std)]

extern crate alloc;

mod contract_mint;
mod contract_queue;
mod contract_runtime;
mod contract_stakes;

use alloc::string::String;

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use proof_of_stake::ProofOfStake;
use types::{
    account::{PublicKey, PurseId},
    ApiError, CLValue, URef, U512,
};

use crate::{
    contract_mint::ContractMint, contract_queue::ContractQueue, contract_runtime::ContractRuntime,
    contract_stakes::ContractStakes,
};

const METHOD_BOND: &str = "bond";
const METHOD_UNBOND: &str = "unbond";
const METHOD_STEP: &str = "step";
const METHOD_GET_PAYMENT_PURSE: &str = "get_payment_purse";
const METHOD_SET_REFUND_PURSE: &str = "set_refund_purse";
const METHOD_GET_REFUND_PURSE: &str = "get_refund_purse";
const METHOD_FINALIZE_PAYMENT: &str = "finalize_payment";

pub struct ProofOfStakeContract;

impl ProofOfStake<ContractMint, ContractQueue, ContractRuntime, ContractStakes>
    for ProofOfStakeContract
{
}

pub fn delegate() {
    let pos_contract = ProofOfStakeContract;

    let method_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    match method_name.as_str() {
        // Type of this method: `fn bond(amount: U512, purse: URef)`
        METHOD_BOND => {
            let validator = runtime::get_caller();
            let amount: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let source_uref: URef = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            pos_contract
                .bond(validator, amount, source_uref)
                .unwrap_or_revert();
        }
        // Type of this method: `fn unbond(amount: Option<U512>)`
        METHOD_UNBOND => {
            let validator = runtime::get_caller();
            let maybe_amount = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            pos_contract
                .unbond(validator, maybe_amount)
                .unwrap_or_revert();
        }
        // Type of this method: `fn step()`
        METHOD_STEP => {
            // This is called by the system in every block.
            pos_contract.step().unwrap_or_revert();
        }
        // Type of this method: `fn get_payment_purse() -> PurseId`
        METHOD_GET_PAYMENT_PURSE => {
            let rights_controlled_purse = pos_contract.get_payment_purse().unwrap_or_revert();
            let return_value = CLValue::from_t(rights_controlled_purse).unwrap_or_revert();
            runtime::ret(return_value);
        }
        // Type of this method: `fn set_refund_purse(purse_id: PurseId)`
        METHOD_SET_REFUND_PURSE => {
            let purse_id: PurseId = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            pos_contract.set_refund_purse(purse_id).unwrap_or_revert();
        }
        // Type of this method: `fn get_refund_purse() -> PurseId`
        METHOD_GET_REFUND_PURSE => {
            // We purposely choose to remove the access rights so that we do not
            // accidentally give rights for a purse to some contract that is not
            // supposed to have it.
            let maybe_purse_uref = pos_contract.get_refund_purse().unwrap_or_revert();
            let return_value = CLValue::from_t(maybe_purse_uref).unwrap_or_revert();
            runtime::ret(return_value);
        }
        // Type of this method: `fn finalize_payment()`
        METHOD_FINALIZE_PAYMENT => {
            let amount_spent: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let account: PublicKey = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            pos_contract
                .finalize_payment(amount_spent, account)
                .unwrap_or_revert();
        }
        _ => {}
    }
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    delegate();
}
