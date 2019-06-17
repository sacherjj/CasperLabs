#![no_std]
#![feature(alloc)]

extern crate common;

#[no_mangle]
pub extern "C" fn call() {
    // Dummy function, not used, it is here because build system expects a call contract.
}
