#![no_std]
#![no_main]

extern crate alloc;

use alloc::{collections::BTreeMap, format, string::ToString, vec};

use alloc::boxed::Box;
use contract::{
    contract_api::{account, runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};

use types::{
    contract_header::{EntryPoint, EntryPointAccess, EntryPointType, Parameter},
    ApiError, CLType, Key, URef, U512,
};

const ENTRY_FUNCTION_NAME: &str = "transfer";
const HASH_KEY_NAME: &str = "transfer_purse_to_account";
const ACCESS_KEY_NAME: &str = "transfer_purse_to_account_access";
const ARG_0_NAME: &str = "target_account_addr";
const ARG_1_NAME: &str = "amount";

const TRANSFER_RESULT_UREF_NAME: &str = "transfer_result";
const MAIN_PURSE_FINAL_BALANCE_UREF_NAME: &str = "final_balance";

#[no_mangle]
pub extern "C" fn transfer() {
    let source: URef = account::get_main_purse();
    let destination = runtime::get_named_arg(ARG_0_NAME)
        .map(|arg| arg.unwrap_or_revert_with(ApiError::InvalidArgument))
        .unwrap_or_revert();
    let amount: U512 = runtime::get_named_arg(ARG_1_NAME)
        .map(|arg| arg.unwrap_or_revert_with(ApiError::InvalidArgument))
        .unwrap_or_revert();

    let transfer_result = system::transfer_from_purse_to_account(source, destination, amount);

    let final_balance = system::get_balance(source).unwrap_or_revert_with(ApiError::User(103));

    let result = format!("{:?}", transfer_result);

    let result_uref: Key = storage::new_uref(result).into();
    runtime::put_key(TRANSFER_RESULT_UREF_NAME, result_uref);
    runtime::put_key(
        MAIN_PURSE_FINAL_BALANCE_UREF_NAME,
        storage::new_uref(final_balance).into(),
    );
}

#[no_mangle]
pub extern "C" fn call() {
    let methods = {
        let mut methods = BTreeMap::new();
        let entry_point = EntryPoint::new(
            vec![
                Parameter::new(ARG_0_NAME, CLType::FixedList(Box::new(CLType::U8), 32)),
                Parameter::new(ARG_1_NAME, CLType::U512),
            ],
            CLType::Unit,
            EntryPointAccess::Public,
            EntryPointType::Session,
        );
        methods.insert(ENTRY_FUNCTION_NAME.to_string(), entry_point);
        methods
    };

    storage::new_contract(
        methods,
        None,
        Some(HASH_KEY_NAME.to_string()),
        Some(ACCESS_KEY_NAME.to_string()),
    )
    .unwrap_or_revert();
}
