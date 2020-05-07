#![no_std]
#![no_main]
#![allow(unused_imports)]

use contract;

#[no_mangle]
pub extern "C" fn call() {}
//
// extern crate alloc;
//
// use alloc::{collections::BTreeMap, string::String};
//
// use contract::{
//     contract_api::{runtime, storage},
//     unwrap_or_revert::UnwrapOrRevert,
// };
// use types::{ApiError, Key};
//
// const CONTRACT_NAME: &str = "faucet";
// const DESTINATION_HASH: &str = "hash";
// const DESTINATION_UREF: &str = "uref";
// const FUNCTION_NAME: &str = "call_faucet";
//
// #[repr(u16)]
// enum Error {
//     UnknownDestination = 1,
// }
//
// #[no_mangle]
// pub extern "C" fn call_faucet() {
//     faucet::delegate();
// }
