#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

#[repr(u16)]
enum Error {
    ShouldNotExist = 0,
    NotFound,
    Invalid,
    IncorrectAccessRights,
}

fn purse_to_key(p: &PurseId) -> Key {
    Key::URef(p.value())
}

fn set_refund_purse(pos: &ContractPointer, p: &PurseId) {
    contract_api::runtime::call_contract::<_, ()>(
        pos.clone(),
        &("set_refund_purse", *p),
        &vec![purse_to_key(p)],
    );
}

fn get_refund_purse(pos: &ContractPointer) -> Option<PurseId> {
    contract_api::runtime::call_contract(pos.clone(), &("get_refund_purse",), &Vec::new())
}

fn get_payment_purse(pos: &ContractPointer) -> PurseId {
    contract_api::runtime::call_contract(pos.clone(), &("get_payment_purse",), &Vec::new())
}

fn submit_payment(pos: &ContractPointer, amount: U512) {
    let payment_purse = get_payment_purse(pos);
    let main_purse = contract_api::account::get_main_purse();
    contract_api::system::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
        .unwrap_or_revert()
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = contract_api::system::get_proof_of_stake();

    let p1 = contract_api::system::create_purse();
    let p2 = contract_api::system::create_purse();

    // get_refund_purse should return None before setting it
    let refund_result = get_refund_purse(&pos_pointer);
    if refund_result.is_some() {
        contract_api::runtime::revert(ApiError::User(Error::ShouldNotExist as u16));
    }

    // it should return Some(x) after calling set_refund_purse(x)
    set_refund_purse(&pos_pointer, &p1);
    let refund_purse = match get_refund_purse(&pos_pointer) {
        None => contract_api::runtime::revert(ApiError::User(Error::NotFound as u16)),
        Some(x) if x.value().addr() == p1.value().addr() => x.value(),
        Some(_) => contract_api::runtime::revert(ApiError::User(Error::Invalid as u16)),
    };

    // the returned purse should not have any access rights
    if refund_purse.is_addable() || refund_purse.is_writeable() || refund_purse.is_readable() {
        contract_api::runtime::revert(ApiError::User(Error::IncorrectAccessRights as u16))
    }

    // get_refund_purse should return correct value after setting a second time
    set_refund_purse(&pos_pointer, &p2);
    match get_refund_purse(&pos_pointer) {
        None => contract_api::runtime::revert(ApiError::User(Error::NotFound as u16)),
        Some(x) if x.value().addr() == p2.value().addr() => (),
        Some(_) => contract_api::runtime::revert(ApiError::User(Error::Invalid as u16)),
    }

    let payment_amount: U512 = contract_api::runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    submit_payment(&pos_pointer, payment_amount);
}
