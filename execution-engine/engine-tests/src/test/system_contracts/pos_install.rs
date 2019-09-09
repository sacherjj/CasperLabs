use contract_ffi::key::Key;
use contract_ffi::uref::{AccessRights, URef};
use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::U512;
use contract_ffi::value::Value;
use engine_core::engine_state::MAX_PAYMENT;
use engine_shared::transform::Transform;
use std::collections::{BTreeMap, HashMap};

use crate::support::exec_with_return;
use crate::support::test_support::{
    DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT, WasmTestBuilder,
};

const GENESIS_ADDR: [u8; 32] = [7u8; 32];
const SYSTEM_ADDR: [u8; 32] = [0u8; 32];
const DEPLOY_HASH_1: [u8; 32] = [1u8; 32];
const DEPLOY_HASH_2: [u8; 32] = [2u8; 32];
const N_VALIDATORS: u8 = 5;

// one known_uref for each validator, one for the mint and three for the purses
const EXPECTED_UREFS_LEN: usize = (N_VALIDATORS as usize) + 1 + 3;

const POS_BONDING_PURSE: &str = "pos_bonding_purse";
const POS_PAYMENT_PURSE: &str = "pos_payment_purse";
const POS_REWARDS_PURSE: &str = "pos_rewards_purse";

#[ignore]
#[test]
fn should_run_pos_install_contract() {
    let mut builder = WasmTestBuilder::default();

    builder
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT), ),
            "transfer_to_account_01.wasm",
            (SYSTEM_ADDR, ),
            DEFAULT_BLOCK_TIME,
            DEPLOY_HASH_1,
        )
        .commit()
        .expect_success();

    let mint_uref = URef::new(builder.get_mint_contract_uref().addr(), AccessRights::READ);
    let genesis_validators: BTreeMap<PublicKey, U512> = (1u8..=N_VALIDATORS)
        .map(|i| (PublicKey::new([i; 32]), U512::from(i)))
        .collect();

    let total_bond = genesis_validators.values().fold(U512::zero(), |x, y| x + y);

    let (ret_value, ret_urefs, effect): (URef, _, _) = exec_with_return::exec(
        &mut builder,
        SYSTEM_ADDR,
        "pos_install.wasm",
        DEFAULT_BLOCK_TIME,
        DEPLOY_HASH_2,
        (mint_uref, genesis_validators),
        vec![mint_uref],
    )
        .expect("should run successfully");

    let prestate = builder.get_poststate_hash();
    builder.commit_effects(prestate, effect.transforms.clone());

    // should return a uref
    assert_eq!(ret_value, ret_urefs[0]);

    // should have written a contract under that uref
    let known_urefs = match effect
        .transforms
        .get(&Key::URef(ret_value.remove_access_rights()))
        {
            Some(Transform::Write(Value::Contract(contract))) => contract.urefs_lookup(),

            _ => panic!("Expected contract to be written under the key"),
        };

    assert_eq!(known_urefs.len(), EXPECTED_UREFS_LEN);

    // bonding purse has correct balance
    let bonding_purse = get_purse(known_urefs, POS_BONDING_PURSE)
        .expect("should find bonding purse in known_urefs");

    let bonding_purse_balance = builder.get_purse_balance(bonding_purse);
    assert_eq!(bonding_purse_balance, total_bond);

    // payment purse has correct balance
    let payment_purse = get_purse(known_urefs, POS_PAYMENT_PURSE)
        .expect("should find payment purse in known_urefs");

    let payment_purse_balance = builder.get_purse_balance(payment_purse);
    assert_eq!(payment_purse_balance, U512::zero());

    // rewards purse has correct balance
    let rewards_purse = get_purse(known_urefs, POS_REWARDS_PURSE)
        .expect("should find rewards purse in known_urefs");

    let rewards_purse_balance = builder.get_purse_balance(rewards_purse);
    assert_eq!(rewards_purse_balance, U512::zero());
}

fn get_purse(known_urefs: &BTreeMap<String, Key>, name: &str) -> Option<PurseId> {
    known_urefs
        .get(name)
        .and_then(Key::as_uref)
        .cloned()
        .map(PurseId::new)
}
