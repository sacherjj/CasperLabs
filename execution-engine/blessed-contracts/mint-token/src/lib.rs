#![no_std]
#![feature(alloc, cell_update)]

#[macro_use]
extern crate alloc;
extern crate cl_std;

mod capabilities;

// These types are purposely defined in a separate module
// so that their constructors are hidden and therefore
// we must use the conversion methods from Key elsewhere
// in the code.
mod internal_purse_id;

mod mint;

use alloc::collections::BTreeMap;

use core::convert::TryInto;

use cl_std::contract_api;
use cl_std::key::Key;
use cl_std::value::U512;

use capabilities::{ARef, RWRef};
use internal_purse_id::{DepositId, WithdrawId};
use mint::Mint;

struct CLMint;

impl Mint<ARef<U512>, RWRef<U512>> for CLMint {
    type PurseId = WithdrawId;
    type DepOnlyId = DepositId;

    fn create(&self, balance: U512) -> Self::PurseId {
        let balance_uref: Key = contract_api::new_uref(balance).into();

        let purse_key: Key = contract_api::new_uref(()).into();
        let purse_id: WithdrawId = purse_key.try_into().unwrap();

        // store balance uref so that the runtime knows the mint has full access
        contract_api::add_uref(&format!("{:?}", purse_id.raw_id()), &balance_uref);

        // store association between purse id and balance uref
        contract_api::write_local(purse_id.raw_id(), balance_uref);

        purse_id
    }

    fn lookup(&self, p: Self::PurseId) -> Option<RWRef<U512>> {
        p.lookup().and_then(|key| key.try_into().ok())
    }

    fn dep_lookup(&self, p: Self::DepOnlyId) -> Option<ARef<U512>> {
        p.lookup().and_then(|key| key.try_into().ok())
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let _hash = contract_api::store_function("???", BTreeMap::new());
}
