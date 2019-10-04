mod alloc_util;
pub mod argsparser;
mod error;
pub mod pointers;

use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;
use core::convert::{From, TryFrom, TryInto};
use core::fmt::Debug;

use self::alloc_util::*;
use self::pointers::*;
use crate::bytesrepr::{self, deserialize, FromBytes, ToBytes};
use crate::execution::{Phase, PHASE_SIZE};
use crate::ext_ffi;
use crate::key::{Key, UREF_SIZE};
use crate::uref::{AccessRights, URef};
use crate::value::account::{
    Account, ActionType, AddKeyFailure, BlockTime, PublicKey, PurseId, RemoveKeyFailure,
    SetThresholdFailure, UpdateKeyFailure, Weight, BLOCKTIME_SER_SIZE, PURSE_ID_SIZE_SERIALIZED,
};
use crate::value::{Contract, Value, U512};
use argsparser::ArgsParser;
pub use error::{i32_from, result_from, Error};

pub type TransferResult = Result<TransferredTo, Error>;

const MINT_NAME: &str = "mint";
const POS_NAME: &str = "pos";

/// Read value under the key in the global state
pub fn read<T>(turef: TURef<T>) -> Result<Option<T>, bytesrepr::Error>
where
    T: TryFrom<Value>,
{
    let key: Key = turef.into();
    let maybe_value = read_untyped(&key)?;
    try_into(maybe_value)
}

fn read_untyped(key: &Key) -> Result<Option<Value>, bytesrepr::Error> {
    // Note: _bytes is necessary to keep the Vec<u8> in scope. If _bytes is
    //      dropped then key_ptr becomes invalid.

    let (key_ptr, key_size, _bytes) = to_ptr(key);
    let value_size = unsafe { ext_ffi::read_value(key_ptr, key_size) };
    let value_ptr = alloc_bytes(value_size);
    let value_bytes = unsafe {
        ext_ffi::get_read(value_ptr);
        Vec::from_raw_parts(value_ptr, value_size, value_size)
    };
    deserialize(&value_bytes)
}

/// Reads the value at the given key in the context-local partition of global
/// state
pub fn read_local<K, V>(key: K) -> Result<Option<V>, bytesrepr::Error>
where
    K: ToBytes,
    V: TryFrom<Value>,
{
    let key_bytes = key.to_bytes()?;
    let maybe_value = read_untyped_local(&key_bytes)?;
    try_into(maybe_value)
}

fn read_untyped_local(key_bytes: &[u8]) -> Result<Option<Value>, bytesrepr::Error> {
    let key_bytes_ptr = key_bytes.as_ptr();
    let key_bytes_size = key_bytes.len();
    let value_size = unsafe { ext_ffi::read_value_local(key_bytes_ptr, key_bytes_size) };
    let value_ptr = alloc_bytes(value_size);
    let value_bytes = unsafe {
        ext_ffi::get_read(value_ptr);
        Vec::from_raw_parts(value_ptr, value_size, value_size)
    };
    deserialize(&value_bytes)
}

fn try_into<T>(maybe_value: Option<Value>) -> Result<Option<T>, bytesrepr::Error>
where
    T: TryFrom<Value>,
{
    match maybe_value {
        None => Ok(None),
        Some(value) => value
            .try_into()
            .map(Some)
            .map_err(|_| bytesrepr::Error::custom("T could not be derived from Value")),
    }
}

/// Write the value under the key in the global state
pub fn write<T>(turef: TURef<T>, t: T)
where
    Value: From<T>,
{
    let key = turef.into();
    let value = t.into();
    write_untyped(&key, &value)
}

fn write_untyped(key: &Key, value: &Value) {
    let (key_ptr, key_size, _bytes) = to_ptr(key);
    let (value_ptr, value_size, _bytes2) = to_ptr(value);
    unsafe {
        ext_ffi::write(key_ptr, key_size, value_ptr, value_size);
    }
}

/// Writes the given value at the given key in the context-local partition of
/// global state
pub fn write_local<K, V>(key: K, value: V)
where
    K: ToBytes,
    V: Into<Value>,
{
    let key_bytes = key.to_bytes().unwrap();
    write_untyped_local(&key_bytes, &value.into());
}

fn write_untyped_local(key_bytes: &[u8], value: &Value) {
    let key_bytes_ptr = key_bytes.as_ptr();
    let key_bytes_size = key_bytes.len();
    let (value_ptr, value_size, _bytes2) = to_ptr(value);
    unsafe {
        ext_ffi::write_local(key_bytes_ptr, key_bytes_size, value_ptr, value_size);
    }
}

