mod alloc_util;
pub mod argsparser;
pub mod pointers;

use self::alloc_util::*;
use self::pointers::*;
use crate::bytesrepr::{deserialize, FromBytes, ToBytes};
use crate::ext_ffi;
use crate::key::{Key, LOCAL_KEY_HASH_SIZE, LOCAL_SEED_SIZE, UREF_SIZE};
use crate::value::account::{ActionType, AddKeyFailure, PublicKey, Weight};
use crate::value::{Contract, Value};
use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;
use argsparser::ArgsParser;
use blake2::digest::{Input, VariableOutput};
use blake2::VarBlake2b;
use core::convert::{TryFrom, TryInto};

/// Creates a 32-byte BLAKE2b hash digest from a given a piece of data
fn hash(bytes: &[u8]) -> [u8; LOCAL_KEY_HASH_SIZE] {
    let mut ret = [0u8; LOCAL_KEY_HASH_SIZE];
    // Safe to unwrap here because our digest length is constant and valid
    let mut hasher = VarBlake2b::new(LOCAL_KEY_HASH_SIZE).unwrap();
    hasher.input(bytes);
    hasher.variable_result(|hash| ret.clone_from_slice(hash));
    ret
}

/// Read value under the key in the global state
pub fn read<T>(u_ptr: UPointer<T>) -> T
where
    T: TryFrom<Value>,
{
    let key: Key = u_ptr.into();
    let value = read_untyped(&key);
    value
        .unwrap() // TODO: return an Option instead of unwrapping (https://casperlabs.atlassian.net/browse/EE-349)
        .try_into()
        .map_err(|_| "T could not be derived from Value")
        .unwrap()
}

/// Reads the value at the given key in the context-local partition of global state
pub fn read_local<K, V>(key: K) -> Option<V>
where
    K: ToBytes,
    V: TryFrom<Value>,
{
    let seed: [u8; LOCAL_SEED_SIZE] = {
        let mut ret = [0u8; LOCAL_SEED_SIZE];
        unsafe { ext_ffi::seed(ret.as_mut_ptr()) };
        ret
    };
    let key_hash: [u8; LOCAL_KEY_HASH_SIZE] = {
        let key_bytes = key.to_bytes().unwrap();
        hash(&key_bytes)
    };
    let key = Key::Local { seed, key_hash };
    read_untyped(&key).map(|v| {
        v.try_into()
            .map_err(|_| "T could not be derived from Value")
            .unwrap()
    })
}

fn read_untyped(key: &Key) -> Option<Value> {
    // Note: _bytes is necessary to keep the Vec<u8> in scope. If _bytes is
    //      dropped then key_ptr becomes invalid.

    let (key_ptr, key_size, _bytes) = to_ptr(key);
    let value_size = unsafe { ext_ffi::read_value(key_ptr, key_size) };
    let value_ptr = alloc_bytes(value_size);
    let value_bytes = unsafe {
        ext_ffi::get_read(value_ptr);
        Vec::from_raw_parts(value_ptr, value_size, value_size)
    };
    deserialize(&value_bytes).unwrap()
}

/// Write the value under the key in the global state
pub fn write<T>(u_ptr: UPointer<T>, t: T)
where
    Value: From<T>,
{
    let key = u_ptr.into();
    let value = t.into();
    write_untyped(&key, &value)
}

/// Writes the given value at the given key in the context-local partition of global state
pub fn write_local<K, V>(key: K, value: V)
where
    K: ToBytes,
    V: Into<Value>,
{
    let seed: [u8; LOCAL_SEED_SIZE] = {
        let mut ret = [0u8; LOCAL_SEED_SIZE];
        unsafe { ext_ffi::seed(ret.as_mut_ptr()) };
        ret
    };
    let key_hash: [u8; LOCAL_KEY_HASH_SIZE] = {
        let key_bytes = key.to_bytes().unwrap();
        hash(&key_bytes)
    };
    let key = Key::Local { seed, key_hash };
    write_untyped(&key, &value.into());
}

fn write_untyped(key: &Key, value: &Value) {
    let (key_ptr, key_size, _bytes) = to_ptr(key);
    let (value_ptr, value_size, _bytes2) = to_ptr(value);
    unsafe {
        ext_ffi::write(key_ptr, key_size, value_ptr, value_size);
    }
}

/// Add the given value to the one currently under the key in the global state
pub fn add<T>(u_ptr: UPointer<T>, t: T)
where
    Value: From<T>,
{
    let key = u_ptr.into();
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
pub fn new_uref<T>(init: T) -> UPointer<T>
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
    if let Key::URef(id, Some(access_rights)) = key {
        UPointer::new(id, access_rights)
    } else {
        panic!("URef FFI did not return a valid URef!");
    }
}

fn fn_bytes_by_name(name: &str) -> Vec<u8> {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    let fn_size = unsafe { ext_ffi::serialize_function(name_ptr, name_size) };
    let fn_ptr = alloc_bytes(fn_size);
    unsafe {
        ext_ffi::get_function(fn_ptr);
        Vec::from_raw_parts(fn_ptr, fn_size, fn_size)
    }
}

