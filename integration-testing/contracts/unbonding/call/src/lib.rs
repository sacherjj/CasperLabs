#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::contract_api::{call_contract, get_uref, read, revert};
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::uint::U512;

enum Error {
    GetPosOuterURef = 1000,
    GetPosInnerURef = 1001,
}

fn get_pos_contract() -> ContractPointer {
    let outer: TURef<Key> = get_uref("pos")
        .and_then(Key::to_turef)
        .unwrap_or_else(|| revert(Error::GetPosInnerURef as u32));
    if let Some(ContractPointer::URef(inner)) = read::<Key>(outer).to_c_ptr() {
        ContractPointer::URef(TURef::new(inner.addr(), AccessRights::READ))
    } else {
        revert(Error::GetPosOuterURef as u32)
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_contract: ContractPointer = get_pos_contract();
    // I dont have any safe method to check for the existence of the args.
    // I am utilizing 0(invalid) amount to indicate no args to EE.
    let unbond_amount: Option<U512> = match contract_api::get_arg::<u32>(0).unwrap().unwrap() {
        0 => None,
        amount => Some(amount.into()),
    };
    let _result: () = call_contract(pos_contract, &("unbond", unbond_amount), &vec![]);
}
