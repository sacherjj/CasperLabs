#![no_std]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, ApiError, U512};

const ARG_TARGET: &str = "target";
const ARG_AMOUNT: &str = "amount";

/// Executes mote transfer to supplied public key.
/// Transfers the requested amount.
pub fn delegate() {
    let public_key: PublicKey = runtime::get_named_arg(ARG_TARGET);
    let transfer_amount: u64 = runtime::get_named_arg(ARG_AMOUNT);
    let u512_motes = U512::from(transfer_amount);
    system::transfer_to_account(public_key, u512_motes).unwrap_or_revert();
}
