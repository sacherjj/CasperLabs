#![no_std]
#![feature(cell_update)]

extern crate alloc;

// These types are purposely defined in a separate module
// so that their constructors are hidden and therefore
// we must use the conversion methods from Key elsewhere
// in the code.
pub mod mint;

use alloc::string::String;
use core::convert::TryInto;

use contract::{
    contract_api::{runtime, storage, TURef},
    unwrap_or_revert::UnwrapOrRevert,
};
use mint::Mint;
use types::{
    account::PUBLIC_KEY_LENGTH, system_contract_errors::mint::Error, ApiError, CLValue, Key, URef,
    U512,
};

const SYSTEM_ACCOUNT: [u8; PUBLIC_KEY_LENGTH] = [0u8; PUBLIC_KEY_LENGTH];

pub struct CLMint;

impl Mint for CLMint {
    fn mint(&self, initial_balance: U512) -> Result<URef, Error> {
        let caller = runtime::get_caller();
        if !initial_balance.is_zero() && caller.value() != SYSTEM_ACCOUNT {
            return Err(Error::InvalidNonEmptyPurseCreation);
        }

        let balance_uref: Key = storage::new_turef(initial_balance).into();
        let purse_key: URef = storage::new_turef(()).into();
        let purse_uref_name = purse_key.remove_access_rights().as_string();

        // store balance uref so that the runtime knows the mint has full access
        runtime::put_key(&purse_uref_name, balance_uref);

        // store association between purse id and balance uref
        storage::write_local(purse_key.addr(), balance_uref);

        Ok(purse_key)
    }

    fn lookup(&self, p: URef) -> Option<URef> {
        storage::read_local(&p.addr())
            .ok()?
            .and_then(|key: Key| key.try_into().ok())
    }

    fn create(&self) -> URef {
        self.mint(U512::zero())
            .expect("Creating a zero balance purse should always be allowed.")
    }

    fn transfer(&self, source: URef, dest: URef, amount: U512) -> Result<(), Error> {
        let source_bal = self.lookup(source).ok_or(Error::SourceNotFound)?;
        let source_bal_turef: TURef<U512> =
            TURef::from_uref(source_bal).map_err(|_| Error::InvalidURef)?;
        let source_value = match storage::read(source_bal_turef) {
            Ok(Some(source_value)) => source_value,
            Ok(None) => return Err(Error::SourceNotFound),
            Err(err) => runtime::revert(err),
        };
        if amount > source_value {
            return Err(Error::InsufficientFunds);
        }
        let dest_bal = self.lookup(dest).ok_or(Error::DestNotFound)?;
        let dest_bal_turef: TURef<U512> =
            TURef::from_uref(dest_bal).map_err(|_| Error::InvalidURef)?;
        storage::write(source_bal_turef, source_value - amount);
        storage::add(dest_bal_turef, amount);
        Ok(())
    }
}

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
            let uref: URef = mint.create();
            let ret = CLValue::from_t(uref).unwrap_or_revert();
            runtime::ret(ret)
        }

        "balance" => {
            let uref: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let balance_uref: TURef<U512> = match mint.lookup(uref) {
                // safe to unwrap because of patten guard
                Some(uref) if uref.access_rights().is_some() => TURef::from_uref(uref).unwrap(),
                Some(_) => runtime::revert(ApiError::NoAccessRights),
                None => runtime::revert(ApiError::ValueNotFound),
            };
            let balance: Option<U512> = storage::read(balance_uref).unwrap_or_default();
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
