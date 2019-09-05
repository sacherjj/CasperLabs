use std::collections::HashMap;

use crate::support::test_support::{WasmTestBuilder, DEFAULT_BLOCK_TIME};

use contract_ffi::base16;

use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;
use engine_shared::transform::Transform;

const GENESIS_ADDR: [u8; 32] = [6u8; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const TEST_PURSE_NAME: &str = "test_purse";

fn get_purse_key_from_mint_transform(mint_transform: &Transform) -> Key {
    let keys = if let Transform::AddKeys(keys) = mint_transform {
        keys
    } else {
        panic!(
            "Mint transform is expected to be an AddKeys variant instead got {:?}",
            mint_transform
        );
    };

    // Exactly one new key which is the new purse created
    assert_eq!(keys.len(), 1);
    let (map_key, map_value) = keys.iter().nth(0).unwrap();

    // Decode uref name
    assert!(
        map_key.starts_with("uref-"),
        format!(
            "expected uref to start with uref- but the map contains {:?}",
            keys
        )
    );

    let decoded_purse_id = base16::decode_lower(&map_key[5..69]).expect("should decode base16");
    assert_eq!(decoded_purse_id.len(), 32);

    *map_value
}

#[ignore]
#[test]
fn should_insert_mint_add_keys_transform() {
    let mint_transform: &Transform = {
        let result = WasmTestBuilder::default()
            .run_genesis(GENESIS_ADDR, HashMap::new())
            .exec_with_args(
                GENESIS_ADDR,
                "transfer_to_account_01.wasm",
                DEFAULT_BLOCK_TIME,
                [1; 32],
                (ACCOUNT_1_ADDR,),
            )
            .expect_success()
            .commit()
            .exec_with_args(
                ACCOUNT_1_ADDR,
                "create_purse_01.wasm",
                DEFAULT_BLOCK_TIME,
                [1; 32],
                (TEST_PURSE_NAME,),
            )
            .expect_success()
            .commit()
            .finish();

        let mint_contract_uref = result.builder().get_mint_contract_uref();
        &result.builder().get_transforms()[0][&mint_contract_uref.remove_access_rights().into()]
    };

    get_purse_key_from_mint_transform(mint_transform); // <-- assert equivalent
}

#[ignore]
#[test]
fn should_insert_into_account_known_urefs() {
    let account_1 = WasmTestBuilder::default()
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            [1; 32],
            (ACCOUNT_1_ADDR,),
        )
        .expect_success()
        .commit()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "create_purse_01.wasm",
            DEFAULT_BLOCK_TIME,
            [1; 32],
            (TEST_PURSE_NAME,),
        )
        .expect_success()
        .commit()
        .finish()
        .builder()
        .get_account(Key::Account(ACCOUNT_1_ADDR))
        .expect("should have account");

    assert!(
        account_1.urefs_lookup().contains_key(TEST_PURSE_NAME),
        "account_1 known_urefs should include test purse"
    );
}

#[ignore]
#[test]
fn should_create_usable_purse_id() {
    let mut builder = WasmTestBuilder::default();
    let purse_key = *builder
        .run_genesis(GENESIS_ADDR, HashMap::new())
        .exec_with_args(
            GENESIS_ADDR,
            "transfer_to_account_01.wasm",
            DEFAULT_BLOCK_TIME,
            [1; 32],
            (ACCOUNT_1_ADDR,),
        )
        .expect_success()
        .commit()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            "create_purse_01.wasm",
            DEFAULT_BLOCK_TIME,
            [1; 32],
            (TEST_PURSE_NAME,),
        )
        .expect_success()
        .commit()
        .finish()
        .builder()
        .get_account(Key::Account(ACCOUNT_1_ADDR))
        .expect("should have account")
        .urefs_lookup()
        .get(TEST_PURSE_NAME)
        .expect("should have known_uref");

    let purse_id = PurseId::new(*purse_key.as_uref().expect("should have uref"));

    let purse_balance = builder.get_purse_balance(purse_id);
    assert_eq!(
        purse_balance,
        U512::from(0),
        "when created directly a purse has 0 balance"
    );
}
