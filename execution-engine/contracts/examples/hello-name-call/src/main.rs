#![no_std]
#![no_main]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{runtime_args, ApiError, RuntimeArgs, SemVer};

const HELLO_NAME_METADATA_KEY: &str = "hello_name_metadata";
const HELLOWORLD_KEY: &str = "helloworld";
const HELLO_ENTRYPOINT: &str = "hello";
const HELLO_ARG: &str = "name";

#[no_mangle]
pub extern "C" fn call() {
    let contract_key =
        runtime::get_key(HELLO_NAME_METADATA_KEY).unwrap_or_revert_with(ApiError::GetKey);
    let contract_ref = contract_key
        .to_contract_ref()
        .unwrap_or_revert_with(ApiError::UnexpectedKeyVariant);

    let args = runtime_args! {
        HELLO_ARG => "World",
    };

    let result: String =
        runtime::call_versioned_contract(contract_ref, SemVer::V1_0_0, HELLO_ENTRYPOINT, args);
    assert_eq!("Hello, World", result);

    // Store the result at a uref so it can be seen as an effect on the global state
    runtime::put_key(HELLOWORLD_KEY, storage::new_uref(result).into());
}
