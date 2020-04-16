#![no_std]

extern crate alloc;

use alloc::string::String;

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use mint::{Mint, RuntimeProvider, StorageProvider};
use types::{
    account::PublicKey,
    bytesrepr::{FromBytes, ToBytes},
    system_contract_errors::mint::Error,
    ApiError, CLTyped, CLValue, Key, URef, U512,
};

const METHOD_MINT: &str = "mint";
const METHOD_CREATE: &str = "create";
const METHOD_BALANCE: &str = "balance";
const METHOD_TRANSFER: &str = "transfer";

pub struct MintContract;

impl RuntimeProvider for MintContract {
    fn get_caller(&self) -> PublicKey {
        runtime::get_caller()
    }

    fn put_key(&mut self, name: &str, key: Key) {
        runtime::put_key(name, key)
    }
}

impl StorageProvider for MintContract {
    fn new_uref<T: CLTyped + ToBytes>(&mut self, init: T) -> URef {
        storage::new_uref(init)
    }

    fn write_local<K: ToBytes, V: CLTyped + ToBytes>(&mut self, key: K, value: V) {
        storage::write_local(key, value)
    }

    fn read_local<K: ToBytes, V: CLTyped + FromBytes>(
        &mut self,
        key: &K,
    ) -> Result<Option<V>, Error> {
        storage::read_local(key).map_err(|_| Error::Storage)
    }

    fn read<T: CLTyped + FromBytes>(&mut self, uref: URef) -> Result<Option<T>, Error> {
        storage::read(uref).map_err(|_| Error::Storage)
    }

    fn write<T: CLTyped + ToBytes>(&mut self, uref: URef, value: T) -> Result<(), Error> {
        storage::write(uref, value);
        Ok(())
    }

    fn add<T: CLTyped + ToBytes>(&mut self, uref: URef, value: T) -> Result<(), Error> {
        storage::add(uref, value);
        Ok(())
    }
}

impl Mint for MintContract {}

pub fn delegate() {
    let mut mint_contract = MintContract;

    let method_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    match method_name.as_str() {
        // Type: `fn mint(amount: U512) -> Result<URef, Error>`
        METHOD_MINT => {
            let amount: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let result: Result<URef, Error> = mint_contract.mint(amount);
            let ret = CLValue::from_t(result).unwrap_or_revert();
            runtime::ret(ret)
        }
        // Type: `fn create() -> URef`
        METHOD_CREATE => {
            let uref = mint_contract.mint(U512::zero()).unwrap_or_revert();
            let ret = CLValue::from_t(uref).unwrap_or_revert();
            runtime::ret(ret)
        }
        // Type: `fn balance(purse: URef) -> Option<U512>`
        METHOD_BALANCE => {
            let uref: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let balance: Option<U512> = mint_contract.balance(uref).unwrap_or_revert();
            let ret = CLValue::from_t(balance).unwrap_or_revert();
            runtime::ret(ret)
        }
        // Type: `fn transfer(source: URef, target: URef, amount: U512) -> Result<(), Error>`
        METHOD_TRANSFER => {
            let source: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let target: URef = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let amount: U512 = runtime::get_arg(3)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let result: Result<(), Error> = mint_contract.transfer(source, target, amount);
            let ret = CLValue::from_t(result).unwrap_or_revert();
            runtime::ret(ret);
        }

        _ => panic!("Unknown method name!"),
    }
}
