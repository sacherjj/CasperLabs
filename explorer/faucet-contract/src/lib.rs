#![no_std]
#![feature(alloc, cell_update)]

extern crate alloc;
extern crate cl_std;

use cl_std::contract_api::{get_arg, read_local, revert, transfer_to_account, TransferResult, write_local};
use cl_std::value::account::PublicKey;
use cl_std::value::U512;

const TRANSFER_AMOUNT: u32 = 100_000;

/// Executes token transfer to supplied public key.
/// Transfers 100_000 tokens every time.
///
/// Revert status codes:
/// 1 - requested transfer to already funded public key.
/// 2 - transfer error. (In the future it will have more granular statuses).
#[no_mangle]
pub extern "C" fn call() {
    let public_key: PublicKey = get_arg(0);
    // Maybe we will decide to allow multiple funds up until some maximum value.
    let already_funded = read_local::<PublicKey, U512>(public_key).is_some();
    if already_funded {
        revert(1);
    } else {
        let u512_tokens = U512::from(TRANSFER_AMOUNT);
        match transfer_to_account(public_key, U512::from(u512_tokens)) {
            TransferResult::TransferError => revert(2),
            _ => {
                // Transfer successful; Store the fact of funding in the local state.
                write_local(public_key, u512_tokens);
            }
        }
    }
}
