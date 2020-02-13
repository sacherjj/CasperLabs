#![no_std]

extern crate alloc;

mod contract_runtime;
mod contract_storage;

use alloc::string::String;

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use mint::Mint;
use types::{system_contract_errors::mint::Error, ApiError, CLValue, URef, U512};

use crate::{contract_runtime::ContractRuntime, contract_storage::ContractStorage};

const METHOD_MINT: &str = "mint";
const METHOD_CREATE: &str = "create";
const METHOD_BALANCE: &str = "balance";
const METHOD_TRANSFER: &str = "transfer";

pub struct MintContract;

impl Mint<ContractRuntime, ContractStorage> for MintContract {}

pub fn delegate() {
    let mint_contract = MintContract;

    let method_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    match method_name.as_str() {
        // Type: `fn mint(amount: U512) -> Result<URef, Error>`
        METHOD_MINT => {
            let amount: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let result: Result<URef, Error> = mint_contract.mint(amount);
            let ret = CLValue::from_t(result).unwrap_or_revert();
            runtime::ret(ret)
        }
        // Type: `fn create() -> URef`
        METHOD_CREATE => {
            let uref = mint_contract.mint(U512::zero()).unwrap_or_revert();
            let ret = CLValue::from_t(uref).unwrap_or_revert();
            runtime::ret(ret)
        }
        // Type: `fn balance(purse: URef) -> Option<U512>`
        METHOD_BALANCE => {
            let uref: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let balance: Option<U512> = mint_contract.balance(uref).unwrap_or_revert();
            let ret = CLValue::from_t(balance).unwrap_or_revert();
            runtime::ret(ret)
        }
        // Type: `fn transfer(source: URef, target: URef, amount: U512) -> Result<(), Error>`
        METHOD_TRANSFER => {
            let source: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let target: URef = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let amount: U512 = runtime::get_arg(3)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let result: Result<(), Error> = mint_contract.transfer(source, target, amount);
            let ret = CLValue::from_t(result).unwrap_or_revert();
            runtime::ret(ret);
        }

        _ => panic!("Unknown method name!"),
    }
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    delegate();
}
