#![no_std]

extern crate alloc;

use alloc::vec;

use contract_ffi::{
    contract_api::{runtime, system, Error},
    unwrap_or_revert::UnwrapOrRevert,
    value::U512,
};

const UNBOND_METHOD_NAME: &str = "unbond";

enum Arg {
    UnbondAmount = 0,
}

// Unbonding contract.
//
// Accepts unbonding amount (of type `Option<U512>`) as first argument.
// Unbonding with `None` unbonds all stakes in the PoS contract.
// Otherwise (`Some<U512>`) unbonds with part of the bonded stakes.
#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = system::get_proof_of_stake();
    let unbond_amount: Option<U512> = runtime::get_arg(Arg::UnbondAmount as u32)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    runtime::call_contract(pos_pointer, (UNBOND_METHOD_NAME, unbond_amount), vec![])
}
