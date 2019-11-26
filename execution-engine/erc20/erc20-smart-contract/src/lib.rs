#![no_std]

extern crate alloc;

mod api;
mod erc20;
mod proxy;
mod deployer;
mod error;

#[no_mangle]
pub extern "C" fn call() {
    deployer::deploy();
}
