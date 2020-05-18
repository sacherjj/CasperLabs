#![no_std]
#![no_main]

use contract::contract_api::runtime;
use types::{contracts::DEFAULT_ENTRY_POINT_NAME, ContractHash, RuntimeArgs};

const ARG_SEED: &str = "seed";

#[no_mangle]
pub extern "C" fn call() {
    let contract_hash: ContractHash = runtime::get_named_arg(ARG_SEED);
    runtime::call_contract(
        contract_hash,
        DEFAULT_ENTRY_POINT_NAME,
        RuntimeArgs::default(),
    )
}
