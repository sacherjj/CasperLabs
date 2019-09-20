#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, call_contract};
use contract_ffi::value::uint::U512;

enum Error {
    GetPosURef = 1000,
}

fn get_pos_contract() -> ContractPointer {
    contract_api::get_pos().unwrap_or_else(|| contract_api::revert(Error::GetPosURef as u32))
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_contract: ContractPointer = get_pos_contract();
    // I dont have any safe method to check for the existence of the args.
    // I am utilizing 0(invalid) amount to indicate no args to EE.
    let unbond_amount: Option<U512> = match contract_api::get_arg::<u32>(0) {
        0 => None,
        amount => Some(amount.into()),
    };
    let _result: () = call_contract(pos_contract, &("unbond", unbond_amount), &vec![]);
}
