#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
extern crate contracts_common;

use contract_ffi::contract_api;
use contract_ffi::value::uint::U512;

const UNBOND_METHOD_NAME: &str = "unbond";

// Unbonding contract.
//
// Accepts unbonding amount (of type `Option<u64>`) as first argument.
// Unbonding with `None` unbonds all stakes in the PoS contract.
// Otherwise (`Some<u64>`) unbonds with part of the bonded stakes.
#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contracts_common::get_pos_contract_read_only();

    let unbond_amount: Option<U512> = contract_api::get_arg::<Option<u64>>(0).map(U512::from);

    contract_api::call_contract(pos_pointer, &(UNBOND_METHOD_NAME, unbond_amount), &vec![])
}
