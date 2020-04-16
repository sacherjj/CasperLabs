#![no_std]
#![no_main]

extern crate alloc;

use alloc::{collections::BTreeMap, string::String};

use contract::{
    contract_api::{runtime, storage},
    unwrap_or_revert::UnwrapOrRevert,
};
use proof_of_stake::Stakes;
use types::{
    account::PublicKey, system_contract_errors::mint, AccessRights, ApiError, CLValue, ContractRef,
    Key, URef, U512,
};

const PLACEHOLDER_KEY: Key = Key::Hash([0u8; 32]);
const POS_BONDING_PURSE: &str = "pos_bonding_purse";
const POS_PAYMENT_PURSE: &str = "pos_payment_purse";
const POS_REWARDS_PURSE: &str = "pos_rewards_purse";
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
    let mint_uref: URef = runtime::get_arg(Args::MintURef as u32)
        .unwrap_or_revert_with(ApiError::MissingArgument)
        .unwrap_or_revert_with(ApiError::InvalidArgument);
    let mint = ContractRef::URef(URef::new(mint_uref.addr(), AccessRights::READ));

    let genesis_validators: BTreeMap<PublicKey, U512> =
        runtime::get_arg(Args::GenesisValidators as u32)
            .unwrap_or_revert_with(ApiError::MissingArgument)
            .unwrap_or_revert_with(ApiError::InvalidArgument);

    let stakes = Stakes::new(genesis_validators);

    // Add genesis validators to PoS contract object.
    // For now, we are storing validators in `named_keys` map of the PoS contract
    // in the form: key: "v_{validator_pk}_{validator_stake}", value: doesn't
    // matter.
    let mut named_keys: BTreeMap<String, Key> =
        stakes.strings().map(|key| (key, PLACEHOLDER_KEY)).collect();

    let total_bonds: U512 = stakes.total_bonds();

    let bonding_purse = mint_purse(&mint, total_bonds);
    let payment_purse = mint_purse(&mint, U512::zero());
    let rewards_purse = mint_purse(&mint, U512::zero());

    // Include PoS purses in its named_keys
    [
        (POS_BONDING_PURSE, bonding_purse),
        (POS_PAYMENT_PURSE, payment_purse),
        (POS_REWARDS_PURSE, rewards_purse),
    ]
    .iter()
    .for_each(|(name, uref)| {
        named_keys.insert(String::from(*name), Key::URef(*uref));
    });

    let uref: URef = storage::store_function(POS_FUNCTION_NAME, named_keys)
        .into_uref()
        .unwrap_or_revert_with(ApiError::UnexpectedContractRefVariant);
    let return_value = CLValue::from_t(uref).unwrap_or_revert();

    runtime::ret(return_value);
}

fn mint_purse(mint: &ContractRef, amount: U512) -> URef {
    let result: Result<URef, mint::Error> = runtime::call_contract(mint.clone(), ("mint", amount));

    result.unwrap_or_revert()
}
