use contract_ffi::base16;
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;
use engine_core::engine_state::{CONV_RATE, MAX_PAYMENT};
use engine_shared::motes::Motes;
use engine_shared::transform::Transform;

use crate::support::test_support::{
    get_exec_costs, WasmTestBuilder, DEFAULT_BLOCK_TIME, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const TEST_PURSE_NAME: &str = "test_purse";
const ACCOUNT_1_INITIAL_BALANCE: u64 = MAX_PAYMENT;

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
            .run_genesis(&DEFAULT_GENESIS_CONFIG)
            .exec_with_args(
                DEFAULT_ACCOUNT_ADDR,
                STANDARD_PAYMENT_CONTRACT,
                (U512::from(MAX_PAYMENT),),
                "transfer_purse_to_account.wasm",
                (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
                DEFAULT_BLOCK_TIME,
                [1; 32],
            )
            .expect_success()
            .commit()
            .exec_with_args(
                ACCOUNT_1_ADDR,
                STANDARD_PAYMENT_CONTRACT,
                (U512::from(MAX_PAYMENT),),
                "create_purse_01.wasm",
                (TEST_PURSE_NAME,),
                DEFAULT_BLOCK_TIME,
                [1; 32],
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
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "transfer_purse_to_account.wasm",
            (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
            DEFAULT_BLOCK_TIME,
            [1; 32],
        )
        .expect_success()
        .commit()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "create_purse_01.wasm",
            (TEST_PURSE_NAME,),
            DEFAULT_BLOCK_TIME,
            [1; 32],
        )
        .expect_success()
        .commit()
        .finish()
        .builder()
        .get_account(ACCOUNT_1_ADDR)
        .expect("should have account");

    assert!(
        account_1.urefs_lookup().contains_key(TEST_PURSE_NAME),
        "account_1 known_urefs should include test purse"
    );
}

#[ignore]
#[test]
fn should_create_usable_purse_id() {
    let result = WasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_args(
            DEFAULT_ACCOUNT_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "transfer_purse_to_account.wasm",
            (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
            DEFAULT_BLOCK_TIME,
            [1; 32],
        )
        .expect_success()
        .commit()
        .exec_with_args(
            ACCOUNT_1_ADDR,
            STANDARD_PAYMENT_CONTRACT,
            (U512::from(MAX_PAYMENT),),
            "create_purse_01.wasm",
            (TEST_PURSE_NAME,),
            DEFAULT_BLOCK_TIME,
            [1; 32],
        )
        .expect_success()
        .commit()
        .finish();

    let exec_response = result
        .builder()
        .get_exec_response(1)
        .expect("should have exec response 1");

    let account_1 = result
        .builder()
        .get_account(ACCOUNT_1_ADDR)
        .expect("should have account");

    let purse_key = account_1
        .urefs_lookup()
        .get(TEST_PURSE_NAME)
        .expect("should have known_uref");

    let purse_id = PurseId::new(*purse_key.as_uref().expect("should have uref"));

    let gas_cost =
        Motes::from_gas(get_exec_costs(&exec_response)[0], CONV_RATE).expect("should convert");

    let purse_balance = result.builder().get_purse_balance(purse_id);
    assert_eq!(
        purse_balance,
        U512::from(ACCOUNT_1_INITIAL_BALANCE) - gas_cost.value(),
        "when created directly a purse has 0 balance"
    );
}
