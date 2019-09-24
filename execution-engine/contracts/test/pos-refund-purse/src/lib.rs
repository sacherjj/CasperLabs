#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::vec::Vec;

use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

enum Error {
    GetPosURef = 1,
    RefundPurseShouldNotExist = 2,
    RefundPurseNotFound = 3,
    RefundPurseInvalid = 4,
    RefundPurseIncorrectAccessRights = 5,
    MissingArgument = 100,
    InvalidArgument = 101,
}

fn purse_to_key(p: &PurseId) -> Key {
    Key::URef(p.value())
}

fn set_refund_purse(pos: &ContractPointer, p: &PurseId) {
    contract_api::call_contract::<_, ()>(
        pos.clone(),
        &("set_refund_purse", *p),
        &vec![purse_to_key(p)],
    );
}

fn get_refund_purse(pos: &ContractPointer) -> Option<PurseId> {
    contract_api::call_contract(pos.clone(), &("get_refund_purse",), &Vec::new())
}

fn get_payment_purse(pos: &ContractPointer) -> PurseId {
    contract_api::call_contract(pos.clone(), &("get_payment_purse",), &Vec::new())
}

fn submit_payment(pos: &ContractPointer, amount: U512) {
    let payment_purse = get_payment_purse(pos);
    let main_purse = contract_api::main_purse();
    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
    {
        contract_api::revert(99);
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer =
        contract_api::get_pos().unwrap_or_else(|| contract_api::revert(Error::GetPosURef as u32));

    let p1 = contract_api::create_purse();
    let p2 = contract_api::create_purse();

    // get_refund_purse should return None before setting it
    let refund_result = get_refund_purse(&pos_pointer);
    if refund_result.is_some() {
        contract_api::revert(Error::RefundPurseShouldNotExist as u32);
    }

    // it should return Some(x) after calling set_refund_purse(x)
    set_refund_purse(&pos_pointer, &p1);
    let refund_purse = match get_refund_purse(&pos_pointer) {
        None => contract_api::revert(Error::RefundPurseNotFound as u32),
        Some(x) if x.value().addr() == p1.value().addr() => x.value(),
        Some(_) => contract_api::revert(Error::RefundPurseInvalid as u32),
    };

    // the returned purse should not have any access rights
    if refund_purse.is_addable() || refund_purse.is_writeable() || refund_purse.is_readable() {
        contract_api::revert(Error::RefundPurseIncorrectAccessRights as u32)
    }

    // get_refund_purse should return correct value after setting a second time
    set_refund_purse(&pos_pointer, &p2);
    match get_refund_purse(&pos_pointer) {
        None => contract_api::revert(Error::RefundPurseNotFound as u32),
        Some(x) if x.value().addr() == p2.value().addr() => (),
        Some(_) => contract_api::revert(Error::RefundPurseInvalid as u32),
    }

    let payment_amount: U512 = match contract_api::get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument as u32),
        None => contract_api::revert(Error::MissingArgument as u32),
    };

    submit_payment(&pos_pointer, payment_amount);
}
