#![no_std]
#![no_main]

extern crate alloc;

use alloc::collections::BTreeMap;

use contract::contract_api::{runtime, storage};

#[no_mangle]
pub extern "C" fn calculate() -> u64 {
    let large_prime: u64 = 0xffff_fffb;

    let mut result: u64 = 42;
    // calculate 42^4242 mod large_prime
    for _ in 1..4242 {
        result *= 42;
        result %= large_prime;
    }

    result
}

#[no_mangle]
pub extern "C" fn call() {
    let named_keys = BTreeMap::new();
    let pointer = storage::store_function_at_hash("calculate", named_keys);
    runtime::put_key("expensive-calculation", pointer.into());
}
