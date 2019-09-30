mod alloc_util;
pub mod argsparser;
pub mod pointers;

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
use crate::value::{Contract, ProtocolVersion, Value, U512};
use alloc::collections::BTreeMap;
use alloc::string::String;
use alloc::vec::Vec;
use argsparser::ArgsParser;
use core::convert::{From, TryFrom, TryInto};
use core::fmt::{self, Debug, Formatter};
use core::u16;

const MINT_NAME: &str = "mint";
const POS_NAME: &str = "pos";

/// All `Error` variants defined in this library other than `Error::User` will convert to a `u32`
/// value less than or equal to `RESERVED_ERROR_MAX`.
const RESERVED_ERROR_MAX: u32 = u16::MAX as u32;

/// Variants to be passed to `contract_api::revert()`.
///
/// Variants other than `Error::User` will represent a `u32` in the range `(0, u16::MAX]`, while
/// `Error::User` will represent a `u32` in the range `(u16::MAX, 2 * u16::MAX + 1]`.
///
/// Users can specify a C-style enum and implement `From` to ease usage of `contract_api::revert()`,
/// e.g.
/// ```
/// use casperlabs_contract_ffi::contract_api::Error;
///
/// #[repr(u16)]
/// enum FailureCode {
///     Zero = 0,  // 65,536 as an Error::User
///     One,       // 65,537 as an Error::User
///     Two        // 65,538 as an Error::User
/// }
///
/// impl From<FailureCode> for Error {
///     fn from(code: FailureCode) -> Self {
///         Error::User(code as u16)
///     }
/// }
///
/// assert_eq!(Error::User(1), FailureCode::One.into());
/// assert_eq!(65_536, u32::from(Error::from(FailureCode::Zero)));
/// assert_eq!(65_538, u32::from(Error::from(FailureCode::Two)));
/// ```
#[derive(Copy, Clone, PartialEq, Eq)]
pub enum Error {
    /// A call to `get_uref()` returned a failure.
    GetURef,
    /// Failed to deserialize a value.
    Deserialize,
    /// Failed to find a specified contract.
    ContractNotFound,
    /// The `Key` variant was not as expected.
    UnexpectedKeyVariant,
    /// The `Value` variant was not as expected.
    UnexpectedValueVariant,
    /// `read` returned an error.
    Read,
    /// The given key returned a `None` value.
    ValueNotFound,
    /// Failed to initialize a mint purse.
    MintFailure,
    /// Invalid purse name given.
    InvalidPurseName,
    /// Invalid purse retrieved.
    InvalidPurse,
    /// Specified argument not provided.
    MissingArgument,
    /// Argument not of correct type.
    InvalidArgument,
    /// Failed to upgrade contract at URef.
    UpgradeContractAtURef,
    /// Failed to transfer motes.
    Transfer,
    /// User-specified value.  The internal `u16` value is added to `u16::MAX as u32 + 1` when an
    /// `Error::User` is converted to a `u32`.
    User(u16),
}

impl From<Error> for u32 {
    fn from(error: Error) -> Self {
        match error {
            Error::GetURef => 1,
            Error::Deserialize => 2,
            Error::ContractNotFound => 3,
            Error::UnexpectedKeyVariant => 4,
            Error::UnexpectedValueVariant => 5,
            Error::Read => 6,
            Error::ValueNotFound => 7,
            Error::MintFailure => 8,
            Error::InvalidPurseName => 9,
            Error::InvalidPurse => 10,
            Error::MissingArgument => 11,
            Error::InvalidArgument => 12,
            Error::UpgradeContractAtURef => 13,
            Error::Transfer => 14,
            Error::User(value) => RESERVED_ERROR_MAX + 1 + u32::from(value),
        }
    }
}

