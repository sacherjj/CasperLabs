#![no_std]

#[macro_use]
extern crate alloc;

extern crate contract_ffi;

use alloc::string::{String, ToString};

use contract_ffi::contract_api::{self, Error as ApiError};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const GET_PAYMENT_PURSE: &str = "get_payment_purse";
const NEW_UREF_RESULT_UREF_NAME: &str = "new_uref_result";

enum Arg {
    Amount = 0,
}

#[repr(u16)]
enum Error {
    InvalidPhase = 0,
}

#[no_mangle]
pub extern "C" fn call() {
    let phase = contract_api::get_phase();
    if phase == contract_ffi::execution::Phase::Payment {
        let amount: U512 = contract_api::get_arg(Arg::Amount as u32)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);

        let main_purse: PurseId = contract_api::main_purse();

        let pos_pointer = contract_api::get_pos();

        let payment_purse: PurseId =
            contract_api::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

        contract_api::transfer_from_purse_to_purse(main_purse, payment_purse, amount)
            .unwrap_or_revert()
    }

    let value: Option<&str> = {
        match phase {
            contract_ffi::execution::Phase::Payment => Some("payment"),
            contract_ffi::execution::Phase::Session => Some("session"),
            _ => None,
        }
    };
    let value = value.unwrap_or_revert_with(ApiError::User(Error::InvalidPhase as u16));
    let result_key = contract_api::new_turef(value.to_string()).into();
    let mut uref_name: String = NEW_UREF_RESULT_UREF_NAME.to_string();
    uref_name.push_str("-");
    uref_name.push_str(value);
    contract_api::put_key(&uref_name, &result_key);
}
