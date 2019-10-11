#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{account, runtime, system, ContractRef};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

fn purse_to_key(p: PurseId) -> Key {
    Key::URef(p.value())
}

const POS_BOND: &str = "bond";

fn bond(pos: ContractRef, amount: &U512, source: PurseId) {
    runtime::call_contract::<_, ()>(
        pos,
        &(POS_BOND, *amount, source),
        &vec![purse_to_key(source)],
    );
}

#[no_mangle]
pub extern "C" fn call() {
    bond(
        system::get_proof_of_stake(),
        &U512::from(0),
        account::get_main_purse(),
    );
}
