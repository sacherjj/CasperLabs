#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;
use contract_ffi::contract_api::{self, PurseTransferResult};
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const SET_REFUND_PURSE: &str = "set_refund_purse";

enum Error {
    PosNotFound = 1,
    NamedPurseNotFound = 2,
    Transfer = 3,
}

enum Arg {
    Amount = 0,
    Name = 1,
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = contract_api::get_arg(Arg::Amount as u32);
    let name: String = contract_api::get_arg(Arg::Name as u32);
    let purse: PurseId =
        get_named_purse(&name).unwrap_or_else(|| contract_api::revert(Error::PosNotFound as u32));

    let pos_pointer = contract_api::get_pos()
        .unwrap_or_else(|| contract_api::revert(Error::NamedPurseNotFound as u32));
    let payment_purse: PurseId =
        contract_api::call_contract(pos_pointer.clone(), &(GET_PAYMENT_PURSE,), &vec![]);

    contract_api::call_contract::<_, ()>(
        pos_pointer,
        &(SET_REFUND_PURSE, purse),
        &vec![Key::URef(purse.value())],
    );

    if let PurseTransferResult::TransferError =
        contract_api::transfer_from_purse_to_purse(purse, payment_purse, amount)
    {
        contract_api::revert(Error::Transfer as u32);
    }
}

fn get_named_purse(name: &str) -> Option<PurseId> {
    let key = contract_api::get_uref(name)?;
    let uref = key.as_uref()?;

    Some(PurseId::new(*uref))
}
