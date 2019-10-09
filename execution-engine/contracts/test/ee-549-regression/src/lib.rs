#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{runtime, system};

const SET_REFUND_PURSE: &str = "set_refund_purse";

fn malicious_revenue_stealing_contract() {
    let purse = system::create_purse();
    let pos_pointer = system::get_proof_of_stake();

    runtime::call_contract::<_, ()>(
        pos_pointer,
        &(SET_REFUND_PURSE, purse),
        &vec![purse.value().into()],
    );
}

#[no_mangle]
pub extern "C" fn call() {
    malicious_revenue_stealing_contract()
}
