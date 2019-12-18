#![no_std]

extern crate alloc;

mod api;
mod deployer;
mod erc20;
mod error;
mod proxy;

#[no_mangle]
pub extern "C" fn call() {
    deployer::deploy();
}
