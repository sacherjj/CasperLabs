#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::uint::U512;

const BOND_METHOD_NAME: &str = "bond";

// Bonding contract.
//
// Accepts bonding amount (of type `u64`) as first argument.
// Issues bonding request to the PoS contract.
#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contract_api::system::get_proof_of_stake();
    let source_purse = contract_api::account::get_main_purse();
    let bonding_purse = contract_api::system::create_purse();
    let bond_amount: U512 = contract_api::runtime::get_arg::<u64>(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument)
        .into();

    contract_api::system::transfer_from_purse_to_purse(source_purse, bonding_purse, bond_amount)
        .unwrap_or_revert();
    contract_api::runtime::call_contract(
        pos_pointer,
        &(BOND_METHOD_NAME, bond_amount, bonding_purse),
        &vec![Key::URef(bonding_purse.value())],
    )
}
