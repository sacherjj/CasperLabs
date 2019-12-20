#![no_std]

extern crate alloc;

use alloc::vec;

use contract_ffi::{
    contract_api::{account, runtime, system, ContractRef},
    key::Key,
    value::{account::PurseId, U512},
};

fn purse_to_key(p: PurseId) -> Key {
    Key::URef(p.value())
}

const POS_BOND: &str = "bond";

fn bond(pos: ContractRef, amount: &U512, source: PurseId) {
    runtime::call_contract::<_, ()>(pos, (POS_BOND, *amount, source), vec![purse_to_key(source)]);
}

#[no_mangle]
pub extern "C" fn call() {
    bond(
        system::get_proof_of_stake(),
        &U512::from(0),
        account::get_main_purse(),
    );
}
