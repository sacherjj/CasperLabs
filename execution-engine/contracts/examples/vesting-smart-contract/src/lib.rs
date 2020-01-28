#![no_std]

extern crate alloc;

mod api;
mod deployer;
mod error;
mod proxy;
mod vesting;

#[no_mangle]
pub extern "C" fn call() {
    deployer::deploy();
}
