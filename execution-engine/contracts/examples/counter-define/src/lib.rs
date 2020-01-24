#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};
use core::convert::TryInto;

use contract::{
    contract_api::{runtime, storage, TURef},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{ApiError, CLValue, Key};

const COUNT_KEY: &str = "count";
const COUNTER_EXT: &str = "counter_ext";
const COUNTER_INCREMENT: &str = "counter_increment";
const COUNTER_KEY: &str = "counter";
const COUNTER_INC_KEY: &str = "counter_inc";
const GET_METHOD: &str = "get";
const INC_METHOD: &str = "inc";

enum Arg {
    MethodName = 0,
}

#[repr(u16)]
enum Error {
    UnknownMethodName = 0,
}

impl Into<ApiError> for Error {
    fn into(self) -> ApiError {
        ApiError::User(self as u16)
    }
}

#[no_mangle]
pub extern "C" fn counter_ext() {
    let turef: TURef<i32> = runtime::get_key(COUNT_KEY)
        .unwrap_or_revert()
        .try_into()
        .unwrap_or_revert();

    let method_name: String = runtime::get_arg(Arg::MethodName as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    match method_name.as_str() {
        INC_METHOD => storage::add(turef, 1),
        GET_METHOD => {
            let result = storage::read(turef)
                .unwrap_or_revert_with(ApiError::Read)
                .unwrap_or_revert_with(ApiError::ValueNotFound);
            let return_value = CLValue::from_t(result).unwrap_or_revert();
            runtime::ret(return_value);
        }
        _ => runtime::revert(Error::UnknownMethodName),
    }
}

#[no_mangle]
pub extern "C" fn counter_increment() {
    // This function will call the stored counter contract (defined above) and increment it.
    // It is stored in `call` below so that it can be called directly by the client
    // (without needing to send any further wasm).
    let counter_key = runtime::get_key(COUNTER_KEY).unwrap_or_revert_with(ApiError::GetKey);
    let contract_ref = counter_key
        .to_contract_ref()
        .unwrap_or_revert_with(ApiError::UnexpectedKeyVariant);

    let args = (INC_METHOD,);
    runtime::call_contract(contract_ref, args)
}

#[no_mangle]
pub extern "C" fn call() {
    let counter_local_key = storage::new_turef(0); //initialize counter

    //create map of references for stored contract
    let mut counter_urefs: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from(COUNT_KEY);
    counter_urefs.insert(key_name, counter_local_key.into());

    let pointer = storage::store_function_at_hash(COUNTER_EXT, counter_urefs);
    runtime::put_key(COUNTER_KEY, pointer.into());

    let inc_pointer = storage::store_function_at_hash(COUNTER_INCREMENT, Default::default());
    runtime::put_key(COUNTER_INC_KEY, inc_pointer.into());
}