/// Add the given value to the one currently under the key in the global state
pub fn add<T>(turef: TURef<T>, t: T)
where
    Value: From<T>,
{
    let key = turef.into();
    let value = t.into();
    add_untyped(&key, &value)
}

fn add_untyped(key: &Key, value: &Value) {
    let (key_ptr, key_size, _bytes) = to_ptr(key);
    let (value_ptr, value_size, _bytes2) = to_ptr(value);
    unsafe {
        // Could panic if the value under the key cannot be added to
        // the given value in memory
        ext_ffi::add(key_ptr, key_size, value_ptr, value_size);
    }
}

/// Returns a new unforgable pointer, where value is initialized to `init`
pub fn new_turef<T>(init: T) -> TURef<T>
where
    Value: From<T>,
{
    let key_ptr = alloc_bytes(UREF_SIZE);
    let value: Value = init.into();
    let (value_ptr, value_size, _bytes2) = to_ptr(&value);
    let bytes = unsafe {
        ext_ffi::new_uref(key_ptr, value_ptr, value_size); // new_uref creates a URef with ReadWrite access writes
        Vec::from_raw_parts(key_ptr, UREF_SIZE, UREF_SIZE)
    };
    let key: Key = deserialize(&bytes).unwrap();
    if let Key::URef(uref) = key {
        TURef::from_uref(uref).unwrap()
    } else {
        panic!("URef FFI did not return a valid URef!");
    }
}

pub fn list_named_keys() -> BTreeMap<String, Key> {
    let bytes_size = unsafe { ext_ffi::serialize_named_keys() };
    let dest_ptr = alloc_bytes(bytes_size);
    let bytes = unsafe {
        ext_ffi::list_named_keys(dest_ptr);
        Vec::from_raw_parts(dest_ptr, bytes_size, bytes_size)
    };
    deserialize(&bytes).unwrap()
}

/// Stores the serialized bytes of an exported function under a URef generated by the host.
pub fn store_function(name: &str, named_keys: BTreeMap<String, Key>) -> ContractPointer {
    let (fn_ptr, fn_size, _bytes1) = str_ref_to_ptr(name);
    let (keys_ptr, keys_size, _bytes2) = to_ptr(&named_keys);
    let mut addr = [0u8; 32];
    unsafe {
        ext_ffi::store_function(fn_ptr, fn_size, keys_ptr, keys_size, addr.as_mut_ptr());
    }
    ContractPointer::URef(TURef::<Contract>::new(addr, AccessRights::READ_ADD_WRITE))
}

/// Stores the serialized bytes of an exported function at an immutable address generated by the
/// host.
pub fn store_function_at_hash(name: &str, named_keys: BTreeMap<String, Key>) -> ContractPointer {
    let (fn_ptr, fn_size, _bytes1) = str_ref_to_ptr(name);
    let (keys_ptr, keys_size, _bytes2) = to_ptr(&named_keys);
    let mut addr = [0u8; 32];
    unsafe {
        ext_ffi::store_function_at_hash(fn_ptr, fn_size, keys_ptr, keys_size, addr.as_mut_ptr());
    }
    ContractPointer::Hash(addr)
}

fn load_arg(index: u32) -> Option<usize> {
    let arg_size = unsafe { ext_ffi::load_arg(index) };
    if arg_size >= 0 {
        Some(arg_size as usize)
    } else {
        None
    }
}

/// Return the i-th argument passed to the host for the current module
/// invocation. Note that this is only relevant to contracts stored on-chain
/// since a contract deployed directly is not invoked with any arguments.
pub fn get_arg<T: FromBytes>(i: u32) -> Option<Result<T, bytesrepr::Error>> {
    let arg_size = load_arg(i)?;
    let arg_bytes = {
        let dest_ptr = alloc_bytes(arg_size);
        unsafe {
            ext_ffi::get_arg(dest_ptr);
            Vec::from_raw_parts(dest_ptr, arg_size, arg_size)
        }
    };
    Some(deserialize(&arg_bytes))
}

/// Return the unforgable reference known by the current module under the given
/// name. This either comes from the named_keys of the account or contract,
/// depending on whether the current module is a sub-call or not.
pub fn get_key(name: &str) -> Option<Key> {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    let key_size = unsafe { ext_ffi::get_key(name_ptr, name_size) };
    let dest_ptr = alloc_bytes(key_size);
    let key_bytes = unsafe {
        // TODO: unify FFIs that just copy from the host buffer
        // https://casperlabs.atlassian.net/browse/EE-426
        ext_ffi::get_arg(dest_ptr);
        Vec::from_raw_parts(dest_ptr, key_size, key_size)
    };
    // TODO: better error handling (i.e. pass the `Result` on)
    deserialize(&key_bytes).unwrap()
}

