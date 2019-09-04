#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::pointers::UPointer;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::execution::Phase;
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const POS_CONTRACT_NAME: &str = "pos";
const GET_PAYMENT_PURSE: &str = "get_payment_purse";

#[repr(u32)]
enum Error {
    GetPosOuterURef = 1,
    GetPosInnerURef = 2,
    TransferFromSourceToPayment = 3,
}

fn standard_payment(amount: U512) {
    let main_purse = contract_api::main_purse();

    let pos_public: UPointer<Key> = contract_api::get_uref(POS_CONTRACT_NAME)
        .and_then(Key::to_u_ptr)
        .unwrap_or_else(|| contract_api::revert(Error::GetPosOuterURef as u32));

    let pos_contract = contract_api::read(pos_public)
        .to_c_ptr()
        .unwrap_or_else(|| contract_api::revert(Error::GetPosInnerURef as u32));

    let payment_purse: PurseId =
        contract_api::call_contract(pos_contract, &(GET_PAYMENT_PURSE,), &vec![]);

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(Error::TransferFromSourceToPayment as u32);
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
