#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api;
use cl_std::value::account::PublicKey;

#[no_mangle]
pub extern "C" fn call() {
    let known_public_key: PublicKey = contract_api::get_arg(0);
    let caller_public_key: PublicKey = contract_api::get_caller();
    assert_eq!(
        caller_public_key, known_public_key,
        "caller public key was not known public key"
    );
}