impl Debug for Error {
    fn fmt(&self, f: &mut Formatter) -> fmt::Result {
        match self {
            Error::GetURef => write!(f, "Error::GetURef")?,
            Error::Deserialize => write!(f, "Error::Deserialize")?,
            Error::ContractNotFound => write!(f, "Error::ContractNotFound")?,
            Error::UnexpectedKeyVariant => write!(f, "Error::UnexpectedKeyVariant")?,
            Error::UnexpectedValueVariant => write!(f, "Error::UnexpectedValueVariant")?,
            Error::Read => write!(f, "Error::Read")?,
            Error::ValueNotFound => write!(f, "Error::ValueNotFound")?,
            Error::MintFailure => write!(f, "Error::MintFailure")?,
            Error::InvalidPurseName => write!(f, "Error::InvalidPurseName")?,
            Error::InvalidPurse => write!(f, "Error::InvalidPurse")?,
            Error::MissingArgument => write!(f, "Error::MissingArgument")?,
            Error::InvalidArgument => write!(f, "Error::InvalidArgument")?,
            Error::UpgradeContractAtURef => write!(f, "Error::UpgradeContractAtURef")?,
            Error::Transfer => write!(f, "Error::Transfer")?,
            Error::User(value) => write!(f, "Error::User({})", value)?,
        }
        write!(f, " [{}]", u32::from(*self))
    }
}

pub fn i32_from(result: Result<(), Error>) -> i32 {
    match result {
        Ok(()) => 0,
        Err(error) => u32::from(error) as i32,
    }
}

pub fn result_from(value: i32) -> Result<(), Error> {
    match value {
        0 => Ok(()),
        1 => Err(Error::GetURef),
        2 => Err(Error::Deserialize),
        3 => Err(Error::ContractNotFound),
        4 => Err(Error::UnexpectedKeyVariant),
        5 => Err(Error::UnexpectedValueVariant),
        6 => Err(Error::Read),
        7 => Err(Error::ValueNotFound),
        8 => Err(Error::MintFailure),
        9 => Err(Error::InvalidPurseName),
        10 => Err(Error::InvalidPurse),
        11 => Err(Error::MissingArgument),
        12 => Err(Error::InvalidArgument),
        13 => Err(Error::UpgradeContractAtURef),
        14 => Err(Error::Transfer),
        _ => {
            if value > RESERVED_ERROR_MAX as i32 && value <= (2 * RESERVED_ERROR_MAX + 1) as i32 {
                Err(Error::User(value as u16))
            } else {
                unreachable!()
            }
        }
    }
}

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

fn fn_bytes_by_name(name: &str) -> Vec<u8> {
    let (name_ptr, name_size, _bytes) = str_ref_to_ptr(name);
    let fn_size = unsafe { ext_ffi::serialize_function(name_ptr, name_size) };
    let fn_ptr = alloc_bytes(fn_size);
    unsafe {
        ext_ffi::get_function(fn_ptr);
        Vec::from_raw_parts(fn_ptr, fn_size, fn_size)
    }
}

pub fn list_known_keys() -> BTreeMap<String, Key> {
    let bytes_size = unsafe { ext_ffi::serialize_known_keys() };
    let dest_ptr = alloc_bytes(bytes_size);
    let bytes = unsafe {
        ext_ffi::list_known_keys(dest_ptr);
        Vec::from_raw_parts(dest_ptr, bytes_size, bytes_size)
    };
    deserialize(&bytes).unwrap()
}

// TODO: fn_by_name, fn_bytes_by_name and ext_ffi::serialize_function should be
// removed. Functions shouldn't be serialized and returned back to the contract
// because they're never used there. Host should read the function pointer (and
// correct number of bytes) and persist it on the host side.

/// Returns the serialized bytes of a function which is exported in the current
/// module. Note that the function is wrapped up in a new module and re-exported
/// under the name "call". `fn_bytes_by_name` is meant to be used when storing a
/// contract on-chain at an unforgable reference.
pub fn fn_by_name(name: &str, known_keys: BTreeMap<String, Key>) -> Contract {
    let bytes = fn_bytes_by_name(name);
    let protocol_version = unsafe { ext_ffi::protocol_version() };
    let protocol_version = ProtocolVersion::new(protocol_version);
    Contract::new(bytes, known_keys, protocol_version)
}

