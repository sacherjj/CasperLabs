#![no_std]

use contract::contract_api::{runtime, system};

const SET_REFUND_PURSE: &str = "set_refund_purse";

fn malicious_revenue_stealing_contract() {
    let purse = system::create_purse();
    let pos_pointer = system::get_proof_of_stake();

    runtime::call_contract::<_, ()>(pos_pointer, (SET_REFUND_PURSE, purse));
}

#[no_mangle]
pub extern "C" fn call() {
    malicious_revenue_stealing_contract()
}
