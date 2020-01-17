#![no_std]

use contract::{
    contract_api::{account, runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{PublicKey, PurseId},
    ApiError, ContractRef, Key, U512,
};

fn set_refund_purse(pos: &ContractRef, p: &PurseId) {
    runtime::call_contract(pos.clone(), ("set_refund_purse", *p))
}

fn get_payment_purse(pos: &ContractRef) -> PurseId {
    runtime::call_contract(pos.clone(), ("get_payment_purse",))
}

fn submit_payment(pos: &ContractRef, amount: U512) {
    let payment_purse = get_payment_purse(pos);
    let main_purse = account::get_main_purse();
    system::transfer_from_purse_to_purse(main_purse, payment_purse, amount).unwrap_or_revert()
}

fn finalize_payment(pos: &ContractRef, amount_spent: U512, account: PublicKey) {
    runtime::call_contract(pos.clone(), ("finalize_payment", amount_spent, account))
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = system::get_proof_of_stake();

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

    submit_payment(&pos_pointer, payment_amount);
    if refund_purse_flag != 0 {
        let refund_purse = system::create_purse();
        runtime::put_key("local_refund_purse", Key::URef(refund_purse.value()));
        set_refund_purse(&pos_pointer, &refund_purse);
    }

    if let (Some(amount_spent), Some(account)) = (maybe_amount_spent, maybe_account) {
        finalize_payment(&pos_pointer, amount_spent, account);
    }
}
