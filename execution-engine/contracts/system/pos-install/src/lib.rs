#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;
extern crate pos;

use alloc::collections::BTreeMap;
use alloc::string::String;
use contract_ffi::contract_api::pointers::{ContractPointer, TURef};
use contract_ffi::contract_api::{self, Error};
use contract_ffi::key::Key;
use contract_ffi::system_contracts::mint;
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::U512;
use core::fmt::Write;

const PLACEHOLDER_KEY: Key = Key::Hash([0u8; 32]);
const POS_BONDING_PURSE: &str = "pos_bonding_purse";
const POS_PAYMENT_PURSE: &str = "pos_payment_purse";
const POS_REWARDS_PURSE: &str = "pos_rewards_purse";
const MINT_NAME: &str = "mint";
const POS_FUNCTION_NAME: &str = "pos_ext";

#[repr(u32)]
enum Args {
    MintURef = 0,
    GenesisValidators = 1,
}

#[no_mangle]
pub extern "C" fn pos_ext() {
    pos::delegate();
}

#[no_mangle]
pub extern "C" fn call() {
    let mint_uref: URef = match contract_api::get_arg(Args::MintURef as u32) {
        Some(Ok(data)) => data,
        Some(Err(_)) => contract_api::revert(Error::InvalidArgument),
        None => contract_api::revert(Error::MissingArgument),
    };
    let mint = ContractPointer::URef(TURef::new(mint_uref.addr(), AccessRights::READ));

    let genesis_validators: BTreeMap<PublicKey, U512> =
        match contract_api::get_arg(Args::GenesisValidators as u32) {
            Some(Ok(data)) => data,
            Some(Err(_)) => contract_api::revert(Error::InvalidArgument),
            None => contract_api::revert(Error::MissingArgument),
        };

    // Add genesis validators to PoS contract object.
    // For now, we are storing validators in `named_keys` map of the PoS contract
    // in the form: key: "v_{validator_pk}_{validator_stake}", value: doesn't
    // matter.
    let mut named_keys: BTreeMap<String, Key> = genesis_validators
        .iter()
        .map(|(pub_key, balance)| {
            let key_bytes = pub_key.value();
            let mut hex_key = String::with_capacity(64);
            for byte in &key_bytes[..32] {
                write!(hex_key, "{:02x}", byte).unwrap();
            }
            let mut uref = String::new();
            uref.write_fmt(format_args!("v_{}_{}", hex_key, balance))
                .unwrap();
            uref
        })
        .map(|key| (key, PLACEHOLDER_KEY))
        .collect();

    // Include the mint contract in its named_keys
    named_keys.insert(String::from(MINT_NAME), Key::URef(mint_uref));

    let total_bonds: U512 = genesis_validators.values().fold(U512::zero(), |x, y| x + y);

    let bonding_purse = mint_purse(&mint, total_bonds);
    let payment_purse = mint_purse(&mint, U512::zero());
    let rewards_purse = mint_purse(&mint, U512::zero());

    // Include PoS purses in its named_keys
    [
        (POS_BONDING_PURSE, bonding_purse.value()),
        (POS_PAYMENT_PURSE, payment_purse.value()),
        (POS_REWARDS_PURSE, rewards_purse.value()),
    ]
    .iter()
    .for_each(|(name, uref)| {
        named_keys.insert(String::from(*name), Key::URef(*uref));
    });

    let uref = contract_api::store_function(POS_FUNCTION_NAME, named_keys)
        .into_turef()
        .unwrap_or_else(|| contract_api::revert(Error::UnexpectedContractPointerVariant))
        .into();

    contract_api::ret(&uref, &vec![uref]);
}

fn mint_purse(mint: &ContractPointer, amount: U512) -> PurseId {
    let result: Result<URef, mint::error::Error> =
        contract_api::call_contract(mint.clone(), &("mint", amount), &vec![]);

    result
        .map(PurseId::new)
        .unwrap_or_else(|_| contract_api::revert(Error::MintFailure))
}
