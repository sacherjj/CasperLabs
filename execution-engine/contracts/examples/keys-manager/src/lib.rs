#![no_std]

extern crate alloc;

mod api;
mod error;
mod keys_manager;

#[no_mangle]
pub extern "C" fn call() {
    keys_manager::execute();
}
