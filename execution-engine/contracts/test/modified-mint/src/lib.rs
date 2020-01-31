#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{runtime, storage, TURef},
    unwrap_or_revert::UnwrapOrRevert,
};
use mint_token::{mint::Mint, CLMint};
use types::{system_contract_errors::mint::Error, ApiError, CLValue, URef, U512};

const VERSION: &str = "1.1.0";

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
            let uref = mint.create();
            let ret = CLValue::from_t(uref).unwrap_or_revert();
            runtime::ret(ret)
        }

        "balance" => {
            let uref: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let balance_uref: TURef<U512> = match mint.lookup(uref) {
                Some(uref) if uref.access_rights().is_some() => TURef::from_uref(uref).unwrap(),
                Some(_) => runtime::revert(ApiError::NoAccessRights),
                None => runtime::revert(ApiError::ValueNotFound),
            };
            let balance: Option<U512> = storage::read(balance_uref).unwrap_or_default();
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
            let result: Result<(), Error> = mint.transfer(source, target, amount);
            let ret = CLValue::from_t(result).unwrap_or_revert();
            runtime::ret(ret);
        }
        "version" => {
            runtime::ret(CLValue::from_t(VERSION).unwrap_or_revert());
        }

        _ => panic!("Unknown method name!"),
    }
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    delegate();
}
