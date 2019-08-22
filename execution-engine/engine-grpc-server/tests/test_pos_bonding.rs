extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;
extern crate grpc;

use std::collections::HashMap;
use std::convert::TryInto;

use contract_ffi::base16;
use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::Account;
use contract_ffi::value::Contract;
use contract_ffi::value::{Value, U512};

use engine_core::engine_state::genesis::POS_BONDING_PURSE;
use engine_core::execution::POS_NAME;
use engine_shared::transform::Transform;

use test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

#[allow(unused)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];

fn get_pos_contract(builder: &WasmTestBuilder) -> Contract {
    let genesis_key = Key::Account(GENESIS_ADDR);
    let pos_uref: Key = builder
        .query(None, genesis_key, &[POS_NAME])
        .and_then(|v| v.try_into().ok())
        .expect("should find PoS URef");

    builder
        .query(None, pos_uref, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should find PoS Contract")
}

fn get_pos_purse_id_by_name(builder: &WasmTestBuilder, purse_name: &str) -> Option<PurseId> {
    let pos_contract = get_pos_contract(builder);

    pos_contract
        .urefs_lookup()
        .get(purse_name)
        .and_then(Key::as_uref)
        .map(|u| PurseId::new(*u))
}

fn get_purse_balance(builder: &WasmTestBuilder, purse_id: PurseId) -> U512 {
    let mint = builder.get_mint_contract_uref();
    let purse_bytes = purse_id
        .value()
        .addr()
        .to_bytes()
        .expect("should be able to serialize purse bytes");

    let balance_mapping_key = Key::local(mint.addr(), &purse_bytes);
    let balance_uref = builder
        .query(None, balance_mapping_key, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should find balance uref");

    builder
        .query(None, balance_uref, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should parse balance into a U512")
}

fn get_pos_bonding_purse_balance(builder: &WasmTestBuilder) -> U512 {
    let purse_id = get_pos_purse_id_by_name(builder, POS_BONDING_PURSE)
        .expect("should find PoS payment purse");
    get_purse_balance(builder, purse_id)
}

fn get_account(builder: &WasmTestBuilder, key: Key) -> Option<Account> {
    let account_value = builder.query(None, key, &[]).expect("should query account");
    if let Value::Account(account) = account_value {
        Some(account)
    } else {
        None
    }
}

#[ignore]
#[test]
fn should_run_successful_bond_and_unbond() {
    let genesis_account_key = Key::Account(GENESIS_ADDR);
    let genesis_validators = {
        let mut result = HashMap::new();
        result.insert(PublicKey::new([42; 32]), U512::from(50_000));
        result
    };

    let result = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, genesis_validators)
        .exec_with_args(
            GENESIS_ADDR,
            "pos_bonding.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (String::from("bond"), U512::from(100_000)),
        )
        .expect_success()
        .commit()
        .finish();
    let genesis_account =
        get_account(result.builder(), genesis_account_key).expect("should get account 1");

    let pos = result.builder().get_pos_contract_uref();

    let transforms = &result.builder().get_transforms()[0];

    let pos_transform = &transforms[&pos.into()];

    // Verify that genesis account is in validator queue
    let add_keys = if let Transform::AddKeys(keys) = pos_transform {
        keys
    } else {
        panic!("pos transform is expected to be of AddKeys variant");
    };

    let lookup_key = format!("v_{}_{}", base16::encode_lower(&GENESIS_ADDR), 100_000);
    assert!(add_keys.contains_key(&lookup_key));

    // Gensis validator [42; 32] bonded 50k, and genesis account bonded 100k inside the test contract
    assert_eq!(
        get_pos_bonding_purse_balance(result.builder()),
        U512::from(50_000 + 100_000)
    );

    // Create new account (from genesis funds) and bond with it
    let result = WasmTestBuilder::from_result(result)
        .exec_with_args(
            GENESIS_ADDR,
            "pos_bonding.wasm",
            DEFAULT_BLOCK_TIME,
            2,
            (
                String::from("seed_new_account"),
                PublicKey::new(ACCOUNT_1_ADDR),
                U512::from(1_000_000),
            ),
        )
        .expect_success()
        .commit()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "pos_bonding.wasm",
            DEFAULT_BLOCK_TIME,
            1,
            (String::from("bond"), U512::from(42_000)),
        )
        .expect_success()
        .commit()
        .finish();

    let account_1_key = Key::Account(ACCOUNT_1_ADDR);

    let account_1 = get_account(result.builder(), account_1_key).expect("should get account 1");

    let pos = result.builder().get_pos_contract_uref();

    let transforms = &result.builder().get_transforms()[1];

    let pos_transform = &transforms[&pos.into()];

    // Verify that genesis account is in validator queue
    let add_keys = if let Transform::AddKeys(keys) = pos_transform {
        keys
    } else {
        panic!("pos transform is expected to be of AddKeys variant");
    };

    let lookup_key = format!("v_{}_{}", base16::encode_lower(&ACCOUNT_1_ADDR), 42_000);
    assert!(add_keys.contains_key(&lookup_key));

    // Gensis validator [42; 32] bonded 50k, and genesis account bonded 100k inside the test contract
    assert_eq!(
        get_pos_bonding_purse_balance(result.builder()),
        U512::from(50_000 + 100_000 + 42_000)
    );

    //
    // Stage 2a - Account 1 unbonds by decreasing less than 50% (and is still in the queue)
    //

    let result = WasmTestBuilder::from_result(result)
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "pos_bonding.wasm",
            DEFAULT_BLOCK_TIME,
            2,
            (String::from("unbond"), Some(U512::from(20_000))),
        )
        .expect_success()
        .commit()
        .finish();

    assert_eq!(
        get_purse_balance(result.builder(), account_1.purse_id()),
        U512::from(1_000_000 - 42_000 + 20_000)
    );

    // POS bonding purse is decreased
    assert_eq!(
        get_pos_bonding_purse_balance(result.builder()),
        U512::from(50_000 + 100_000 + 22_000)
    );

    let pos_contract = get_pos_contract(result.builder());

    let lookup_key = format!("v_{}_{}", base16::encode_lower(&ACCOUNT_1_ADDR), 42_000);
    assert!(!pos_contract.urefs_lookup().contains_key(&lookup_key));

    let lookup_key = format!("v_{}_{}", base16::encode_lower(&ACCOUNT_1_ADDR), 22_000);
    // Account 1 is still tracked anymore in the bonding queue with different uref name
    assert!(pos_contract.urefs_lookup().contains_key(&lookup_key));

    //
    // Stage 2b - Genesis unbonds by decreasing less than 50% (and is still in the queue)
    //
    // Genesis account unbonds less than 50% of his stake
    let result = WasmTestBuilder::from_result(result)
        .exec_with_args(
            GENESIS_ADDR,
            "pos_bonding.wasm",
            DEFAULT_BLOCK_TIME,
            3,
            (String::from("unbond"), Some(U512::from(45_000))),
        )
        .expect_success()
        .commit()
        .finish();

    assert_eq!(
        get_purse_balance(result.builder(), genesis_account.purse_id()),
        U512::from(test_support::GENESIS_INITIAL_BALANCE - 1_000_000 - 55_000)
    );

    // POS bonding purse is further decreased
    assert_eq!(
        get_pos_bonding_purse_balance(result.builder()),
        U512::from(50_000 + 55_000 + 22_000)
    );

    //
    // Stage 3a - Fully unbond account1 with Some(TOTAL_AMOUNT)
    //
    let result = WasmTestBuilder::from_result(result)
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "pos_bonding.wasm",
            DEFAULT_BLOCK_TIME,
            3,
            (String::from("unbond"), Some(U512::from(22_000))), // <-- rest of accont1's funds
        )
        .expect_success()
        .commit()
        .finish();

    assert_eq!(
        get_purse_balance(result.builder(), account_1.purse_id()),
        U512::from(1_000_000)
    );

    // POS bonding purse contains now genesis validator (50k) + genesis account (55k)
    assert_eq!(
        get_pos_bonding_purse_balance(result.builder()),
        U512::from(50_000 + 55_000)
    );

    let pos_contract = get_pos_contract(result.builder());

    let lookup_key = format!("v_{}_{}", base16::encode_lower(&ACCOUNT_1_ADDR), 22_000);
    // Account 1 isn't tracked anymore in the bonding queue
    assert!(!pos_contract.urefs_lookup().contains_key(&lookup_key));

    //
    // Stage 3b - Fully unbond account1 with Some(TOTAL_AMOUNT)
    //

    // Genesis account unbonds less than 50% of his stake
    let result = WasmTestBuilder::from_result(result)
        .exec_with_args(
            GENESIS_ADDR,
            "pos_bonding.wasm",
            DEFAULT_BLOCK_TIME,
            4,
            (String::from("unbond"), None as Option<U512>), // <-- va banque
        )
        .expect_success()
        .commit()
        .finish();

    // Back to original after funding account1's pursee
    assert_eq!(
        get_purse_balance(result.builder(), genesis_account.purse_id()),
        U512::from(test_support::GENESIS_INITIAL_BALANCE - 1_000_000)
    );

    // Final balance after two full unbonds is the initial bond valuee
    assert_eq!(
        get_pos_bonding_purse_balance(result.builder()),
        U512::from(50_000)
    );

    let pos_contract = get_pos_contract(result.builder());
    let lookup_key = format!("v_{}_{}", base16::encode_lower(&GENESIS_ADDR), 55_000);
    // Genesis is still tracked anymore in the bonding queue with different uref name
    assert!(!pos_contract.urefs_lookup().contains_key(&lookup_key));

    //
    // Final checks on validator queue
    //

    // Account 1 is still tracked anymore in the bonding queue with any amount suffix
    assert_eq!(
        pos_contract
            .urefs_lookup()
            .iter()
            .filter(
                |(key, _)| key.starts_with(&format!("v_{}", base16::encode_lower(&GENESIS_ADDR)))
            )
            .count(),
        0
    );
    assert_eq!(
        pos_contract
            .urefs_lookup()
            .iter()
            .filter(
                |(key, _)| key.starts_with(&format!("v_{}", base16::encode_lower(&ACCOUNT_1_ADDR)))
            )
            .count(),
        0
    );
    // only genesis validator is still in the queue
    assert_eq!(
        pos_contract
            .urefs_lookup()
            .iter()
            .filter(|(key, _)| key.starts_with("v_"))
            .count(),
        1
    );
}
