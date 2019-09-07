#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
extern crate pos;

use alloc::collections::BTreeMap;
use alloc::string::String;
use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::{ContractPointer, UPointer};
use contract_ffi::key::Key;
use contract_ffi::system_contracts::mint;
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

const POS_PAYMENT_PURSE: &str = "pos_payment_purse";
const POS_REWARDS_PURSE: &str = "pos_rewards_purse";

#[repr(u32)]
enum Error {
    MintFailure = 0,
}

#[repr(u32)]
enum Args {
    MintURef = 0,
}

#[no_mangle]
pub extern "C" fn pos_ext() {
    pos::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let mint_uref: URef = contract_api::get_arg(Args::MintURef as u32);
    let mint = ContractPointer::URef(UPointer::new(mint_uref.addr(), AccessRights::READ));

    // TODO: set up bonded validators and bonding purse

    let payment_purse = mint_purse(&mint, U512::zero());
    let rewards_purse = mint_purse(&mint, U512::zero());

    let known_urefs: BTreeMap<String, Key> = [
        (POS_PAYMENT_PURSE, payment_purse.value()),
        (POS_REWARDS_PURSE, rewards_purse.value()),
    ]
    .iter()
    .map(|(name, uref)| (String::from(*name), Key::URef(*uref)))
    .collect();

    let contract = contract_api::fn_by_name("pos_ext", known_urefs);
    let uref: URef = contract_api::new_uref(contract).into();

    contract_api::ret(&uref, &vec![uref]);
}

fn mint_purse(mint: &ContractPointer, amount: U512) -> PurseId {
    let result: Result<URef, mint::error::Error> =
        contract_api::call_contract(mint.clone(), &("mint", amount), &vec![]);

    result
        .map(PurseId::new)
        .unwrap_or_else(|_| contract_api::revert(Error::MintFailure as u32))
}
