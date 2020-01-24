#![no_std]

extern crate alloc;

mod api;
mod deployer;
mod vesting;
mod error;
mod proxy;

#[no_mangle]
pub extern "C" fn call() {
    deployer::deploy();
}
