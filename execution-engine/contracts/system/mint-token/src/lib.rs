#![no_std]
#![feature(cell_update)]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

mod capabilities;

// These types are purposely defined in a separate module
// so that their constructors are hidden and therefore
// we must use the conversion methods from Key elsewhere
// in the code.
pub mod internal_purse_id;
pub mod mint;

use alloc::string::String;
use core::convert::TryInto;

use contract_ffi::contract_api::{runtime, storage, Error as ApiError};
use contract_ffi::key::Key;
use contract_ffi::system_contracts::mint::Error;
use contract_ffi::unwrap_or_revert::UnwrapOrRevert;
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::KEY_SIZE;
use contract_ffi::value::U512;

use capabilities::{ARef, RAWRef};
use internal_purse_id::{DepositId, WithdrawId};
use mint::Mint;

const SYSTEM_ACCOUNT: [u8; KEY_SIZE] = [0u8; KEY_SIZE];

pub struct CLMint;

impl Mint<ARef<U512>, RAWRef<U512>> for CLMint {
    type PurseId = WithdrawId;
    type DepOnlyId = DepositId;

    fn mint(&self, initial_balance: U512) -> Result<Self::PurseId, Error> {
        let caller = runtime::get_caller();
        if !initial_balance.is_zero() && caller.value() != SYSTEM_ACCOUNT {
            return Err(Error::InvalidNonEmptyPurseCreation);
        }

        let balance_uref: Key = storage::new_turef(initial_balance).into();

        let purse_key: URef = storage::new_turef(()).into();
        let purse_uref_name = purse_key.remove_access_rights().as_string();

        let purse_id: WithdrawId = WithdrawId::from_uref(purse_key).unwrap();

        // store balance uref so that the runtime knows the mint has full access
        runtime::put_key(&purse_uref_name, &balance_uref);

        // store association between purse id and balance uref
        //
        // Gorski writes:
        //   I'm worried that this can lead to overwriting of values in the local state.
        //   Since it accepts a raw byte array it's possible to construct one by hand.
        // Of course,   a key can be overwritten only when that write is
        // performed in the "owner" context   so it aligns with other semantics
        // of write but I would prefer if were able to enforce   uniqueness
        // somehow.
        storage::write_local(purse_id.raw_id(), balance_uref);

        Ok(purse_id)
    }

    fn lookup(&self, p: Self::PurseId) -> Option<RAWRef<U512>> {
        storage::read_local(p.raw_id())
            .ok()?
            .and_then(|key: Key| key.try_into().ok())
    }

    fn dep_lookup(&self, p: Self::DepOnlyId) -> Option<ARef<U512>> {
        storage::read_local(p.raw_id())
            .ok()?
            .and_then(|key: Key| key.try_into().ok())
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

            let maybe_purse_key = mint
                .mint(amount)
                .map(|purse_id| URef::new(purse_id.raw_id(), AccessRights::READ_ADD_WRITE));

            if let Ok(purse_key) = maybe_purse_key {
                runtime::ret(&maybe_purse_key, &vec![purse_key])
            } else {
                runtime::ret(&maybe_purse_key, &vec![])
            }
        }

        "create" => {
            let purse_id = mint.create();
            let purse_key = URef::new(purse_id.raw_id(), AccessRights::READ_ADD_WRITE);
            runtime::ret(&purse_key, &vec![purse_key])
        }

        "balance" => {
            let key: URef = runtime::get_arg(1)
                .unwrap_or_revert_with(ApiError::MissingArgument)
                .unwrap_or_revert_with(ApiError::InvalidArgument);
            let purse_id: WithdrawId = WithdrawId::from_uref(key).unwrap();
            let balance_uref = mint.lookup(purse_id);
            let balance: Option<U512> =
                balance_uref.and_then(|uref| storage::read(uref.into()).unwrap_or_default());
            runtime::ret(&balance, &vec![])
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

            let source: WithdrawId = match WithdrawId::from_uref(source) {
                Ok(withdraw_id) => withdraw_id,
                Err(error) => {
                    let transfer_result: Result<(), Error> = Err(error.into());
                    runtime::ret(&transfer_result, &vec![])
                }
            };

            let target: DepositId = match DepositId::from_uref(target) {
                Ok(deposit_id) => deposit_id,
                Err(error) => {
                    let transfer_result: Result<(), Error> = Err(error.into());
                    runtime::ret(&transfer_result, &vec![])
                }
            };

            let transfer_result = mint.transfer(source, target, amount);
            runtime::ret(&transfer_result, &vec![]);
        }
        _ => panic!("Unknown method name!"),
    }
}

#[cfg(not(feature = "lib"))]
#[no_mangle]
pub extern "C" fn call() {
    delegate();
}
