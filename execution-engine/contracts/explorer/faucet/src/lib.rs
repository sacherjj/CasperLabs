#![no_std]

extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api::{runtime, storage, system, Error};
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;

const TRANSFER_AMOUNT: u32 = 10_000_000;

/// Executes token transfer to supplied public key.
/// Transfers 10_000_000 motes every time.
///
/// Revert status codes:
/// 1 - requested transfer to already funded public key.
#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = runtime::get_arg(0)
        .unwrap_or_revert_with(Error::MissingArgument)
        .unwrap_or_revert_with(Error::InvalidArgument);

    // Maybe we will decide to allow multiple funds up until some maximum value.
    let already_funded = storage::read_local::<PublicKey, U512>(public_key)
        .unwrap_or_default()
        .is_some();
    if already_funded {
        runtime::revert(Error::User(1));
    } else {
        let u512_tokens = U512::from(TRANSFER_AMOUNT);
        system::transfer_to_account(public_key, u512_tokens).unwrap_or_revert();
        // Transfer successful; Store the fact of funding in the local state.
        storage::write_local(public_key, u512_tokens);
    }
}
