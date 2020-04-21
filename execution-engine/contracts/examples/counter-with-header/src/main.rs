#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use alloc::{collections::BTreeMap, string::String, vec::Vec};
use core::convert::TryInto;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    contract_header::{Arg, ContractHeader, EntryPoint, EntryPointAccess},
    ApiError, CLType, CLValue, Key, ProtocolVersion, SemVer, URef,
};

const COUNT_KEY: &str = "count";
const COUNTER_ACCESS: &str = "counter_access";
const COUNTER_INCREMENT: &str = "counter_increment";
const COUNTER_KEY: &str = "counter";
const COUNTER_INC_KEY: &str = "counter_inc";
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

    let step: i32 = runtime::get_arg(0)
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
pub extern "C" fn counter_increment() {
    // This function will call the stored counter contract (defined above) and increment it.
    // It is stored in `call` below so that it can be called directly by the client
    // (without needing to send any further wasm).
    let counter_key = runtime::get_arg(0)
        .map(|arg| arg.unwrap_or_revert_with(ApiError::InvalidArgument))
        .unwrap_or_else(|| runtime::get_key(COUNTER_KEY).unwrap_or_revert_with(ApiError::GetKey));

    let contract_ref = counter_key
        .to_contract_ref()
        .unwrap_or_revert_with(ApiError::UnexpectedKeyVariant);

    let args = (5,);
    let _: () = runtime::call_versioned_contract(contract_ref, VERSION, INC_METHOD, args);
}

/// main session code which stores the contract and convenience session code
#[no_mangle]
pub extern "C" fn call() {
    let (contract_pointer, access_key) = storage::create_contract_at_hash();
    runtime::put_key(COUNTER_KEY, contract_pointer.clone().into());
    runtime::put_key(COUNTER_ACCESS, access_key.into());

    let header = ContractHeader::new(
        [
            (
                String::from(GET_METHOD),
                EntryPoint::new(EntryPointAccess::public(), Vec::new(), CLType::I32),
            ),
            (
                String::from(INC_METHOD),
                EntryPoint::new(
                    EntryPointAccess::public(),
                    vec![Arg::new(String::from("step"), CLType::I32)],
                    CLType::Unit,
                ),
            ),
        ]
        .iter()
        .cloned()
        .collect(),
        ProtocolVersion::new(SemVer::new(1, 0, 0)),
    );

    let counter_variable = storage::new_uref(0); //initialize counter

    //create map of references for stored contract
    let mut counter_named_keys: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from(COUNT_KEY);
    counter_named_keys.insert(key_name, counter_variable.into());

    let _ = storage::add_contract_version(
        contract_pointer,
        access_key,
        VERSION,
        header,
        counter_named_keys,
    )
    .unwrap_or_revert();

    let inc_pointer = storage::store_function_at_hash(COUNTER_INCREMENT, Default::default());
    runtime::put_key(COUNTER_INC_KEY, inc_pointer.into());
}
