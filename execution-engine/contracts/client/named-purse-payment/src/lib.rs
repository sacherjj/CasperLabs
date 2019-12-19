#![no_std]

extern crate alloc;

use alloc::{string::String, vec};

use contract_ffi::{
    contract_api::{runtime, system, Error},
    key::Key,
    unwrap_or_revert::UnwrapOrRevert,
    value::{account::PurseId, U512},
};

const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const SET_REFUND_PURSE: &str = "set_refund_purse";

enum Arg {
    PurseName = 0,
    Amount = 1,
}

#[no_mangle]
pub extern "C" fn call() {
    let purse_name: String = runtime::get_arg(Arg::PurseName as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let purse_key = runtime::get_key(&purse_name).unwrap_or_revert_with(Error::InvalidPurseName);
    let purse = purse_key
        .as_uref()
        .map(|uref| PurseId::new(*uref))
        .unwrap_or_revert_with(Error::InvalidPurse);

    let amount: U512 = runtime::get_arg(Arg::Amount as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    let pos_pointer = system::get_proof_of_stake();

    let payment_purse: PurseId =
        runtime::call_contract(pos_pointer.clone(), (GET_PAYMENT_PURSE,), vec![]);

    runtime::call_contract::<_, ()>(
        pos_pointer,
        (SET_REFUND_PURSE, purse),
        vec![Key::URef(purse.value())],
    );

    system::transfer_from_purse_to_purse(purse, payment_purse, amount).unwrap_or_revert();
}
