#![no_std]

extern crate alloc;

use alloc::{
    string::{String, ToString},
    vec,
};

use contract_ffi::{
    contract_api::{account, runtime, storage, system, Error as ApiError},
    unwrap_or_revert::UnwrapOrRevert,
    value::{account::PurseId, U512},
};

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
    let phase = runtime::get_phase();
    if phase == contract_ffi::execution::Phase::Payment {
        let amount: U512 = runtime::get_arg(Arg::Amount as u32)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);

        let main_purse: PurseId = account::get_main_purse();

        let pos_pointer = system::get_proof_of_stake();

        let payment_purse: PurseId =
            runtime::call_contract(pos_pointer, &(GET_PAYMENT_PURSE,), &vec![]);

        system::transfer_from_purse_to_purse(main_purse, payment_purse, amount).unwrap_or_revert()
    }

    let value: Option<&str> = {
        match phase {
            contract_ffi::execution::Phase::Payment => Some("payment"),
            contract_ffi::execution::Phase::Session => Some("session"),
            _ => None,
        }
    };
    let value = value.unwrap_or_revert_with(ApiError::User(Error::InvalidPhase as u16));
    let result_key = storage::new_turef(value.to_string()).into();
    let mut uref_name: String = NEW_UREF_RESULT_UREF_NAME.to_string();
    uref_name.push_str("-");
    uref_name.push_str(value);
    runtime::put_key(&uref_name, &result_key);
}
