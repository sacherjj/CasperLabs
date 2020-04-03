//! Functions for accessing and mutating local and global state.

use alloc::{collections::BTreeMap, string::String, vec::Vec};
use core::{convert::From, mem::MaybeUninit};

use casperlabs_types::{
    api_error,
    bytesrepr::{self, FromBytes, ToBytes},
    contract_header::{self, ContractHeader},
    AccessRights, ApiError, CLTyped, CLValue, ContractRef, Key, SemVer, URef,
    KEY_UREF_SERIALIZED_LENGTH,
};

use crate::{
    contract_api::{self, runtime},
    ext_ffi,
    unwrap_or_revert::UnwrapOrRevert,
};

/// Reads value under `uref` in the global state.
pub fn read<T: CLTyped + FromBytes>(uref: URef) -> Result<Option<T>, bytesrepr::Error> {
    let key: Key = uref.into();
    let (key_ptr, key_size, _bytes) = contract_api::to_ptr(key);

    let value_size = {
        let mut value_size = MaybeUninit::uninit();
        let ret = unsafe { ext_ffi::read_value(key_ptr, key_size, value_size.as_mut_ptr()) };
        match api_error::result_from(ret) {
            Ok(_) => unsafe { value_size.assume_init() },
            Err(ApiError::ValueNotFound) => return Ok(None),
            Err(e) => runtime::revert(e),
        }
    };

    let value_bytes = runtime::read_host_buffer(value_size).unwrap_or_revert();
    Ok(Some(bytesrepr::deserialize(value_bytes)?))
}

/// Reads value under `uref` in the global state, reverts if value not found or is not `T`.
pub fn read_or_revert<T: CLTyped + FromBytes>(uref: URef) -> T {
    read(uref)
        .unwrap_or_revert_with(ApiError::Read)
        .unwrap_or_revert_with(ApiError::ValueNotFound)
}

/// Reads the value under `key` in the context-local partition of global state.
pub fn read_local<K: ToBytes, V: CLTyped + FromBytes>(
    key: &K,
) -> Result<Option<V>, bytesrepr::Error> {
    let key_bytes = key.to_bytes()?;

    let value_size = {
        let mut value_size = MaybeUninit::uninit();
        let ret = unsafe {
            ext_ffi::read_value_local(key_bytes.as_ptr(), key_bytes.len(), value_size.as_mut_ptr())
        };
        match api_error::result_from(ret) {
            Ok(_) => unsafe { value_size.assume_init() },
            Err(ApiError::ValueNotFound) => return Ok(None),
            Err(e) => runtime::revert(e),
        }
    };

    let value_bytes = runtime::read_host_buffer(value_size).unwrap_or_revert();
    Ok(Some(bytesrepr::deserialize(value_bytes)?))
}

/// Writes `value` under `uref` in the global state.
pub fn write<T: CLTyped + ToBytes>(uref: URef, value: T) {
    let key = Key::from(uref);
    let (key_ptr, key_size, _bytes1) = contract_api::to_ptr(key);

    let cl_value = CLValue::from_t(value).unwrap_or_revert();
    let (cl_value_ptr, cl_value_size, _bytes2) = contract_api::to_ptr(cl_value);

    unsafe {
        ext_ffi::write(key_ptr, key_size, cl_value_ptr, cl_value_size);
    }
}

/// Writes `value` under `key` in the context-local partition of global state.
pub fn write_local<K: ToBytes, V: CLTyped + ToBytes>(key: K, value: V) {
    let (key_ptr, key_size, _bytes1) = contract_api::to_ptr(key);

    let cl_value = CLValue::from_t(value).unwrap_or_revert();
    let (cl_value_ptr, cl_value_size, _bytes) = contract_api::to_ptr(cl_value);

    unsafe {
        ext_ffi::write_local(key_ptr, key_size, cl_value_ptr, cl_value_size);
    }
}

/// Adds `value` to the one currently under `uref` in the global state.
pub fn add<T: CLTyped + ToBytes>(uref: URef, value: T) {
    let key = Key::from(uref);
    let (key_ptr, key_size, _bytes1) = contract_api::to_ptr(key);

    let cl_value = CLValue::from_t(value).unwrap_or_revert();
    let (cl_value_ptr, cl_value_size, _bytes2) = contract_api::to_ptr(cl_value);

    unsafe {
        // Could panic if `value` cannot be added to the given value in memory.
        ext_ffi::add(key_ptr, key_size, cl_value_ptr, cl_value_size);
    }
}

/// Adds `value` to the one currently under `key` in the context-local partition of global state.
pub fn add_local<K: ToBytes, V: CLTyped + ToBytes>(key: K, value: V) {
    let (key_ptr, key_size, _bytes1) = contract_api::to_ptr(key);

    let cl_value = CLValue::from_t(value).unwrap_or_revert();
    let (cl_value_ptr, cl_value_size, _bytes) = contract_api::to_ptr(cl_value);

    unsafe {
        ext_ffi::add_local(key_ptr, key_size, cl_value_ptr, cl_value_size);
    }
}

/// Create a new (versioned) contract stored under a Key::URef. Initially there
/// are no versions; a version must be added via `add_contract_version` before
/// the contract can be executed.
pub fn create_contract() -> (ContractRef, URef) {
    let mut uref_addr = [0u8; 32];
    let mut access_addr = [0u8; 32];
    unsafe {
        ext_ffi::create_contract(uref_addr.as_mut_ptr(), access_addr.as_mut_ptr());
    }
    let contract_ref = ContractRef::URef(URef::new(uref_addr, AccessRights::READ_ADD_WRITE));
    let access_key = URef::new(access_addr, AccessRights::READ_ADD_WRITE);

    (contract_ref, access_key)
}

