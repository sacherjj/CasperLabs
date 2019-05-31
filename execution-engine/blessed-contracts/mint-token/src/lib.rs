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
use alloc::string::String;
use core::convert::TryInto;

use cl_std::contract_api;
use cl_std::key::{AccessRights, Key};
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

fn transfer(
    source_key: Key,
    target_key: Key,
    amount: U512,
    mint: CLMint,
) -> Result<(), mint::Error> {
    let source: WithdrawId = source_key
        .try_into()
        .map_err(|_| mint::Error::SourceNotFound)?;
    let target: DepositId = target_key
        .try_into()
        .map_err(|_| mint::Error::DestNotFound)?;

    mint.transfer(source, target, amount)
}

#[no_mangle]
pub extern "C" fn mint_ext() {
    let mint = CLMint;
    let method_name: String = contract_api::get_arg(0);

    match method_name.as_str() {
        "create" => {
            let amount: U512 = contract_api::get_arg(1);
            let purse_id = mint.create(amount);
            let purse_key = Key::URef(purse_id.raw_id(), AccessRights::READ_ADD_WRITE);
            contract_api::ret(&purse_key, &vec![purse_key])
        }

        "balance" => {
            let key: Key = contract_api::get_arg(1);
            let purse_id: WithdrawId = key.try_into().unwrap();
            let balance_uref = mint.lookup(purse_id);
            let balance: Option<U512> = balance_uref.map(|uref| contract_api::read(uref.into()));
            contract_api::ret(&balance, &vec![])
        }

        "transfer" => {
            let source_key: Key = contract_api::get_arg(1);
            let target_key: Key = contract_api::get_arg(2);
            let amount: U512 = contract_api::get_arg(3);

            let message = match transfer(source_key, target_key, amount, mint) {
                Ok(_) => String::from("Success!"),
                Err(e) => format!("Error: {:?}", e),
            };

            contract_api::ret(&message, &vec![]);
        }

        _ => panic!("Unknown method name!"),
    }
}

#[no_mangle]
pub extern "C" fn call() {
    let _hash = contract_api::store_function("mint_ext", BTreeMap::new());
}
