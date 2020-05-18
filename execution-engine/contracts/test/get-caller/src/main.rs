#![no_std]
#![no_main]

use contract::contract_api::runtime;
use types::account::PublicKey;

const ARG_ACCOUNT: &str = "account";

#[no_mangle]
pub extern "C" fn call() {
    let known_public_key: PublicKey = runtime::get_named_arg(ARG_ACCOUNT);
    let caller_public_key: PublicKey = runtime::get_caller();
    assert_eq!(
        caller_public_key, known_public_key,
        "caller public key was not known public key"
    );
}
