#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use mint_token::{
    internal_purse_id::{DepositId, WithdrawId},
    mint::Mint,
    CLMint,
};
use types::{
    system_contract_errors::mint::{Error, PurseIdError},
    AccessRights, ApiError, CLValue, URef, U512,
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
            let return_value = CLValue::from_t(maybe_purse_key).unwrap_or_revert();

            runtime::ret(return_value)
        }

        "create" => {
            let purse_id = mint.create();
            let purse_key = URef::new(purse_id.raw_id(), AccessRights::READ_ADD_WRITE);
            let return_value = CLValue::from_t(purse_key).unwrap_or_revert();
            runtime::ret(return_value)
        }

        "balance" => {
            let key: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let purse_id: WithdrawId = WithdrawId::from_uref(key).unwrap();
            let balance_uref = mint.lookup(purse_id);
            let balance: Option<U512> =
                balance_uref.and_then(|uref| storage::read(uref.into()).unwrap_or_default());
            let return_value = CLValue::from_t(balance).unwrap_or_revert();
            runtime::ret(return_value)
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

            let return_error = |error: PurseIdError| -> ! {
                let transfer_result: Result<(), Error> = Err(error.into());
                let return_value = CLValue::from_t(transfer_result).unwrap_or_revert();
                runtime::ret(return_value)
            };

            let source: WithdrawId = match WithdrawId::from_uref(source) {
                Ok(withdraw_id) => withdraw_id,
                Err(error) => return_error(error),
            };

            let target: DepositId = match DepositId::from_uref(target) {
                Ok(deposit_id) => deposit_id,
                Err(error) => return_error(error),
            };

            let transfer_result = mint.transfer(source, target, amount);
            let return_value = CLValue::from_t(transfer_result).unwrap_or_revert();
            runtime::ret(return_value);
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
