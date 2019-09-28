#![no_std]
#![feature(cell_update)]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{
    get_arg, read_local, revert, transfer_to_account, write_local, Error, TransferResult,
};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

const TRANSFER_AMOUNT: u32 = 10_000_000;

/// Executes token transfer to supplied public key.
/// Transfers 10_000_000 motes every time.
///
/// Revert status codes:
/// 1 - requested transfer to already funded public key.
/// 2 - transfer error.
#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = match get_arg(0) {
        Some(Ok(data)) => data,
        Some(Err(_)) => revert(Error::InvalidArgument.into()),
        None => revert(Error::MissingArgument.into()),
    };
    // Maybe we will decide to allow multiple funds up until some maximum value.
    let already_funded = read_local::<PublicKey, U512>(public_key)
        .unwrap_or_default()
        .is_some();
    if already_funded {
        revert(1);
    } else {
        let u512_tokens = U512::from(TRANSFER_AMOUNT);
        match transfer_to_account(public_key, u512_tokens) {
            TransferResult::TransferError => revert(2),
            _ => {
                // Transfer successful; Store the fact of funding in the local state.
                write_local(public_key, u512_tokens);
            }
        }
    }
}
