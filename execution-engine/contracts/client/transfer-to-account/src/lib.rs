#![no_std]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, U512};

enum Args {
    PublicKey = 0,
    Amount = 1,
}

/// Executes mote transfer to supplied public key.
/// Transfers the requested amount.
pub fn delegate() {
    let public_key: PublicKey = runtime::get_arg(Args::PublicKey as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let transfer_amount: u64 = runtime::get_arg(Args::Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let u512_motes = U512::from(transfer_amount);
    system::transfer_to_account(public_key, u512_motes).unwrap_or_revert();
}
