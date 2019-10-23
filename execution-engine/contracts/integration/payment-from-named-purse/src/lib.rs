#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use alloc::string::String;

use contract_ffi::contract_api::{runtime, system, Error as ApiError};
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

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

enum Arg {
    Amount = 0,
    Name = 1,
}

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = runtime::get_arg(Arg::Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let name: String = runtime::get_arg(Arg::Name as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let purse: PurseId = get_named_purse(&name).unwrap_or_revert_with(Error::PosNotFound);

    let pos_pointer = system::get_proof_of_stake();
    let payment_purse: PurseId =
        runtime::call_contract(pos_pointer.clone(), &(GET_PAYMENT_PURSE,), &vec![]);

    runtime::call_contract::<_, ()>(
        pos_pointer,
        &(SET_REFUND_PURSE, purse),
        &vec![Key::URef(purse.value())],
    );

    system::transfer_from_purse_to_purse(purse, payment_purse, amount).unwrap_or_revert();
}

fn get_named_purse(name: &str) -> Option<PurseId> {
    let key = runtime::get_key(name).unwrap_or_revert_with(Error::NamedPurseNotFound);
    let uref = key.as_uref()?;

    Some(PurseId::new(*uref))
}
