#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::{ContractPointer, UPointer};
use contract_ffi::key::Key;
use contract_ffi::uref::AccessRights;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

enum Error {
    GetPosOuterURef = 1000,
    GetPosInnerURef = 1001,
}

fn purse_to_key(p: PurseId) -> Key {
    Key::URef(p.value())
}

fn get_pos_contract() -> ContractPointer {
    let outer: UPointer<Key> = contract_api::get_uref("pos")
        .and_then(Key::to_u_ptr)
        .unwrap_or_else(|| contract_api::revert(Error::GetPosInnerURef as u32));
    if let Some(ContractPointer::URef(inner)) = contract_api::read::<Key>(outer).to_c_ptr() {
        ContractPointer::URef(UPointer::new(inner.0, AccessRights::READ))
    } else {
        contract_api::revert(Error::GetPosOuterURef as u32)
    }
}

const POS_BOND: &str = "bond";

fn bond(pos: ContractPointer, amount: &U512, source: PurseId) {
    contract_api::call_contract::<_, ()>(
        pos,
        &(POS_BOND, *amount, source),
        &vec![purse_to_key(source)],
    );
}

#[no_mangle]
pub extern "C" fn call() {
    bond(
        get_pos_contract(),
        &U512::from(0),
        contract_api::main_purse(),
    );
}