/// Gets the serialized bytes of an exported function (see `fn_by_name`), then
/// computes gets the address from the host to produce a key where the contract
/// is then stored in the global state. This key is returned.
pub fn store_function(name: &str, known_keys: BTreeMap<String, Key>) -> ContractPointer {
    let (fn_ptr, fn_size, _bytes1) = str_ref_to_ptr(name);
    let (urefs_ptr, urefs_size, _bytes2) = to_ptr(&known_keys);
    let mut tmp = [0u8; 32];
    let tmp_ptr = tmp.as_mut_ptr();
    unsafe {
        ext_ffi::store_function(fn_ptr, fn_size, urefs_ptr, urefs_size, tmp_ptr);
    }
    ContractPointer::Hash(tmp)
}

/// Finds function by the name and stores it at the unforgable name.
pub fn store_function_at(name: &str, known_keys: BTreeMap<String, Key>, uref: TURef<Contract>) {
    let contract = fn_by_name(name, known_keys);
    write(uref, contract);
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
/// name. This either comes from the known_keys of the account or contract,
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

/// Put the given key to the known_keys map under the given name
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
pub enum TransferResult {
    TransferredToExistingAccount,
    TransferredToNewAccount,
    TransferError,
}

impl TryFrom<i32> for TransferResult {
    type Error = ();

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(TransferResult::TransferredToExistingAccount),
            1 => Ok(TransferResult::TransferredToNewAccount),
            2 => Ok(TransferResult::TransferError),
            _ => Err(()),
        }
    }
}

impl From<TransferResult> for i32 {
    fn from(result: TransferResult) -> Self {
        match result {
            TransferResult::TransferredToExistingAccount => 0,
            TransferResult::TransferredToNewAccount => 1,
            TransferResult::TransferError => 2,
        }
    }
}

/// Transfers `amount` of motes from default purse of the account to `target`
/// account. If `target` does not exist it will create it.
pub fn transfer_to_account(target: PublicKey, amount: U512) -> TransferResult {
    let (target_ptr, target_size, _bytes) = to_ptr(&target);
    let (amount_ptr, amount_size, _bytes) = to_ptr(&amount);
    unsafe { ext_ffi::transfer_to_account(target_ptr, target_size, amount_ptr, amount_size) }
        .try_into()
        .expect("should parse result")
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
    unsafe {
        ext_ffi::transfer_from_purse_to_account(
            source_ptr,
            source_size,
            target_ptr,
            target_size,
            amount_ptr,
            amount_size,
        )
    }
    .try_into()
    .expect("should parse result")
}

// TODO: Improve returned result type.
#[derive(Debug, Copy, Clone, PartialEq, Eq)]
pub enum PurseTransferResult {
    TransferSuccessful,
    TransferError,
}

impl TryFrom<i32> for PurseTransferResult {
    type Error = ();

    fn try_from(value: i32) -> Result<Self, Self::Error> {
        match value {
            0 => Ok(PurseTransferResult::TransferSuccessful),
            1 => Ok(PurseTransferResult::TransferError),
            _ => Err(()),
        }
    }
}

impl From<PurseTransferResult> for i32 {
    fn from(result: PurseTransferResult) -> Self {
        match result {
            PurseTransferResult::TransferSuccessful => 0,
            PurseTransferResult::TransferError => 1,
        }
    }
}

/// Transfers `amount` of motes from `source` purse to `target` purse.
pub fn transfer_from_purse_to_purse(
    source: PurseId,
    target: PurseId,
    amount: U512,
) -> PurseTransferResult {
    let (source_ptr, source_size, _bytes) = to_ptr(&source);
    let (target_ptr, target_size, _bytes) = to_ptr(&target);
    let (amount_ptr, amount_size, _bytes) = to_ptr(&amount);
    unsafe {
        ext_ffi::transfer_from_purse_to_purse(
            source_ptr,
            source_size,
            target_ptr,
            target_size,
            amount_ptr,
            amount_size,
        )
    }
    .try_into()
    .expect("Should parse result")
}

fn get_system_contract(name: &str) -> ContractPointer {
    let key = get_key(name).unwrap_or_else(|| revert(Error::GetURef.into()));

    if let Key::URef(uref) = key {
        let reference = TURef::new(uref.addr(), AccessRights::READ);
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
/// that URef with a new Contract instance containing the original contract's known_keys, the
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