/// Check if the given name corresponds to a known unforgable reference
pub fn has_key(name: &str) -> bool {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    let result = unsafe { ext_ffi::has_key(name_ptr, name_size) };
    result == 0
}

/// Put the given key to the named_keys map under the given name
pub fn put_key(name: &str, key: &Key) {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    let (key_ptr, key_size, _bytes2) = to_ptr(key);
    unsafe { ext_ffi::put_key(name_ptr, name_size, key_ptr, key_size) };
}

/// Removes Key persisted under [name] in the current context's map.
pub fn remove_key(name: &str) {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    unsafe { ext_ffi::remove_key(name_ptr, name_size) }
}

/// Returns caller of current context.
/// When in root context (not in the sub call) - returns None.
/// When in the sub call - returns public key of the account that made the
/// deploy.
pub fn get_caller() -> PublicKey {
    //  TODO: Once `PUBLIC_KEY_SIZE` is fixed, replace 36 with it.
    let dest_ptr = alloc_bytes(36);
    unsafe { ext_ffi::get_caller(dest_ptr) };
    let bytes = unsafe { Vec::from_raw_parts(dest_ptr, 36, 36) };
    deserialize(&bytes).unwrap()
}

pub fn get_blocktime() -> BlockTime {
    let dest_ptr = alloc_bytes(BLOCKTIME_SER_SIZE);
    let bytes = unsafe {
        ext_ffi::get_blocktime(dest_ptr);
        Vec::from_raw_parts(dest_ptr, BLOCKTIME_SER_SIZE, BLOCKTIME_SER_SIZE)
    };
    deserialize(&bytes).unwrap()
}

/// Return `t` to the host, terminating the currently running module.
/// Note this function is only relevant to contracts stored on chain which
/// return a value to their caller. The return value of a directly deployed
/// contract is never looked at.
#[allow(clippy::ptr_arg)]
pub fn ret<T: ToBytes>(t: &T, extra_urefs: &Vec<URef>) -> ! {
    let (ptr, size, _bytes) = to_ptr(t);
    let (urefs_ptr, urefs_size, _bytes2) = to_ptr(extra_urefs);
    unsafe {
        ext_ffi::ret(ptr, size, urefs_ptr, urefs_size);
    }
}

/// Call the given contract, passing the given (serialized) arguments to
/// the host in order to have them available to the called contract during its
/// execution. The value returned from the contract call (see `ret` above) is
/// returned from this function.
#[allow(clippy::ptr_arg)]
pub fn call_contract<A: ArgsParser, T: FromBytes>(
    c_ptr: ContractPointer,
    args: &A,
    extra_urefs: &Vec<Key>,
) -> T {
    let contract_key: Key = c_ptr.into();
    let (key_ptr, key_size, _bytes1) = to_ptr(&contract_key);
    let (args_ptr, args_size, _bytes2) = ArgsParser::parse(args).map(|args| to_ptr(&args)).unwrap();
    let (urefs_ptr, urefs_size, _bytes3) = to_ptr(extra_urefs);
    let res_size = unsafe {
        ext_ffi::call_contract(
            key_ptr, key_size, args_ptr, args_size, urefs_ptr, urefs_size,
        )
    };
    let res_ptr = alloc_bytes(res_size);
    let res_bytes = unsafe {
        ext_ffi::get_call_result(res_ptr);
        Vec::from_raw_parts(res_ptr, res_size, res_size)
    };
    deserialize(&res_bytes).unwrap()
}

/// Stops execution of a contract and reverts execution effects with a given reason.
pub fn revert_with_error<T: Into<Error>>(error: T) -> ! {
    unsafe {
        ext_ffi::revert(error.into().into());
    }
}

/// Stops execution of a contract and reverts execution effects
/// with a given reason.
pub fn revert(status: u32) -> ! {
    unsafe {
        ext_ffi::revert(status);
    }
}

/// Checks if all the keys contained in the given `Value`
/// (rather, thing that can be turned into a `Value`) are
/// valid, in the sense that all of the urefs (and their access rights)
/// are known in the current context.
#[allow(clippy::ptr_arg)]
pub fn is_valid<T: Into<Value>>(t: T) -> bool {
    let value = t.into();
    let (value_ptr, value_size, _bytes) = to_ptr(&value);
    let result = unsafe { ext_ffi::is_valid(value_ptr, value_size) };
    result != 0
}

