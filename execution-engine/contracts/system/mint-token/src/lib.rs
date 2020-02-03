#![no_std]
#![feature(cell_update)]

extern crate alloc;

mod runtime_provider;
mod storage_provider;

use alloc::string::String;
use core::convert::TryFrom;

use contract::{
    contract_api::{runtime, TURef},
    unwrap_or_revert::UnwrapOrRevert,
};
use types::{
    account::PUBLIC_KEY_LENGTH, system_contract_errors::mint::Error, ApiError, CLValue, Key, URef,
    U512,
};

use crate::{
    runtime_provider::{ContractRuntime, RuntimeProvider},
    storage_provider::{ContractStorage, StorageProvider},
};

pub trait Mint<R, S>
where
    R: RuntimeProvider,
    S: StorageProvider,
{
    fn mint(&self, initial_balance: U512) -> Result<URef, Error> {
        let caller = R::get_caller();
        if !initial_balance.is_zero() && caller.value() != SYSTEM_ACCOUNT {
            return Err(Error::InvalidNonEmptyPurseCreation);
        }

        let balance_uref: Key = S::new_turef(initial_balance).into();
        let purse_key: URef = S::new_turef(()).into();
        let purse_uref_name = purse_key.remove_access_rights().as_string();

        // store balance uref so that the runtime knows the mint has full access
        R::put_key(&purse_uref_name, balance_uref);

        // store association between purse id and balance uref
        S::write_local(purse_key.addr(), balance_uref);

        Ok(purse_key)
    }

    fn balance(&self, purse: URef) -> Result<Option<U512>, Error> {
        let balance_uref: TURef<U512> = match S::read_local(&purse.addr()).unwrap_or_revert() {
            Some(key) => {
                let uref = TryFrom::<Key>::try_from(key).unwrap_or_revert();
                TURef::from_uref(uref).unwrap()
            }
            None => return Ok(None),
        };
        match S::read(balance_uref).unwrap_or_revert() {
            some @ Some(_) => Ok(some),
            None => Err(Error::DestNotFound), // TODO
        }
    }

    fn transfer(&self, source: URef, dest: URef, amount: U512) -> Result<(), Error> {
        let source_bal: URef = match S::read_local(&source.addr()).unwrap_or_revert() {
            Some(key) => TryFrom::<Key>::try_from(key).unwrap_or_revert(),
            _ => return Err(Error::SourceNotFound),
        };
        let source_bal_turef: TURef<U512> =
            TURef::from_uref(source_bal).map_err(|_| Error::InvalidURef)?;
        let source_value = match S::read(source_bal_turef).unwrap_or_revert() {
            Some(source_value) => source_value,
            None => return Err(Error::SourceNotFound),
        };
        if amount > source_value {
            return Err(Error::InsufficientFunds);
        }
        let dest_bal: URef = match S::read_local(&dest.addr()).unwrap_or_revert() {
            Some(key) => TryFrom::<Key>::try_from(key).unwrap_or_revert(),
            _ => return Err(Error::DestNotFound),
        };
        let dest_bal_turef: TURef<U512> =
            TURef::from_uref(dest_bal).map_err(|_| Error::InvalidURef)?;
        S::write(source_bal_turef, source_value - amount);
        S::add(dest_bal_turef, amount);
        Ok(())
    }
}

const SYSTEM_ACCOUNT: [u8; PUBLIC_KEY_LENGTH] = [0u8; PUBLIC_KEY_LENGTH];

pub struct CLMint;

impl Mint<ContractRuntime, ContractStorage> for CLMint {}

pub fn delegate() {
    let mint = CLMint;
    let method_name: String = runtime::get_arg(0)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);

    match method_name.as_str() {
        // argument: U512
        // return: Result<URef, mint::error::Error>
        "mint" => {
            let amount: U512 = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let result: Result<URef, Error> = mint.mint(amount);
            let ret = CLValue::from_t(result).unwrap_or_revert();
            runtime::ret(ret)
        }

        "create" => {
            let uref = mint.mint(U512::zero()).unwrap_or_revert();
            let ret = CLValue::from_t(uref).unwrap_or_revert();
            runtime::ret(ret)
        }

        "balance" => {
            let uref: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let balance: Option<U512> = mint.balance(uref).unwrap_or_revert();
            let ret = CLValue::from_t(balance).unwrap_or_revert();
            runtime::ret(ret)
        }

        "transfer" => {
            let source: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let target: URef = runtime::get_arg(2)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let amount: U512 = runtime::get_arg(3)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let result: Result<(), Error> = if !source.is_writeable() || !target.is_addable() {
                Err(Error::InvalidAccessRights)
            } else {
                mint.transfer(source, target, amount)
            };
            let ret = CLValue::from_t(result).unwrap_or_revert();
            runtime::ret(ret);
        }
        _ => panic!("Unknown method name!"),
    }
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    delegate();
}
