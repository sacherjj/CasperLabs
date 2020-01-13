#![no_std]

use contract::contract_api::{runtime, Error};

#[no_mangle]
pub extern "C" fn call() {
    // Call revert with an application specific non-zero exit code.
    runtime::revert(Error::User(1));
}