/// Adds a public key with associated weight to an account.
pub fn add_associated_key(public_key: PublicKey, weight: Weight) -> Result<(), AddKeyFailure> {
    let (public_key_ptr, _public_key_size, _bytes) = to_ptr(&public_key);
    // Cast of u8 (weight) into i32 is assumed to be always safe
    let result = unsafe { ext_ffi::add_associated_key(public_key_ptr, weight.value().into()) };
    // Translates FFI
    match result {
        d if d == 0 => Ok(()),
        d => Err(AddKeyFailure::try_from(d).expect("invalid result")),
    }
}

/// Removes a public key from associated keys on an account
pub fn remove_associated_key(public_key: PublicKey) -> Result<(), RemoveKeyFailure> {
    let (public_key_ptr, _public_key_size, _bytes) = to_ptr(&public_key);
    let result = unsafe { ext_ffi::remove_associated_key(public_key_ptr) };
    match result {
        d if d == 0 => Ok(()),
        d => Err(RemoveKeyFailure::try_from(d).expect("invalid result")),
    }
}

/// Updates the value stored under a public key associated with an account
pub fn update_associated_key(
    public_key: PublicKey,
    weight: Weight,
) -> Result<(), UpdateKeyFailure> {
    let (public_key_ptr, _public_key_size, _bytes) = to_ptr(&public_key);
    // Cast of u8 (weight) into i32 is assumed to be always safe
    let result = unsafe { ext_ffi::update_associated_key(public_key_ptr, weight.value().into()) };
    // Translates FFI
    match result {
        d if d == 0 => Ok(()),
        d => Err(UpdateKeyFailure::try_from(d).expect("invalid result")),
    }
}

pub fn set_action_threshold(
    permission_level: ActionType,
    threshold: Weight,
) -> Result<(), SetThresholdFailure> {
    let permission_level = permission_level as u32;
    let threshold = threshold.value().into();
    let result = unsafe { ext_ffi::set_action_threshold(permission_level, threshold) };
    match result {
        d if d == 0 => Ok(()),
        d => Err(SetThresholdFailure::try_from(d).expect("invalid result")),
    }
}

pub fn create_purse() -> PurseId {
    let purse_id_ptr = alloc_bytes(PURSE_ID_SIZE_SERIALIZED);
    unsafe {
        let ret = ext_ffi::create_purse(purse_id_ptr, PURSE_ID_SIZE_SERIALIZED);
        if ret == 0 {
            let bytes = Vec::from_raw_parts(
                purse_id_ptr,
                PURSE_ID_SIZE_SERIALIZED,
                PURSE_ID_SIZE_SERIALIZED,
            );
            deserialize(&bytes).unwrap()
        } else {
            panic!("could not create purse_id")
        }
    }
}

/// Gets the balance of a given purse
pub fn get_balance(purse_id: PurseId) -> Option<U512> {
    let (purse_id_ptr, purse_id_size, _bytes) = to_ptr(&purse_id);

    let balance_bytes: Vec<u8> = unsafe {
        let value_size = ext_ffi::get_balance(purse_id_ptr, purse_id_size) as usize;
        if value_size == 0 {
            return None;
        }
        let dest_ptr = alloc_bytes(value_size);
        ext_ffi::get_read(dest_ptr);
        Vec::from_raw_parts(dest_ptr, value_size, value_size)
    };

    let balance: U512 =
        deserialize(&balance_bytes).unwrap_or_else(|_| revert(Error::Deserialize.into()));

    Some(balance)
}

pub fn main_purse() -> PurseId {
    // TODO: this could be more efficient, bringing the entire account
    // object across the host/wasm boundary only to use 32 bytes of
    // its data is pretty bad. A native FFI (as opposed to a library
    // API) would get around this problem. However, this solution
    // works for the time being.
    // https://casperlabs.atlassian.net/browse/EE-439
    let account_pk = get_caller();
    let key = Key::Account(account_pk.value());
    let account: Account = read_untyped(&key).unwrap().unwrap().try_into().unwrap();
    account.purse_id()
}

#[derive(Debug, Copy, Clone, PartialEq, Eq)]
#[repr(i32)]
pub enum TransferredTo {
    ExistingAccount = 0,
    NewAccount = 1,
}

impl TransferredTo {
    fn result_from(value: i32) -> TransferResult {
        match value {
            x if x == TransferredTo::ExistingAccount as i32 => Ok(TransferredTo::ExistingAccount),
            x if x == TransferredTo::NewAccount as i32 => Ok(TransferredTo::NewAccount),
            _ => Err(Error::Transfer),
        }
    }

    pub fn i32_from(result: TransferResult) -> i32 {
        match result {
            Ok(transferred_to) => transferred_to as i32,
            Err(_) => 2,
        }
    }
}

