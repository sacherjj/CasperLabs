#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
use contract_ffi::contract_api::{self, Error};
use contract_ffi::value::uint::U512;

const UNBOND_METHOD_NAME: &str = "unbond";

// Unbonding contract.
//
// Accepts unbonding amount (of type `Option<u64>`) as first argument.
// Unbonding with `None` unbonds all stakes in the PoS contract.
// Otherwise (`Some<u64>`) unbonds with part of the bonded stakes.
#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contract_api::get_pos();

    let unbond_amount: Option<U512> = match contract_api::get_arg::<Option<u64>>(0) {
        Some(Ok(Some(data))) => Some(U512::from(data)),
        Some(Ok(None)) => None,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument.into()),
        None => contract_api::revert(Error::MissingArgument.into()),
    };

    contract_api::call_contract(pos_pointer, &(UNBOND_METHOD_NAME, unbond_amount), &vec![])
}
