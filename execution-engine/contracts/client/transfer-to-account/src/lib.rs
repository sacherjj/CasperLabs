#![no_std]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::AccountHash, ApiError, U512};

enum Args {
    AccountHash = 0,
    Amount = 1,
}

/// Executes mote transfer to supplied public key.
/// Transfers the requested amount.
pub fn delegate() {
    let account_hash: AccountHash = runtime::get_arg(Args::AccountHash as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let transfer_amount: u64 = runtime::get_arg(Args::Amount as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let u512_motes = U512::from(transfer_amount);
    system::transfer_to_account(account_hash, u512_motes).unwrap_or_revert();
}
