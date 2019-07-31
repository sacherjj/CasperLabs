#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;

extern crate common;
use common::contract_api;
use common::contract_api::pointers::UPointer;
use common::key::Key;
use common::value::uint::U512;

#[no_mangle]
pub extern "C" fn call() {
    let pos_public: UPointer<Key> = contract_api::get_uref("pos").unwrap().to_u_ptr().unwrap();
    let pos_contract: Key = contract_api::read(pos_public);
    let pos_pointer = pos_contract.to_c_ptr().unwrap();

    // Note: this could be contract_api::get_arg(0) instead of hard-coded
    let unbond_amount: Option<U512> = None;
    let _result: () = contract_api::call_contract(pos_pointer, &("unbond", unbond_amount), &vec![]);
}