/// Create a new (versioned) contract stored under a Key::Hash. Initially there
/// are no versions; a version must be added via `add_contract_version` before
/// the contract can be executed.
pub fn create_contract_at_hash() -> (ContractRef, URef) {
    let mut hash_addr = [0u8; 32];
    let mut access_addr = [0u8; 32];
    unsafe {
        ext_ffi::create_contract_at_hash(hash_addr.as_mut_ptr(), access_addr.as_mut_ptr());
    }
    let contract_ref = ContractRef::Hash(hash_addr);
    let access_key = URef::new(access_addr, AccessRights::READ_ADD_WRITE);

    (contract_ref, access_key)
}

/// Add a new version of a contract to the contract stored at the given
/// `ContractRef`. Note that this contract must have been created by
/// `create_contract` or `create_contract_at_hash` first.
pub fn add_contract_version(
    contract: ContractRef,
    access_key: URef,
    version: SemVer,
    header: ContractHeader,
    named_keys: BTreeMap<String, Key>,
) -> Result<(), contract_header::Error> {
    let (meta_ptr, meta_size, _bytes1) = contract_api::to_ptr(Key::from(contract));
    let (access_ptr, _access_size, _bytes2) = contract_api::to_ptr(access_key);
    let (version_ptr, _version_size, _bytes3) = contract_api::to_ptr(version);
    let (header_ptr, header_size, _bytes4) = contract_api::to_ptr(header);
    let (keys_ptr, keys_size, _bytes5) = contract_api::to_ptr(named_keys);

    let result = unsafe {
        ext_ffi::add_contract_version(
            meta_ptr,
            meta_size,
            access_ptr,
            version_ptr,
            header_ptr,
            header_size,
            keys_ptr,
            keys_size,
        )
    };

    match contract_header::Error::from_i32(result) {
        None => Ok(()),
        Some(err) => Err(err),
    }
}

/// Remove a version of a contract to the contract stored at the given
/// `ContractRef`. That version of the contract will no longer be callable by
/// `call_versioned_contract`. Note that this contract must have been created by
/// `create_contract` or `create_contract_at_hash` first.
pub fn remove_contract_version(
    contract: ContractRef,
    access_key: URef,
    version: SemVer,
) -> Result<(), contract_header::Error> {
    let (meta_ptr, meta_size, _bytes1) = contract_api::to_ptr(Key::from(contract));
    let (access_ptr, _access_size, _bytes2) = contract_api::to_ptr(access_key);
    let (version_ptr, _version_size, _bytes3) = contract_api::to_ptr(version);

    let result =
        unsafe { ext_ffi::remove_contract_version(meta_ptr, meta_size, access_ptr, version_ptr) };

    match contract_header::Error::from_i32(result) {
        None => Ok(()),
        Some(err) => Err(err),
    }
}

/// Stores the serialized bytes of an exported, non-mangled `extern "C"` function as a new contract
/// under a [`URef`] generated by the host.
pub fn store_function(name: &str, named_keys: BTreeMap<String, Key>) -> ContractRef {
    let (fn_ptr, fn_size, _bytes1) = contract_api::to_ptr(name);
    let (keys_ptr, keys_size, _bytes2) = contract_api::to_ptr(named_keys);
    let mut addr = [0u8; 32];
    unsafe {
        ext_ffi::store_function(fn_ptr, fn_size, keys_ptr, keys_size, addr.as_mut_ptr());
    }
    ContractRef::URef(URef::new(addr, AccessRights::READ_ADD_WRITE))
}

/// Stores the serialized bytes of an exported, non-mangled `extern "C"` function as a new contract
/// at an immutable address generated by the host.
pub fn store_function_at_hash(name: &str, named_keys: BTreeMap<String, Key>) -> ContractRef {
    let (fn_ptr, fn_size, _bytes1) = contract_api::to_ptr(name);
    let (keys_ptr, keys_size, _bytes2) = contract_api::to_ptr(named_keys);
    let mut addr = [0u8; 32];
    unsafe {
        ext_ffi::store_function_at_hash(fn_ptr, fn_size, keys_ptr, keys_size, addr.as_mut_ptr());
    }
    ContractRef::Hash(addr)
}

/// Returns a new unforgeable pointer, where the value is initialized to `init`.
pub fn new_uref<T: CLTyped + ToBytes>(init: T) -> URef {
    let key_ptr = contract_api::alloc_bytes(Key::max_serialized_length());
    let cl_value = CLValue::from_t(init).unwrap_or_revert();
    let (cl_value_ptr, cl_value_size, _cl_value_bytes) = contract_api::to_ptr(cl_value);
    let bytes = unsafe {
        ext_ffi::new_uref(key_ptr, cl_value_ptr, cl_value_size); // URef has `READ_ADD_WRITE`
        Vec::from_raw_parts(
            key_ptr,
            KEY_UREF_SERIALIZED_LENGTH,
            KEY_UREF_SERIALIZED_LENGTH,
        )
    };
    let key: Key = bytesrepr::deserialize(bytes).unwrap_or_revert();
    if let Key::URef(uref) = key {
        uref
    } else {
        runtime::revert(ApiError::UnexpectedKeyVariant);
    }
}
