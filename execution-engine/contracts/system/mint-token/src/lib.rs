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
mod internal_purse_id;

mod mint;

use alloc::string::String;
use core::convert::TryInto;

use contract_ffi::contract_api;
use contract_ffi::key::Key;
use contract_ffi::system_contracts::mint::error::Error;
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::U512;

use capabilities::{ARef, RAWRef};
use internal_purse_id::{DepositId, WithdrawId};
use mint::Mint;

struct CLMint;

impl Mint<ARef<U512>, RAWRef<U512>> for CLMint {
    type PurseId = WithdrawId;
    type DepOnlyId = DepositId;

    fn create(&self) -> Self::PurseId {
        let initial_balance = U512::from(0);
        let balance_uref: Key = contract_api::new_uref(initial_balance).into();

        let purse_key: URef = contract_api::new_uref(()).into();
        let purse_uref_name = purse_key.remove_access_rights().as_string();

        let purse_id: WithdrawId = WithdrawId::from_uref(purse_key).unwrap();

        // store balance uref so that the runtime knows the mint has full access
        contract_api::add_uref(&purse_uref_name, &balance_uref);

        // store association between purse id and balance uref
        //
        // Gorski writes:
        //   I'm worried that this can lead to overwriting of values in the local state.
        //   Since it accepts a raw byte array it's possible to construct one by hand.
        // Of course,   a key can be overwritten only when that write is
        // performed in the "owner" context   so it aligns with other semantics
        // of write but I would prefer if were able to enforce   uniqueness
        // somehow.
        contract_api::write_local(purse_id.raw_id(), balance_uref);

        purse_id
    }

    fn lookup(&self, p: Self::PurseId) -> Option<RAWRef<U512>> {
        contract_api::read_local(p.raw_id()).and_then(|key: Key| key.try_into().ok())
    }

    fn dep_lookup(&self, p: Self::DepOnlyId) -> Option<ARef<U512>> {
        contract_api::read_local(p.raw_id()).and_then(|key: Key| key.try_into().ok())
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let mint = CLMint;
    let method_name: String = contract_api::get_arg(0);

    match method_name.as_str() {
        "create" => {
            let purse_id = mint.create();
            let purse_key = URef::new(purse_id.raw_id(), AccessRights::READ_ADD_WRITE);
            contract_api::ret(&purse_key, &vec![purse_key])
        }

        "balance" => {
            let key: URef = contract_api::get_arg(1);
            let purse_id: WithdrawId = WithdrawId::from_uref(key).unwrap();
            let balance_uref = mint.lookup(purse_id);
            let balance: Option<U512> = balance_uref.map(|uref| contract_api::read(uref.into()));
            contract_api::ret(&balance, &vec![])
        }

        "transfer" => {
            let source: URef = contract_api::get_arg(1);
            let target: URef = contract_api::get_arg(2);
            let amount: U512 = contract_api::get_arg(3);

            let source: WithdrawId = match WithdrawId::from_uref(source) {
                Ok(withdraw_id) => withdraw_id,
                Err(error) => {
                    let transfer_result: Result<(), Error> = Err(error.into());
                    contract_api::ret(&transfer_result, &vec![])
                }
            };

            let target: DepositId = match DepositId::from_uref(target) {
                Ok(deposit_id) => deposit_id,
                Err(error) => {
                    let transfer_result: Result<(), Error> = Err(error.into());
                    contract_api::ret(&transfer_result, &vec![])
                }
            };

            let transfer_result = mint.transfer(source, target, amount);
            contract_api::ret(&transfer_result, &vec![]);
        }

        _ => panic!("Unknown method name!"),
    }
}
