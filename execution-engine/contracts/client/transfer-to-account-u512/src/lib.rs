#![no_std]

use contract::{
    contract_api::{runtime, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{account::PublicKey, U512};

const ARG_TARGET: &str = "target";
const ARG_AMOUNT: &str = "amount";

/// Executes mote transfer to supplied public key.
/// Transfers the requested amount.
#[no_mangle]
pub fn delegate() {
    let public_key: PublicKey = runtime::get_named_arg(ARG_TARGET);
    let transfer_amount: U512 = runtime::get_named_arg(ARG_AMOUNT);
    system::transfer_to_account(public_key, transfer_amount).unwrap_or_revert();
}
