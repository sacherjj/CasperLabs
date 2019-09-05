#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;
use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::UPointer;
use contract_ffi::key::Key;
use contract_ffi::value::uint::U512;

#[no_mangle]
pub extern "C" fn call() {
    let pos_public: UPointer<Key> = contract_api::get_uref("pos").unwrap().to_u_ptr().unwrap();
    let pos_contract: Key = contract_api::read(pos_public);
    let pos_pointer = pos_contract.to_c_ptr().unwrap();
    // I dont have any safe method to check for the existence of the args.
    // I am utilizing 0(invalid) amount to indicate no args to EE.
    let unbond_amount: Option<U512> = match contract_api::get_arg::<u32>(0) {
        0 => None,
        amount => Some(amount.into()),
    };
    let _result: () = contract_api::call_contract(pos_pointer, &("unbond", unbond_amount), &vec![]);
}
