#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use alloc::{
    collections::BTreeMap,
    string::{String, ToString},
};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, Arg, CLType, CLValue, EntryPoint, EntryPointAccess, EntryPointType, SemVer};

const HELLO_NAME_METADATA_KEY: &str = "hello_name_metadata";
const HELLO_NAME_EXT: &str = "hello_name_ext";
const ARG_NAME: &str = "name";

fn hello_name(name: &str) -> String {
    let mut result = String::from("Hello, ");
    result.push_str(name);
    result
}

#[no_mangle]
pub extern "C" fn hello_name_ext() {
    let name: String = runtime::get_named_arg(ARG_NAME)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let return_value = CLValue::from_t(hello_name(&name)).unwrap_or_revert();
    runtime::ret(return_value);
}

#[no_mangle]
pub extern "C" fn call() {
    let (metadata_hash, access_uref) = storage::create_contract_metadata_at_hash();
    runtime::put_key(HELLO_NAME_METADATA_KEY, metadata_hash);

    let methods = {
        let mut methods = BTreeMap::new();
        let entrypoint_hello = EntryPoint::new(
            vec![Arg::new(ARG_NAME, CLType::String)],
            CLType::String,
            EntryPointAccess::Public,
            EntryPointType::Contract,
        );
        methods.insert(HELLO_NAME_EXT.to_string(), entrypoint_hello);
        methods
    };

    storage::add_contract_version(
        metadata_hash,
        access_uref,
        SemVer::V1_0_0,
        methods,
        BTreeMap::new(),
    )
    .unwrap_or_revert();
}
