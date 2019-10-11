#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;

use contract_api::runtime;
use contract_api::system;
use contract_ffi::contract_api::ContractRef;
use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;

fn purse_to_key(p: &PurseId) -> Key {
    Key::URef(p.value())
}

fn set_refund_purse(pos: &ContractRef, p: &PurseId) {
    runtime::call_contract::<_, ()>(
        pos.clone(),
        &("set_refund_purse", *p),
        &vec![purse_to_key(p)],
    );
}

fn get_refund_purse(pos: &ContractRef) -> Option<PurseId> {
    runtime::call_contract(pos.clone(), &("get_refund_purse",), &Vec::new())
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = system::get_proof_of_stake();

    let p1 = system::create_purse();
    let p2 = system::create_purse();

    // get_refund_purse should return None before setting it
    let refund_result = get_refund_purse(&pos_pointer);
    if refund_result.is_some() {
        runtime::revert(Error::User(1));
    }

    // it should return Some(x) after calling set_refund_purse(x)
    set_refund_purse(&pos_pointer, &p1);
    let refund_purse = match get_refund_purse(&pos_pointer) {
        None => runtime::revert(Error::User(2)),
        Some(x) if x.value().addr() == p1.value().addr() => x.value(),
        Some(_) => runtime::revert(Error::User(3)),
    };

    // the returned purse should not have any access rights
    if refund_purse.is_addable() || refund_purse.is_writeable() || refund_purse.is_readable() {
        runtime::revert(Error::User(4))
    }

    // get_refund_purse should return correct value after setting a second time
    set_refund_purse(&pos_pointer, &p2);
    match get_refund_purse(&pos_pointer) {
        None => runtime::revert(Error::User(5)),
        Some(x) if x.value().addr() == p2.value().addr() => (),
        Some(_) => runtime::revert(Error::User(6)),
    }
}
