#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
extern crate contracts_common;

use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::uint::U512;
use contracts_common::Error;

const BOND_METHOD_NAME: &str = "bond";

// Bonding contract.
//
// Accepts bonding amount (of type `u64`) as first argument.
// Issues bonding request to the PoS contract.
#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contracts_common::get_pos_contract_read_only();
    let source_purse = contract_api::main_purse();
    let bonding_purse = contract_api::create_purse();
    let bond_amount: U512 = U512::from(contract_api::get_arg::<u64>(0));

    match contract_api::transfer_from_purse_to_purse(source_purse, bonding_purse, bond_amount) {
        PurseTransferResult::TransferSuccessful => contract_api::call_contract(
            pos_pointer,
            &(BOND_METHOD_NAME, bond_amount, bonding_purse),
            &vec![Key::URef(bonding_purse.value())],
        ),

        PurseTransferResult::TransferError => contract_api::revert(Error::Transfer as u32),
    }
}
