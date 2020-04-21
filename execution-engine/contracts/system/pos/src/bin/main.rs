#![no_std]
#![cfg_attr(not(test), no_main)]

#[no_mangle]
pub extern "C" fn call() {
    pos::delegate();
}
