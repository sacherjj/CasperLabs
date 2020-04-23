#![no_std]
#![no_main]

#[macro_use]
extern crate alloc;

use alloc::{
    boxed::Box,
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
    AccessRights, ApiError, CLType, CLValue, Key, SemVer, URef,
};

const COUNT_KEY: &str = "count";
const COUNTER_INCREMENT: &str = "counter_increment";
const COUNTER_KEY: &str = "counter";
const COUNTER_INC_KEY: &str = "counter_inc";
const GET_METHOD: &str = "get";
const INC_METHOD: &str = "increment";
const CREATE_COUNTER_METHOD: &str = "create_counter";
const FACTORY_KEY: &str = "counter_factory";
const FACTORY_ACCESS: &str = "counter_factory_access";
const FACTORY_SESSION: &str = "factory_session";
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

/// function which creates counter contracts
#[no_mangle]
pub extern "C" fn create_counter() -> ! {
    let (contract_pointer, access_key) = storage::create_contract_metadata_at_hash();
    let contract_key: Key = contract_pointer.clone().into();

    let mut methods = BTreeMap::new();
    methods.insert(
        String::from(GET_METHOD),
        EntryPoint::new(
            Vec::new(),
            CLType::I32,
            EntryPointAccess::Public,
            EntryPointType::Contract,
        ),
    );
    methods.insert(
        String::from(INC_METHOD),
        EntryPoint::new(
            vec![Arg::new(String::from("step"), CLType::I32)],
            CLType::Unit,
            EntryPointAccess::Public,
            EntryPointType::Contract,
        ),
    );

    let counter_variable = storage::new_uref(0); //initialize counter

    //create map of references for stored contract
    let mut counter_named_keys: BTreeMap<String, Key> = BTreeMap::new();
    let key_name = String::from(COUNT_KEY);
    counter_named_keys.insert(key_name, counter_variable.into());

    let _ = storage::add_contract_version(
        contract_pointer,
        access_key.clone(),
        VERSION,
        methods,
        counter_named_keys,
    )
    .unwrap_or_revert();

    // TODO: it should return the access key too, but that would require extracting urefs from a
    // tuple3 (at least until we stop returning the counter variable uref, i.e. fix query for
    // versioned contracts)
    let return_value = CLValue::from_t((
        contract_key,
        counter_variable.with_access_rights(AccessRights::READ),
    ))
    .unwrap_or_revert();
    runtime::ret(return_value);
}

/// Calls create counter function, then stores the contract reference and access key (meant to be
/// run as session code).
#[no_mangle]
pub extern "C" fn factory_session() {
    let factory_key = runtime::get_arg(0)
        .map(|arg| arg.unwrap_or_revert_with(ApiError::InvalidArgument))
        .unwrap_or_else(|| runtime::get_key(FACTORY_KEY).unwrap_or_revert_with(ApiError::GetKey));

    let contract_ref = factory_key
        .to_contract_ref()
        .unwrap_or_revert_with(ApiError::UnexpectedKeyVariant);

    let (counter_contract, count_var): (Key, URef) =
        runtime::call_versioned_contract(contract_ref, VERSION, CREATE_COUNTER_METHOD, ());

    runtime::put_key(COUNTER_KEY, counter_contract);
    // store counter variable in account too for easier query
    runtime::put_key(COUNT_KEY, count_var.into());
}

/// main session code which stores the factory contract and convenience session codes
#[no_mangle]
pub extern "C" fn call() {
    // create new versioned contract
    let (contract_pointer, access_key) = storage::create_contract_metadata_at_hash();
    runtime::put_key(FACTORY_KEY, contract_pointer.clone().into());
    runtime::put_key(FACTORY_ACCESS, access_key.into());

    // define contract header
    let mut entrypoints = BTreeMap::new();

    let entrypoint_get = EntryPoint::new(
        Vec::new(),
        CLType::I32,
        // These first two methods need to be stored so they are
        // available to the factory. But they are not supposed to be
        // called from here, only from the contracts that the
        // factory produces, so we mark them as being callable by no
        // security groups.
        EntryPointAccess::Groups(Vec::new()),
        EntryPointType::Contract,
    );

    entrypoints.insert(GET_METHOD.to_string(), entrypoint_get);

    let entrypoint_inc = EntryPoint::new(
        vec![Arg::new(String::from("step"), CLType::I32)],
        CLType::Unit,
        EntryPointAccess::Groups(vec![]),
        EntryPointType::Contract,
    );

    entrypoints.insert(INC_METHOD.to_string(), entrypoint_inc);

    let entrypoint_create_counter = EntryPoint::new(
        Vec::new(),
        // Returns the key for the new contract, and
        // a read-only uref for the counter variable itself (for
        // query convenience until querying versioned contracts is
        // possible).
        CLType::Tuple2([Box::new(CLType::Key), Box::new(CLType::URef)]),
        // This method is public so anyone can create counters
        EntryPointAccess::Public,
        // This method is called from within accounts's context
        EntryPointType::Session,
    );

    entrypoints.insert(CREATE_COUNTER_METHOD.to_string(), entrypoint_create_counter);

    // push version 1.0.0 to the contract
    storage::add_contract_version(
        contract_pointer,
        access_key,
        VERSION,
        entrypoints,
        BTreeMap::new(),
    )
    .unwrap_or_revert();

    // store counter increment convenience session code
    let inc_pointer = storage::store_function_at_hash(COUNTER_INCREMENT, Default::default());
    runtime::put_key(COUNTER_INC_KEY, inc_pointer.into());

    // store factory convenience session code
    let factory_pointer = storage::store_function_at_hash(FACTORY_SESSION, Default::default());
    runtime::put_key(FACTORY_SESSION, factory_pointer.into());
}
