#![no_std]
#![no_main]

use contract::contract_api::{runtime, system};
use types::{account::PublicKey, ApiError, U512};

const ARG_AMOUNT: &str = "amount";

#[no_mangle]
pub extern "C" fn call() {
    let amount: U512 = runtime::get_named_arg(ARG_AMOUNT);
    let public_key = PublicKey::ed25519_from([42; 32]);
    let result = system::transfer_to_account(public_key, amount);
    assert_eq!(result, Err(ApiError::Transfer))
}
