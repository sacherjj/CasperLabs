#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use contract_ffi::contract_api::{self, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::uint::U512;

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contract_api::system::get_proof_of_stake();
    // I dont have any safe method to check for the existence of the args.
    // I am utilizing 0(invalid) amount to indicate no args to EE.
    let unbond_amount: Option<U512> = match contract_api::runtime::get_arg::<u32>(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument)
    {
        0 => None,
        amount => Some(amount.into()),
    };
    contract_api::runtime::call_contract::<_, ()>(pos_pointer, &("unbond", unbond_amount), &vec![]);
}