// TODO: fn_by_name, fn_bytes_by_name and ext_ffi::serialize_function should be removed.
// Functions shouldn't be serialized and returned back to the contract because they're never used there.
// Host should read the function pointer (and correct number of bytes) and persist it on the host side.

/// Returns the serialized bytes of a function which is exported in the current module.
/// Note that the function is wrapped up in a new module and re-exported under the name
/// "call". `fn_bytes_by_name` is meant to be used when storing a contract on-chain at
/// an unforgable reference.
pub fn fn_by_name(name: &str, known_urefs: BTreeMap<String, Key>) -> Contract {
    let bytes = fn_bytes_by_name(name);
    let protocol_version = unsafe { ext_ffi::protocol_version() };
    Contract::new(bytes, known_urefs, protocol_version)
}

/// Gets the serialized bytes of an exported function (see `fn_by_name`), then
/// computes gets the address from the host to produce a key where the contract is then
/// stored in the global state. This key is returned.
pub fn store_function(name: &str, known_urefs: BTreeMap<String, Key>) -> ContractPointer {
    let (fn_ptr, fn_size, _bytes1) = str_ref_to_ptr(name);
    let (urefs_ptr, urefs_size, _bytes2) = to_ptr(&known_urefs);
    let mut tmp = [0u8; 32];
    let tmp_ptr = tmp.as_mut_ptr();
    unsafe {
        ext_ffi::store_function(fn_ptr, fn_size, urefs_ptr, urefs_size, tmp_ptr);
    }
    ContractPointer::Hash(tmp)
}

/// Finds function by the name and stores it at the unforgable name.
pub fn store_function_at(name: &str, known_urefs: BTreeMap<String, Key>, uref: UPointer<Contract>) {
    let contract = fn_by_name(name, known_urefs);
    write(uref, contract);
}

/// Return the i-th argument passed to the host for the current module
/// invokation. Note that this is only relevent to contracts stored on-chain
/// since a contract deployed directly is not invoked with any arguments.
pub fn get_arg<T: FromBytes>(i: u32) -> T {
    let arg_size = unsafe { ext_ffi::load_arg(i) };
    let dest_ptr = alloc_bytes(arg_size);
    let arg_bytes = unsafe {
        ext_ffi::get_arg(dest_ptr);
        Vec::from_raw_parts(dest_ptr, arg_size, arg_size)
    };
    // TODO: better error handling (i.e. pass the `Result` on)
    deserialize(&arg_bytes).unwrap()
}

/// Return the unforgable reference known by the current module under the given name.
/// This either comes from the known_urefs of the account or contract,
/// depending on whether the current module is a sub-call or not.
pub fn get_uref(name: &str) -> Key {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    let dest_ptr = alloc_bytes(UREF_SIZE);
    let uref_bytes = unsafe {
        ext_ffi::get_uref(name_ptr, name_size, dest_ptr);
        Vec::from_raw_parts(dest_ptr, UREF_SIZE, UREF_SIZE)
    };
    // TODO: better error handling (i.e. pass the `Result` on)
    deserialize(&uref_bytes).unwrap()
}

/// Check if the given name corresponds to a known unforgable reference
pub fn has_uref(name: &str) -> bool {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    let result = unsafe { ext_ffi::has_uref_name(name_ptr, name_size) };
    result == 0
}

/// Add the given key to the known_urefs map under the given name
pub fn add_uref(name: &str, key: &Key) {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    let (key_ptr, key_size, _bytes2) = to_ptr(key);
    unsafe { ext_ffi::add_uref(name_ptr, name_size, key_ptr, key_size) };
}

/// Return `t` to the host, terminating the currently running module.
/// Note this function is only relevent to contracts stored on chain which
/// return a value to their caller. The return value of a directly deployed
/// contract is never looked at.
#[allow(clippy::ptr_arg)]
pub fn ret<T: ToBytes>(t: &T, extra_urefs: &Vec<Key>) -> ! {
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
pub fn add_key(public_key: PublicKey, weight: Weight) -> Result<(), AddKeyFailure> {
    let (public_key_ptr, public_key_size, _bytes) = to_ptr(&public_key);
    // Cast of u8 (weight) into i32 is assumed to be always safe
    let result =
        unsafe { ext_ffi::add_key(public_key_ptr, public_key_size, weight.value() as i32) };
    // Translates FFI
    match result {
        d if d <= 0 => Ok(()),
        d => Err(AddKeyFailure::from(d)),
    }
}

/// Removes a public key from associated keys on an account
pub fn remove_key(public_key: PublicKey) -> bool {
    let (public_key_ptr, public_key_size, _bytes) = to_ptr(&public_key);
    let result = unsafe { ext_ffi::remove_key(public_key_ptr, public_key_size) };
    result != 0
}

pub fn set_threshold(permission_level: ActionType, threshold: Weight) -> bool {
    let permission_level = permission_level as i32;
    let threshold = threshold.value() as i32;
    let result = unsafe { ext_ffi::set_threshold(permission_level, threshold) };
    result != 0
}