/// Transfers `amount` of motes from default purse of the account to `target`
/// account. If `target` does not exist it will create it.
pub fn transfer_to_account(target: PublicKey, amount: U512) -> TransferResult {
    let (target_ptr, target_size, _bytes) = to_ptr(&target);
    let (amount_ptr, amount_size, _bytes) = to_ptr(&amount);
    let return_code =
        unsafe { ext_ffi::transfer_to_account(target_ptr, target_size, amount_ptr, amount_size) };
    TransferredTo::result_from(return_code)
}

/// Transfers `amount` of motes from `source` purse to `target` account.
/// If `target` does not exist it will create it.
pub fn transfer_from_purse_to_account(
    source: PurseId,
    target: PublicKey,
    amount: U512,
) -> TransferResult {
    let (source_ptr, source_size, _bytes) = to_ptr(&source);
    let (target_ptr, target_size, _bytes) = to_ptr(&target);
    let (amount_ptr, amount_size, _bytes) = to_ptr(&amount);
    let return_code = unsafe {
        ext_ffi::transfer_from_purse_to_account(
            source_ptr,
            source_size,
            target_ptr,
            target_size,
            amount_ptr,
            amount_size,
        )
    };
    TransferredTo::result_from(return_code)
}

/// Transfers `amount` of motes from `source` purse to `target` purse.
pub fn transfer_from_purse_to_purse(
    source: PurseId,
    target: PurseId,
    amount: U512,
) -> Result<(), Error> {
    let (source_ptr, source_size, _bytes) = to_ptr(&source);
    let (target_ptr, target_size, _bytes) = to_ptr(&target);
    let (amount_ptr, amount_size, _bytes) = to_ptr(&amount);
    let result = unsafe {
        ext_ffi::transfer_from_purse_to_purse(
            source_ptr,
            source_size,
            target_ptr,
            target_size,
            amount_ptr,
            amount_size,
        )
    };
    if result == 0 {
        Ok(())
    } else {
        Err(Error::Transfer)
    }
}

fn get_system_contract(name: &str) -> ContractPointer {
    let key = get_key(name).unwrap_or_else(|| revert(Error::GetURef.into()));

    if let Key::URef(uref) = key {
        let reference =
            TURef::from_uref(uref).unwrap_or_else(|_| revert(Error::NoAccessRights.into()));
        ContractPointer::URef(reference)
    } else {
        revert(Error::UnexpectedKeyVariant.into())
    }
}

/// Returns a read-only pointer to the Mint Contract.  Any failure will trigger `revert()` with a
/// `contract_api::Error`.
pub fn get_mint() -> ContractPointer {
    get_system_contract(MINT_NAME)
}

/// Returns a read-only pointer to the Proof of Stake Contract.  Any failure will trigger `revert()`
/// with a `contract_api::Error`.
pub fn get_pos() -> ContractPointer {
    get_system_contract(POS_NAME)
}

pub fn get_phase() -> Phase {
    let dest_ptr = alloc_bytes(PHASE_SIZE);
    unsafe { ext_ffi::get_phase(dest_ptr) };
    let bytes = unsafe { Vec::from_raw_parts(dest_ptr, PHASE_SIZE, PHASE_SIZE) };
    deserialize(&bytes).unwrap()
}

/// Takes the name of a function to store and a contract URef, and overwrites the value under
/// that URef with a new Contract instance containing the original contract's named_keys, the
/// current protocol version, and the newly created bytes of the stored function.
pub fn upgrade_contract_at_uref(name: &str, uref: TURef<Contract>) {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    let key: Key = uref.into();
    let (key_ptr, key_size, _bytes) = to_ptr(&key);
    let result_value =
        unsafe { ext_ffi::upgrade_contract_at_uref(name_ptr, name_size, key_ptr, key_size) };
    match result_from(result_value) {
        Ok(()) => (),
        Err(error) => revert_with_error(error),
    }
}

#[cfg(test)]
mod tests {
    use super::Error;
    use core::u16;

    #[test]
    fn error() {
        assert_eq!(65_536_u32, Error::User(0).into()); // u16::MAX + 1
        assert_eq!(131_071_u32, Error::User(u16::MAX).into()); // 2 * u16::MAX + 1

        assert_eq!("Error::GetURef [1]", &format!("{:?}", Error::GetURef));
        assert_eq!("Error::User(0) [65536]", &format!("{:?}", Error::User(0)));
        assert_eq!(
            "Error::User(65535) [131071]",
            &format!("{:?}", Error::User(u16::MAX))
        );
    }
}
