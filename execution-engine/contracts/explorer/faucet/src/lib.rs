#![no_std]

use contract::{
    contract_api::{runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, U512};

/// Executes token transfer to supplied public key.

/// Revert status codes:
/// 1 - requested transfer to already funded public key.
#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let amount: U512 = runtime::get_arg(1)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    // Maybe we will decide to allow multiple funds up until some maximum value.
    let already_funded = storage::read_local::<PublicKey, U512>(&public_key)
        .unwrap_or_default()
        .is_some();

    if already_funded {
        runtime::revert(ApiError::User(1));
    } else {
        system::transfer_to_account(public_key, amount).unwrap_or_revert();
        // Transfer successful; Store the fact of funding in the local state.
        storage::write_local(public_key, amount);
    }
}
