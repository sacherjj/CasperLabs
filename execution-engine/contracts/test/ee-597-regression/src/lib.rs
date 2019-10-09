#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

fn purse_to_key(p: PurseId) -> Key {
    Key::URef(p.value())
}

const POS_BOND: &str = "bond";

fn bond(pos: ContractPointer, amount: &U512, source: PurseId) {
    contract_api::runtime::call_contract::<_, ()>(
        pos,
        &(POS_BOND, *amount, source),
        &vec![purse_to_key(source)],
    );
}

#[no_mangle]
pub extern "C" fn call() {
    bond(
        contract_api::system::get_proof_of_stake(),
        &U512::from(0),
        contract_api::account::get_main_purse(),
    );
}
