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
    // I dont have any safe method to check for the existence of the args.
    // I am utilizing 0(invalid) amount to indicate no args to EE.
    match contract_api::get_arg(0){
        0  => {
            let unbonded_amount: Option<U512> = None;
            let _result: () = contract_api::call_contract(pos_pointer, &("unbond",unbonded_amount), &vec![]);
        },
        amount => {
            let _result: () = contract_api::call_contract(pos_pointer, &("unbond", U512::from(amount)), &vec![]);
        }
    };
}
