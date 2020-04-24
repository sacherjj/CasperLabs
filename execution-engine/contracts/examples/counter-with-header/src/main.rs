#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use alloc::{
    collections::BTreeMap,
    string::{String, ToString},
    vec::Vec,
};
use core::convert::TryInto;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    contract_header::{Arg, EntryPoint, EntryPointAccess, EntryPointType},
    runtime_args, ApiError, CLType, CLValue, Key, RuntimeArgs, SemVer, URef,
};

const COUNT_KEY: &str = "count";
const COUNTER_ACCESS: &str = "counter_access";
const COUNTER_KEY: &str = "counter";
const COUNTER_CALLER: &str = "counter_caller";
const COUNTER_REMOVER: &str = "counter_remover";
const GET_METHOD: &str = "get";
const INC_METHOD: &str = "increment";
const VERSION: SemVer = SemVer {
    major: 1,
    minor: 0,
    patch: 0,
};

fn get_counter_variable() -> URef {
    runtime::get_key(COUNT_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap()
}

/// increment method for counter
#[no_mangle]
pub extern "C" fn increment() {
    let counter_variable = get_counter_variable();

    let step: i32 = runtime::get_named_arg("step")
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    storage::add(counter_variable, step);
}

/// get method for couner
#[no_mangle]
pub extern "C" fn get() -> ! {
    let counter_variable = get_counter_variable();
    let result: i32 = storage::read_or_revert(counter_variable);
    let result_value = CLValue::from_t(result).unwrap_or_revert();
    runtime::ret(result_value);
}

/// convenience stored session code for incrementing counter by 5
#[no_mangle]
pub extern "C" fn counter_caller() {
    // This function will call the stored counter contract (defined above) and increment it.
    // It is stored in `call` below so that it can be called directly by the client
    // (without needing to send any further wasm).
    let counter_key = runtime::get_named_arg("metadata_key")
        .map(|arg| arg.unwrap_or_revert_with(ApiError::InvalidArgument))
        .unwrap_or_else(|| runtime::get_key(COUNTER_KEY).unwrap_or_revert_with(ApiError::GetKey));

    let contract_ref = counter_key
        .to_contract_ref()
        .unwrap_or_revert_with(ApiError::UnexpectedKeyVariant);

    let step: i32 = runtime::get_named_arg("step")
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let args = runtime_args! {
        "step" => step,
    };

    runtime::call_versioned_contract::<()>(contract_ref, VERSION, INC_METHOD, args);
}

/// convenience session code to remove contract version
#[no_mangle]
pub extern "C" fn counter_remover() {
    let counter_metadata_key =
        runtime::get_key(COUNTER_KEY).unwrap_or_revert_with(ApiError::GetKey);
    let counter_access_key = runtime::get_key(COUNTER_ACCESS)
        .unwrap_or_revert_with(ApiError::GetKey)
        .as_uref()
        .cloned()
        .unwrap_or_revert();
    let counter_metadata_ref = counter_metadata_key.to_contract_ref().unwrap();
    storage::remove_contract_version(
        counter_metadata_ref,
        counter_access_key,
        SemVer::new(1, 0, 0),
    )
    .unwrap_or_revert();
}

/// main session code which stores the contract and convenience session code
#[no_mangle]
pub extern "C" fn call() {
    let (contract_hash, access_uref) = storage::create_contract_metadata_at_hash();
    runtime::put_key(COUNTER_KEY, contract_hash);
    runtime::put_key(COUNTER_ACCESS, access_uref.into());

    let mut methods = BTreeMap::new();

    let entrypoint_get = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        EntryPointAccess::Public,
        EntryPointType::Contract,
    );
    methods.insert(GET_METHOD.to_string(), entrypoint_get);

    let entrypoint_inc = EntryPoint::new(
        vec![Arg::new(String::from("step"), CLType::I32)],
        CLType::Unit,
        EntryPointAccess::Public,
        EntryPointType::Contract,
    );
    methods.insert(INC_METHOD.to_string(), entrypoint_inc);

    let entrypoint_caller = EntryPoint::new(
        vec![],
        CLType::Unit,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    methods.insert(COUNTER_CALLER.to_string(), entrypoint_caller);

    let entrypoint_caller = EntryPoint::new(
        vec![],
        CLType::Unit,
        EntryPointAccess::Public,
        EntryPointType::Session,
    );
    methods.insert(COUNTER_REMOVER.to_string(), entrypoint_caller);

    let counter_variable = storage::new_uref(0); //initialize counter

    //create map of references for stored contract
    let mut counter_named_keys: BTreeMap<String, Key> = BTreeMap::new();
    counter_named_keys.insert(COUNT_KEY.to_string(), counter_variable.into());

    storage::add_contract_version(
        contract_hash,
        access_uref,
        VERSION,
        methods,
        counter_named_keys,
    )
    .unwrap_or_revert();
}
