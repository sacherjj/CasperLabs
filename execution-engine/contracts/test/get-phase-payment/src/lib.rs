#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
extern crate contracts_common;

use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::execution::Phase;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;
use contracts_common::Error;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";

fn standard_payment(amount: U512) {
    let main_purse = contract_api::main_purse();

    let pos_pointer = contracts_common::get_pos_contract();

    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(Error::Transfer as u32);
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let known_phase: Phase = contract_api::get_arg(0);
    let get_phase = contract_api::get_phase();
    assert_eq!(
        get_phase, known_phase,
        "get_phase did not return known_phase"
    );

    standard_payment(U512::from(10_000_000));
}
