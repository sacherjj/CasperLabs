#![no_std]
#![feature(alloc)]

extern crate alloc;
extern crate common;

#[no_mangle]
pub extern "C" fn call() {
    // Dummy function to satisfy build system which requires each contract to have both define and call parts.
}
