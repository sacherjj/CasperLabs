#![no_std]
#![feature(cell_update)]

extern crate alloc;

mod contract_runtime;
mod contract_storage;

use alloc::string::String;

use contract::{contract_api::runtime, unwrap_or_revert::UnwrapOrRevert};
use mint::Mint;
use types::{system_contract_errors::mint::Error, ApiError, CLValue, URef, U512};

use crate::{contract_runtime::ContractRuntime, contract_storage::ContractStorage};

pub struct CLMint;

impl Mint<ContractRuntime, ContractStorage> for CLMint {}

pub fn delegate() {
    let mint = CLMint;
    let method_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    match method_name.as_str() {
        // argument: U512
        // return: Result<URef, mint::error::Error>
        "mint" => {
            let amount: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let result: Result<URef, Error> = mint.mint(amount);
            let ret = CLValue::from_t(result).unwrap_or_revert();
            runtime::ret(ret)
        }

        "create" => {
            let uref = mint.mint(U512::zero()).unwrap_or_revert();
            let ret = CLValue::from_t(uref).unwrap_or_revert();
            runtime::ret(ret)
        }

        "balance" => {
            let uref: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let balance: Option<U512> = mint.balance(uref).unwrap_or_revert();
            let ret = CLValue::from_t(balance).unwrap_or_revert();
            runtime::ret(ret)
        }

        "transfer" => {
            let source: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let target: URef = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let amount: U512 = runtime::get_arg(3)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let result: Result<(), Error> = if !source.is_writeable() || !target.is_addable() {
                Err(Error::InvalidAccessRights)
            } else {
                mint.transfer(source, target, amount)
            };
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
