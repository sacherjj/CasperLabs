// Can be removed once https://github.com/rust-lang/rustfmt/issues/3362 is resolved.
#[rustfmt::skip]
use alloc::vec;
use alloc::{collections::BTreeMap, string::String, vec::Vec};
use core::mem::MaybeUninit;

use casperlabs_types::{
    account::{PublicKey, PUBLIC_KEY_SERIALIZED_LENGTH},
    api_error,
    bytesrepr::{self, FromBytes},
    ApiError, BlockTime, CLTyped, CLValue, ContractRef, Key, Phase, URef,
    BLOCKTIME_SERIALIZED_LENGTH, PHASE_SERIALIZED_LENGTH,
};

use crate::{args_parser::ArgsParser, contract_api, ext_ffi, unwrap_or_revert::UnwrapOrRevert};

/// Returns `value` to the host, terminating the currently running module.
///
/// Note this function is only relevant to contracts stored on chain which return a value to their
/// caller. The return value of a directly deployed contract is never looked at.
pub fn ret(value: CLValue) -> ! {
    let (ptr, size, _bytes) = contract_api::to_ptr(value);
    unsafe {
        ext_ffi::ret(ptr, size);
    }
}

/// Stops execution of a contract and reverts execution effects with a given reason.
pub fn revert<T: Into<ApiError>>(error: T) -> ! {
    unsafe {
        ext_ffi::revert(error.into().into());
    }
}

/// Call the given contract, passing the given (serialized) arguments to
/// the host in order to have them available to the called contract during its
/// execution. The value returned from the contract call (see `ret` above) is
/// returned from this function.
#[allow(clippy::ptr_arg)]
pub fn call_contract<A: ArgsParser, T: CLTyped + FromBytes>(c_ptr: ContractRef, args: A) -> T {
    let contract_key: Key = c_ptr.into();
    let (key_ptr, key_size, _bytes1) = contract_api::to_ptr(contract_key);
    let (args_ptr, args_size, _bytes2) = ArgsParser::parse(args)
        .map(contract_api::to_ptr)
        .unwrap_or_revert();

    let bytes_written = {
        let mut bytes_written = MaybeUninit::uninit();
        let ret = unsafe {
            ext_ffi::call_contract(
                key_ptr,
                key_size,
                args_ptr,
                args_size,
                bytes_written.as_mut_ptr(),
            )
        };
        api_error::result_from(ret).unwrap_or_revert();
        unsafe { bytes_written.assume_init() }
    };

    // NOTE: this is a copy of the contents of `read_host_buffer()`.  Calling that directly from
    // here causes several contracts to fail with a Wasmi `Unreachable` error.
    let bytes_ptr = contract_api::alloc_bytes(bytes_written);
    let mut dest: Vec<u8> = unsafe { Vec::from_raw_parts(bytes_ptr, bytes_written, bytes_written) };
    read_host_buffer_into(&mut dest).unwrap_or_revert();
    bytesrepr::deserialize(dest).unwrap_or_revert()
}

/// Takes the name of a function to store and a contract URef, and overwrites the value under
/// that URef with a new Contract instance containing the original contract's named_keys, the
/// current protocol version, and the newly created bytes of the stored function.
pub fn upgrade_contract_at_uref(name: &str, uref: URef) {
    let (name_ptr, name_size, _bytes) = contract_api::to_ptr(name);
    let key: Key = uref.into();
    let (key_ptr, key_size, _bytes) = contract_api::to_ptr(key);
    let result_value =
        unsafe { ext_ffi::upgrade_contract_at_uref(name_ptr, name_size, key_ptr, key_size) };
    match api_error::result_from(result_value) {
        Ok(()) => (),
        Err(error) => revert(error),
    }
}

fn get_arg_size(i: u32) -> Option<usize> {
    let mut arg_size: usize = 0;
    let ret = unsafe { ext_ffi::get_arg_size(i as usize, &mut arg_size as *mut usize) };
    match api_error::result_from(ret) {
        Ok(_) => Some(arg_size),
        Err(ApiError::MissingArgument) => None,
        Err(e) => revert(e),
    }
}

/// Return the i-th argument passed to the host for the current module
/// invocation. Note that this is only relevant to contracts stored on-chain
/// since a contract deployed directly is not invoked with any arguments.
pub fn get_arg<T: FromBytes>(i: u32) -> Option<Result<T, bytesrepr::Error>> {
    let arg_size = get_arg_size(i)?;

    let arg_bytes = {
        let res = {
            let data_ptr = contract_api::alloc_bytes(arg_size);
            let ret = unsafe { ext_ffi::get_arg(i as usize, data_ptr, arg_size) };
            let data = unsafe { Vec::from_raw_parts(data_ptr, arg_size, arg_size) };
            api_error::result_from(ret).map(|_| data)
        };
        // Assumed to be safe as `get_arg_size` checks the argument already
        res.unwrap_or_revert()
    };
    Some(bytesrepr::deserialize(arg_bytes))
}

