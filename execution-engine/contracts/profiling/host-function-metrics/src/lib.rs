#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String, vec::Vec};

use rand::{distributions::Alphanumeric, rngs::SmallRng, Rng, SeedableRng};

use contract::{
    contract_api::{account, runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{ActionType, PublicKey, Weight},
    ApiError, BlockTime, CLValue, Key, Phase, U512,
};

const LARGE_FUNCTION: &str = "large_function";
const SMALL_FUNCTION: &str = "small_function";

const NAMED_KEY_COUNT: usize = 10;
const MIN_NAMED_KEY_NAME_LENGTH: usize = 10;
// TODO - consider increasing to e.g. 1_000 once https://casperlabs.atlassian.net/browse/EE-966 is
//        resolved.
const MAX_NAMED_KEY_NAME_LENGTH: usize = 100;
const VALUE_FOR_ADDITION_1: u64 = 1;
const VALUE_FOR_ADDITION_2: u64 = 2;
const TRANSFER_AMOUNT: u64 = 1_000_000;

enum Arg {
    Seed = 0,
    Others = 1,
}

#[repr(u16)]
enum Error {
    GetCaller = 0,
    GetBlockTime = 1,
    GetPhase = 2,
    HasKey = 3,
    GetKey = 4,
    NamedKeys = 5,
    ReadOrRevert = 6,
    ReadLocal = 7,
    IsValidURef = 8,
    Transfer = 9,
    Revert = 10,
}

impl From<Error> for ApiError {
    fn from(error: Error) -> ApiError {
        ApiError::User(error as u16)
    }
}

fn create_random_names(seed: u64) -> impl Iterator<Item = String> {
    let mut names = Vec::new();
    let mut rng = SmallRng::seed_from_u64(seed);
    for _ in 0..NAMED_KEY_COUNT {
        let key_length: usize = rng.gen_range(MIN_NAMED_KEY_NAME_LENGTH, MAX_NAMED_KEY_NAME_LENGTH);
        let key_name = (&mut rng)
            .sample_iter(&Alphanumeric)
            .take(key_length)
            .collect::<String>();
        names.push(key_name);
    }
    names.into_iter()
}

// Executes the named key functions from the `runtime` module and most of the functions from the
// `storage` module.
#[no_mangle]
pub extern "C" fn large_function() {
    let seed: u64 = runtime::get_arg(Arg::Seed as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let random_bytes: Vec<u8> = runtime::get_arg(Arg::Others as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    let uref = storage::new_uref(random_bytes.clone());

    let mut key_name = String::new();
    for random_name in create_random_names(seed) {
        key_name = random_name;
        runtime::put_key(&key_name, Key::from(uref));
    }

    if !runtime::has_key(&key_name) {
        runtime::revert(Error::HasKey);
    }

    if runtime::get_key(&key_name) != Some(Key::from(uref)) {
        runtime::revert(Error::GetKey);
    }

    runtime::remove_key(&key_name);

    let named_keys = runtime::list_named_keys();
    if named_keys.len() != NAMED_KEY_COUNT - 1 {
        runtime::revert(Error::NamedKeys)
    }

    storage::write(uref, random_bytes.clone());
    let retrieved_value: Vec<u8> = storage::read_or_revert(uref);
    if retrieved_value != random_bytes {
        runtime::revert(Error::ReadOrRevert);
    }

    storage::write(uref, VALUE_FOR_ADDITION_1);
    storage::add(uref, VALUE_FOR_ADDITION_2);

    storage::write_local(key_name.clone(), random_bytes.clone());
    let retrieved_value = storage::read_local(&key_name);
    if retrieved_value != Ok(Some(random_bytes)) {
        runtime::revert(Error::ReadLocal);
    }

    storage::write_local(key_name.clone(), VALUE_FOR_ADDITION_1);
    storage::add_local(key_name, VALUE_FOR_ADDITION_2);

    runtime::ret(CLValue::from_t(named_keys).unwrap_or_revert());
}

#[no_mangle]
pub extern "C" fn small_function() {
    if runtime::get_phase() != Phase::Session {
        runtime::revert(Error::GetPhase);
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let seed: u64 = runtime::get_arg(Arg::Seed as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let (random_bytes, source_account, destination_account): (Vec<u8>, PublicKey, PublicKey) =
        runtime::get_arg(Arg::Others as u32)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);

    // ========== storage, execution and upgrading of contracts ====================================

    // Store large function with no named keys under a URef, then execute it to get named keys
    // returned.
    let mut contract_ref = storage::store_function(LARGE_FUNCTION, BTreeMap::new());

    let named_keys: BTreeMap<String, Key> =
        runtime::call_contract(contract_ref.clone(), (seed, random_bytes.clone()));

    runtime::upgrade_contract_at_uref(LARGE_FUNCTION, contract_ref.into_uref().unwrap_or_revert());

    // Store large function with 10 named keys under a URef, then execute it.
    contract_ref = storage::store_function(LARGE_FUNCTION, named_keys.clone());
    let _ = runtime::call_contract::<_, BTreeMap<String, Key>>(
        contract_ref.clone(),
        (seed, random_bytes.clone()),
    );
    runtime::upgrade_contract_at_uref(SMALL_FUNCTION, contract_ref.into_uref().unwrap_or_revert());

    // Store small function with no named keys under a URef, then execute it.
    contract_ref = storage::store_function(SMALL_FUNCTION, BTreeMap::new());
    runtime::call_contract::<_, ()>(contract_ref.clone(), ());
    runtime::upgrade_contract_at_uref(LARGE_FUNCTION, contract_ref.into_uref().unwrap_or_revert());

    // Store small function with 10 named keys under a URef, then execute it.
    contract_ref = storage::store_function(SMALL_FUNCTION, named_keys.clone());
    runtime::call_contract::<_, ()>(contract_ref.clone(), ());
    runtime::upgrade_contract_at_uref(SMALL_FUNCTION, contract_ref.into_uref().unwrap_or_revert());

    // Store same functions and keys combinations under a hash, but no need to execute any.
    let _ = storage::store_function_at_hash(LARGE_FUNCTION, BTreeMap::new());
    let _ = storage::store_function_at_hash(LARGE_FUNCTION, named_keys.clone());
    let _ = storage::store_function_at_hash(SMALL_FUNCTION, BTreeMap::new());
    let _ = storage::store_function_at_hash(SMALL_FUNCTION, named_keys);

    // ========== functions from `account` module ==================================================

    let main_purse = account::get_main_purse();
    account::set_action_threshold(ActionType::Deployment, Weight::new(1)).unwrap_or_revert();
    account::add_associated_key(destination_account, Weight::new(1)).unwrap_or_revert();
    account::update_associated_key(destination_account, Weight::new(1)).unwrap_or_revert();
    account::remove_associated_key(destination_account).unwrap_or_revert();

    // ========== functions from `system` module ===================================================

    let _ = system::get_mint();

    let new_purse = system::create_purse();

    let transfer_amount = U512::from(TRANSFER_AMOUNT);
    system::transfer_from_purse_to_purse(main_purse, new_purse, transfer_amount).unwrap_or_revert();

    let balance = system::get_balance(new_purse).unwrap_or_revert();
    if balance != transfer_amount {
        runtime::revert(Error::Transfer);
    }

    system::transfer_from_purse_to_account(new_purse, destination_account, transfer_amount)
        .unwrap_or_revert();

    system::transfer_to_account(destination_account, transfer_amount).unwrap_or_revert();

    // ========== remaining functions from `runtime` module ========================================

    if !runtime::is_valid_uref(main_purse) {
        runtime::revert(Error::IsValidURef);
    }

    if runtime::get_blocktime() != BlockTime::new(0) {
        runtime::revert(Error::GetBlockTime);
    }

    if runtime::get_caller() != source_account {
        runtime::revert(Error::GetCaller);
    }

    runtime::print(&String::from_utf8_lossy(&random_bytes));

    runtime::revert(Error::Revert);
}
