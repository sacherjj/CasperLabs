#![no_std]

extern crate alloc;

use alloc::{
    string::{String, ToString},
    vec,
};

use contract_ffi::{
    contract_api::{runtime, storage, Error as ApiError},
    system_contracts::mint::Error,
    unwrap_or_revert::UnwrapOrRevert,
    uref::{AccessRights, URef},
    value::U512,
};
use mint_token::{
    internal_purse_id::{DepositId, WithdrawId},
    mint::Mint,
    CLMint,
};

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

            let maybe_purse_key = mint
                .mint(amount)
                .map(|purse_id| URef::new(purse_id.raw_id(), AccessRights::READ_ADD_WRITE));

            if let Ok(purse_key) = maybe_purse_key {
                runtime::ret(maybe_purse_key, vec![purse_key])
            } else {
                runtime::ret(maybe_purse_key, vec![])
            }
        }

        "create" => {
            let purse_id = mint.create();
            let purse_key = URef::new(purse_id.raw_id(), AccessRights::READ_ADD_WRITE);
            runtime::ret(purse_key, vec![purse_key])
        }

        "balance" => {
            let key: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let purse_id: WithdrawId = WithdrawId::from_uref(key).unwrap();
            let balance_uref = mint.lookup(purse_id);
            let balance: Option<U512> =
                balance_uref.and_then(|uref| storage::read(uref.into()).unwrap_or_default());
            runtime::ret(balance, vec![])
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

            let source: WithdrawId = match WithdrawId::from_uref(source) {
                Ok(withdraw_id) => withdraw_id,
                Err(error) => {
                    let transfer_result: Result<(), Error> = Err(error.into());
                    runtime::ret(transfer_result, vec![])
                }
            };

            let target: DepositId = match DepositId::from_uref(target) {
                Ok(deposit_id) => deposit_id,
                Err(error) => {
                    let transfer_result: Result<(), Error> = Err(error.into());
                    runtime::ret(transfer_result, vec![])
                }
            };

            let transfer_result = mint.transfer(source, target, amount);
            runtime::ret(transfer_result, vec![]);
        }
        "version" => {
            runtime::ret(VERSION.to_string(), vec![]);
        }

        _ => panic!("Unknown method name!"),
    }
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    delegate();
}
