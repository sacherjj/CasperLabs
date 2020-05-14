#![no_std]
#![no_main]

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, runtime_args, ApiError, Key, RuntimeArgs, URef, U512};

pub const ARG_AMOUNT: &str = "amount";
pub const ARG_PURSE: &str = "purse";
pub const ARG_ACCOUNT_KEY: &str = "account";

fn set_refund_purse(contract_key: Key, p: &URef) {
    runtime::call_contract(
        contract_key,
        "set_refund_purse",
        runtime_args! {
            ARG_PURSE => *p,
        },
    )
}

fn get_payment_purse(contract_key: Key) -> URef {
    runtime::call_contract(contract_key, "get_payment_purse", runtime_args! {})
}

fn submit_payment(contract_key: Key, amount: U512) {
    let payment_purse = get_payment_purse(contract_key);
    let main_purse = account::get_main_purse();
    system::transfer_from_purse_to_purse(main_purse, payment_purse, amount).unwrap_or_revert()
}

fn finalize_payment(contract_key: Key, amount_spent: U512, account: PublicKey) {
    runtime::call_contract(
        contract_key,
        "finalize_payment",
        runtime_args! {
            ARG_AMOUNT => amount_spent,
            ARG_ACCOUNT_KEY => account,
        },
    )
}

#[no_mangle]
pub extern "C" fn call() {
    let contract_key = system::get_proof_of_stake();

    let payment_amount: U512 = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let refund_purse_flag: u8 = runtime::get_arg(1)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let maybe_amount_spent: Option<U512> = runtime::get_arg(2)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let maybe_account: Option<PublicKey> = runtime::get_arg(3)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    submit_payment(contract_key, payment_amount);
    if refund_purse_flag != 0 {
        let refund_purse = system::create_purse();
        runtime::put_key("local_refund_purse", Key::URef(refund_purse));
        set_refund_purse(contract_key, &refund_purse);
    }

    if let (Some(amount_spent), Some(account)) = (maybe_amount_spent, maybe_account) {
        finalize_payment(contract_key, amount_spent, account);
    }
}
