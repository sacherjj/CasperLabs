#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::key::Key;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const SET_REFUND_PURSE: &str = "set_refund_purse";

#[repr(u16)]
enum Error {
    PosNotFound = 1,
    NamedPurseNotFound = 2,
}

enum Arg {
    Amount = 0,
    Name = 1,
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::runtime::get_arg(Arg::Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let name: String = contract_api::runtime::get_arg(Arg::Name as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let purse: PurseId =
        get_named_purse(&name).unwrap_or_revert_with(ApiError::User(Error::PosNotFound as u16));

    let pos_pointer = contract_api::system::get_proof_of_stake();
    let payment_purse: PurseId =
        contract_api::runtime::call_contract(pos_pointer.clone(), &(GET_PAYMENT_PURSE,), &vec![]);

    contract_api::runtime::call_contract::<_, ()>(
        pos_pointer,
        &(SET_REFUND_PURSE, purse),
        &vec![Key::URef(purse.value())],
    );

    contract_api::system::transfer_from_purse_to_purse(purse, payment_purse, amount)
        .unwrap_or_revert();
}

fn get_named_purse(name: &str) -> Option<PurseId> {
    let key = contract_api::runtime::get_key(name)
        .unwrap_or_revert_with(ApiError::User(Error::NamedPurseNotFound as u16));
    let uref = key.as_uref()?;

    Some(PurseId::new(*uref))
}
