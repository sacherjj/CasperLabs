#![no_std]
#![no_main]

use contract::contract_api::{runtime, storage};
use types::{contracts::DEFAULT_ENTRY_POINT_NAME, AccessRights, ContractHash, RuntimeArgs, URef};

const REPLACEMENT_DATA: &str = "bawitdaba";

#[no_mangle]
pub extern "C" fn call() {
    let contract_hash: ContractHash = runtime::get_named_arg("contract_hash");

    let reference: URef = runtime::call_contract(
        contract_hash,
        DEFAULT_ENTRY_POINT_NAME,
        RuntimeArgs::default(),
    );
    let forged_reference: URef = URef::new(reference.addr(), AccessRights::READ_ADD_WRITE);
    storage::write(forged_reference, REPLACEMENT_DATA)
}
