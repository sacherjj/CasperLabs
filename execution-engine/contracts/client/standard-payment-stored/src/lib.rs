#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::collections::BTreeMap;
use alloc::string::String;

use contract_api::store_function;

use contract_ffi::contract_api::pointers::UPointer;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;

const POS_CONTRACT_NAME: &str = "pos";
const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const STANDARD_PAYMENT_CONTRACT_NAME: &str = "standard_payment";
const TRANSFER_TO_PAYMENT_PURSE_FUNCTION_NAME: &str = "transfer";

/// standard payment logic
/// transfers from calling account's main purse to a new deploy-specific temporary payment purse
/// arg[0]: U512                // amount to transfer to payment purse
/// ERROR                       // TODO: error / revert enum
/// 66                          // failed to get proof of stake public key
/// 67                          // failed to get proof of stake c_ptr
/// 99                          // failed to transfer from purse to purse
/// NOTE
/// this is a functional duplicate of standard-payment, adding only stored contract mechanics
#[no_mangle]
pub extern "C" fn transfer() {
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

#[no_mangle]
pub extern "C" fn call() {
    let known_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let pointer = store_function(TRANSFER_TO_PAYMENT_PURSE_FUNCTION_NAME, known_urefs);
    contract_api::add_uref(STANDARD_PAYMENT_CONTRACT_NAME, &pointer.into());
}
