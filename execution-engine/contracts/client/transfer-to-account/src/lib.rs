#![no_std]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, U512};

/// Executes mote transfer to supplied public key.
/// Transfers the requested amount.
#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let transfer_amount: U512 = runtime::get_arg(1)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    system::transfer_to_account(public_key, transfer_amount).unwrap_or_revert();
}
