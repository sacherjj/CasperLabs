#![no_std]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String, vec::Vec};
use core::iter::{self, FromIterator};

use rand::{distributions::Alphanumeric, rngs::SmallRng, Rng, SeedableRng};

use contract::{
    contract_api::{account, runtime, storage, system},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::{ActionType, PublicKey, Weight},
    ApiError, BlockTime, CLValue, Key, Phase, U512,
};

const MIN_FUNCTION_NAME_LENGTH: usize = 1;
const MAX_FUNCTION_NAME_LENGTH: usize = 100;

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
    let mut rng = SmallRng::seed_from_u64(seed);
    iter::repeat_with(move || {
        let key_length: usize = rng.gen_range(MIN_NAMED_KEY_NAME_LENGTH, MAX_NAMED_KEY_NAME_LENGTH);
        (&mut rng)
            .sample_iter(&Alphanumeric)
            .take(key_length)
            .collect::<String>()
    })
    .take(NAMED_KEY_COUNT)
}

// Executes the named key functions from the `runtime` module and most of the functions from the
// `storage` module.
fn large_function() {
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

fn small_function() {
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
    let mut rng = SmallRng::seed_from_u64(seed);
    let large_function_name = String::from_iter(
        iter::repeat('l')
            .take(rng.gen_range(MIN_FUNCTION_NAME_LENGTH, MAX_FUNCTION_NAME_LENGTH + 1)),
    );
    let mut contract_ref = storage::store_function(&large_function_name, BTreeMap::new());

    let named_keys: BTreeMap<String, Key> =
        runtime::call_contract(contract_ref.clone(), (seed, random_bytes.clone()));

    runtime::upgrade_contract_at_uref(
        &large_function_name,
        contract_ref.into_uref().unwrap_or_revert(),
    );

    // Store large function with 10 named keys under a URef, then execute it.
    contract_ref = storage::store_function(&large_function_name, named_keys.clone());
    let _ = runtime::call_contract::<_, BTreeMap<String, Key>>(
        contract_ref.clone(),
        (seed, random_bytes.clone()),
    );
    let small_function_name = String::from_iter(
        iter::repeat('s')
            .take(rng.gen_range(MIN_FUNCTION_NAME_LENGTH, MAX_FUNCTION_NAME_LENGTH + 1)),
    );
    runtime::upgrade_contract_at_uref(
        &small_function_name,
        contract_ref.into_uref().unwrap_or_revert(),
    );

    // Store small function with no named keys under a URef, then execute it.
    contract_ref = storage::store_function(&small_function_name, BTreeMap::new());
    runtime::call_contract::<_, ()>(contract_ref.clone(), ());
    runtime::upgrade_contract_at_uref(
        &large_function_name,
        contract_ref.into_uref().unwrap_or_revert(),
    );

    // Store small function with 10 named keys under a URef, then execute it.
    contract_ref = storage::store_function(&small_function_name, named_keys.clone());
    runtime::call_contract::<_, ()>(contract_ref.clone(), ());
    runtime::upgrade_contract_at_uref(
        &small_function_name,
        contract_ref.into_uref().unwrap_or_revert(),
    );

    // Store same functions and keys combinations under a hash, but no need to execute any.
    let _ = storage::store_function_at_hash(&large_function_name, BTreeMap::new());
    let _ = storage::store_function_at_hash(&large_function_name, named_keys.clone());
    let _ = storage::store_function_at_hash(&small_function_name, BTreeMap::new());
    let _ = storage::store_function_at_hash(&small_function_name, named_keys);

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

#[rustfmt::skip] #[no_mangle] pub extern "C" fn s() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn sssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssssss() { small_function() }

#[rustfmt::skip] #[no_mangle] pub extern "C" fn l() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn ll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn lllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
#[rustfmt::skip] #[no_mangle] pub extern "C" fn llllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllllll() { large_function() }
