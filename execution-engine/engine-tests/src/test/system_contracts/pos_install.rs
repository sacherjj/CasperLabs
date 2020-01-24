use std::collections::BTreeMap;

use engine_shared::{stored_value::StoredValue, transform::Transform};
use engine_test_support::low_level::{
    exec_with_return, ExecuteRequestBuilder, WasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_BLOCK_TIME, DEFAULT_GENESIS_CONFIG,
};
use types::{
    account::{PublicKey, PurseId},
    AccessRights, Key, URef, U512,
};

const CONTRACT_TRANSFER_TO_ACCOUNT_01: &str = "transfer_to_account_01.wasm";
const SYSTEM_ADDR: [u8; 32] = [0u8; 32];
const DEPLOY_HASH_2: [u8; 32] = [2u8; 32];
const N_VALIDATORS: u8 = 5;

// one named_key for each validator and three for the purses
const EXPECTED_KNOWN_KEYS_LEN: usize = (N_VALIDATORS as usize) + 3;

const POS_BONDING_PURSE: &str = "pos_bonding_purse";
const POS_PAYMENT_PURSE: &str = "pos_payment_purse";
const POS_REWARDS_PURSE: &str = "pos_rewards_purse";

#[ignore]
#[test]
fn should_run_pos_install_contract() {
    let mut builder = WasmTestBuilder::default();

    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_TO_ACCOUNT_01,
        (SYSTEM_ADDR,),
    )
    .build();
    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
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

    let prestate = builder.get_post_state_hash();
    builder.commit_effects(prestate, effect.transforms.clone());

    // should return a uref
    assert_eq!(ret_value, ret_urefs[0]);

    // should have written a contract under that uref
    let named_keys = match effect
        .transforms
        .get(&Key::URef(ret_value.remove_access_rights()))
    {
        Some(Transform::Write(StoredValue::Contract(contract))) => contract.named_keys(),

        _ => panic!("Expected contract to be written under the key"),
    };

    assert_eq!(named_keys.len(), EXPECTED_KNOWN_KEYS_LEN);

    // bonding purse has correct balance
    let bonding_purse =
        get_purse(named_keys, POS_BONDING_PURSE).expect("should find bonding purse in named_keys");

    let bonding_purse_balance = builder.get_purse_balance(bonding_purse);
    assert_eq!(bonding_purse_balance, total_bond);

    // payment purse has correct balance
    let payment_purse =
        get_purse(named_keys, POS_PAYMENT_PURSE).expect("should find payment purse in named_keys");

    let payment_purse_balance = builder.get_purse_balance(payment_purse);
    assert_eq!(payment_purse_balance, U512::zero());

    // rewards purse has correct balance
    let rewards_purse =
        get_purse(named_keys, POS_REWARDS_PURSE).expect("should find rewards purse in named_keys");

    let rewards_purse_balance = builder.get_purse_balance(rewards_purse);
    assert_eq!(rewards_purse_balance, U512::zero());
}

fn get_purse(named_keys: &BTreeMap<String, Key>, name: &str) -> Option<PurseId> {
    named_keys
        .get(name)
        .and_then(Key::as_uref)
        .cloned()
        .map(PurseId::new)
}
