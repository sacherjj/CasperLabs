#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::pointers::UPointer;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;

const POS_CONTRACT_NAME: &str = "pos";
const GET_PAYMENT_PURSE: &str = "get_payment_purse";

#[no_mangle]
pub extern "C" fn call() {
    let amount = contract_api::get_arg(0);

    let main_purse = contract_api::main_purse();

    let pos_public: UPointer<Key> = contract_api::get_uref(POS_CONTRACT_NAME)
        .and_then(contract_ffi::key::Key::to_u_ptr)
        .unwrap_or_else(|| contract_api::revert(66));

    let pos_contract = contract_api::read(pos_public)
        .to_c_ptr()
        .unwrap_or_else(|| contract_api::revert(67));

    let payment_purse: PurseId =
        contract_api::call_contract(pos_contract, &(GET_PAYMENT_PURSE), &vec![]);

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(99);
    }
}
