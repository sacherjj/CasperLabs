#![no_std]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, TransferredTo, U512};

enum Arg {
    PublicKey = 0,
    Amount = 1,
}

#[repr(u16)]
enum Error {
    TransferredToNewAccount = 0,
}

#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = runtime::get_arg(Arg::PublicKey as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let amount: U512 = runtime::get_arg(Arg::Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let result = system::transfer_to_account(public_key, amount).unwrap_or_revert();
    match result {
        TransferredTo::ExistingAccount => {
            // This is the expected result, as all accounts have to be initialized beforehand
        }
        TransferredTo::NewAccount => {
            runtime::revert(ApiError::User(Error::TransferredToNewAccount as u16))
        }
    }
}
