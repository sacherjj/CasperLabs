#![no_std]

extern crate shared;

const NODE_01_ADDR: &[u8; 64] = b"d853ee569a6cf4315a26cf1190f9b55003aae433bd732453b967742b883da0b2";
const INITIAL_AMOUNT: u64 = 1_000_000;

#[no_mangle]
pub extern "C" fn call() {
    shared::create_account(NODE_01_ADDR, INITIAL_AMOUNT)
}