/// Returns caller of current context.
/// When in root context (not in the sub call) - returns None.
/// When in the sub call - returns public key of the account that made the
/// deploy.
pub fn get_caller() -> PublicKey {
    let dest_ptr = contract_api::alloc_bytes(PUBLIC_KEY_SERIALIZED_LENGTH);
    unsafe { ext_ffi::get_caller(dest_ptr) };
    let bytes = unsafe {
        Vec::from_raw_parts(
            dest_ptr,
            PUBLIC_KEY_SERIALIZED_LENGTH,
            PUBLIC_KEY_SERIALIZED_LENGTH,
        )
    };
    bytesrepr::deserialize(bytes).unwrap_or_revert()
}

pub fn get_blocktime() -> BlockTime {
    let dest_ptr = contract_api::alloc_bytes(BLOCKTIME_SERIALIZED_LENGTH);
    let bytes = unsafe {
        ext_ffi::get_blocktime(dest_ptr);
        Vec::from_raw_parts(
            dest_ptr,
            BLOCKTIME_SERIALIZED_LENGTH,
            BLOCKTIME_SERIALIZED_LENGTH,
        )
    };
    bytesrepr::deserialize(bytes).unwrap_or_revert()
}

pub fn get_phase() -> Phase {
    let dest_ptr = contract_api::alloc_bytes(PHASE_SERIALIZED_LENGTH);
    unsafe { ext_ffi::get_phase(dest_ptr) };
    let bytes =
        unsafe { Vec::from_raw_parts(dest_ptr, PHASE_SERIALIZED_LENGTH, PHASE_SERIALIZED_LENGTH) };
    bytesrepr::deserialize(bytes).unwrap_or_revert()
}

/// Return the unforgable reference known by the current module under the given
/// name. This either comes from the named_keys of the account or contract,
/// depending on whether the current module is a sub-call or not.
pub fn get_key(name: &str) -> Option<Key> {
    let (name_ptr, name_size, _bytes) = contract_api::to_ptr(name);
    let mut key_bytes = vec![0u8; Key::serialized_size_hint()];
    let mut total_bytes: usize = 0;
    let ret = unsafe {
        ext_ffi::get_key(
            name_ptr,
            name_size,
            key_bytes.as_mut_ptr(),
            key_bytes.len(),
            &mut total_bytes as *mut usize,
        )
    };
    match api_error::result_from(ret) {
        Ok(_) => {}
        Err(ApiError::MissingKey) => return None,
        Err(e) => revert(e),
    }
    key_bytes.truncate(total_bytes);
    let key: Key = bytesrepr::deserialize(key_bytes).unwrap_or_revert();
    Some(key)
}

/// Check if the given name corresponds to a known unforgable reference
pub fn has_key(name: &str) -> bool {
    let (name_ptr, name_size, _bytes) = contract_api::to_ptr(name);
    let result = unsafe { ext_ffi::has_key(name_ptr, name_size) };
    result == 0
}

/// Put the given key to the named_keys map under the given name
pub fn put_key(name: &str, key: Key) {
    let (name_ptr, name_size, _bytes) = contract_api::to_ptr(name);
    let (key_ptr, key_size, _bytes2) = contract_api::to_ptr(key);
    unsafe { ext_ffi::put_key(name_ptr, name_size, key_ptr, key_size) };
}

/// Removes key persisted under `name` in the current context's map.
pub fn remove_key(name: &str) {
    let (name_ptr, name_size, _bytes) = contract_api::to_ptr(name);
    unsafe { ext_ffi::remove_key(name_ptr, name_size) }
}

pub fn list_named_keys() -> BTreeMap<String, Key> {
    let (total_keys, result_size) = {
        let mut total_keys = MaybeUninit::uninit();
        let mut result_size = 0;
        let ret = unsafe {
            ext_ffi::load_named_keys(total_keys.as_mut_ptr(), &mut result_size as *mut usize)
        };
        api_error::result_from(ret).unwrap_or_revert();
        let total_keys = unsafe { total_keys.assume_init() };
        (total_keys, result_size)
    };
    if total_keys == 0 {
        return BTreeMap::new();
    }
    let bytes = read_host_buffer(result_size).unwrap_or_revert();
    bytesrepr::deserialize(bytes).unwrap_or_revert()
}

/// checks if a uref is valid
pub fn is_valid_uref(uref: URef) -> bool {
    let (uref_ptr, uref_size, _bytes) = contract_api::to_ptr(uref);
    let result = unsafe { ext_ffi::is_valid_uref(uref_ptr, uref_size) };
    result != 0
}

fn read_host_buffer_into(dest: &mut [u8]) -> Result<usize, ApiError> {
    let mut bytes_written = MaybeUninit::uninit();
    let ret = unsafe {
        ext_ffi::read_host_buffer(dest.as_mut_ptr(), dest.len(), bytes_written.as_mut_ptr())
    };
    // NOTE: When rewriting below expression as `result_from(ret).map(|_| unsafe { ... })`, and the
    // caller ignores the return value, execution of the contract becomes unstable and ultimately
    // leads to `Unreachable` error.
    api_error::result_from(ret)?;
    Ok(unsafe { bytes_written.assume_init() })
}

pub(crate) fn read_host_buffer(size: usize) -> Result<Vec<u8>, ApiError> {
    let bytes_ptr = contract_api::alloc_bytes(size);
    let mut dest: Vec<u8> = unsafe { Vec::from_raw_parts(bytes_ptr, size, size) };
    read_host_buffer_into(&mut dest)?;
    Ok(dest)
}
