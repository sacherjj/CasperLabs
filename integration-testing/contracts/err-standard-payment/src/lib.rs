#![no_std]
#![feature(alloc)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::pointers::UPointer;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const POS_CONTRACT_NAME: &str = "pos";
const GET_PAYMENT_PURSE: &str = "get_payment_purse";

#[no_mangle]
pub extern "C" fn call() {
    panic!("Hello world");
}
