use base16;
use lazy_static::lazy_static;

use engine_shared::transform::Transform;
use engine_test_support::low_level::{
    ExecuteRequestBuilder, WasmTestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG,
    DEFAULT_PAYMENT,
};
use types::{account::PurseId, Key, U512};

const CONTRACT_CREATE_PURSE_01: &str = "create_purse_01.wasm";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const TEST_PURSE_NAME: &str = "test_purse";

lazy_static! {
    static ref ACCOUNT_1_INITIAL_BALANCE: U512 = *DEFAULT_PAYMENT;
}

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
    let (map_key, map_value) = keys.iter().next().unwrap();

    // Decode uref name
    assert!(
        map_key.starts_with("uref-"),
        format!(
            "expected uref to start with uref- but the map contains {:?}",
            keys
        )
    );

    let decoded_purse_id = base16::decode(&map_key[5..69]).expect("should decode base16");
    assert_eq!(decoded_purse_id.len(), 32);

    *map_value
}

#[ignore]
#[test]
fn should_insert_mint_add_keys_transform() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *ACCOUNT_1_INITIAL_BALANCE),
    )
    .build();
    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_CREATE_PURSE_01,
        (TEST_PURSE_NAME,),
    )
    .build();

    let mint_transform: &Transform = {
        let result = WasmTestBuilder::default()
            .run_genesis(&DEFAULT_GENESIS_CONFIG)
            .exec(exec_request_1)
            .expect_success()
            .commit()
            .exec(exec_request_2)
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
fn should_insert_account_into_named_keys() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *ACCOUNT_1_INITIAL_BALANCE),
    )
    .build();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_CREATE_PURSE_01,
        (TEST_PURSE_NAME,),
    )
    .build();
    let account_1 = WasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit()
        .finish()
        .builder()
        .get_account(ACCOUNT_1_ADDR)
        .expect("should have account");

    assert!(
        account_1.named_keys().contains_key(TEST_PURSE_NAME),
        "account_1 named_keys should include test purse"
    );
}

#[ignore]
#[test]
fn should_create_usable_purse_id() {
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (ACCOUNT_1_ADDR, *ACCOUNT_1_INITIAL_BALANCE),
    )
    .build();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        ACCOUNT_1_ADDR,
        CONTRACT_CREATE_PURSE_01,
        (TEST_PURSE_NAME,),
    )
    .build();
    let result = WasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit()
        .finish();

    let account_1 = result
        .builder()
        .get_account(ACCOUNT_1_ADDR)
        .expect("should have account");

    let purse_key = account_1
        .named_keys()
        .get(TEST_PURSE_NAME)
        .expect("should have known key");

    let purse_id = PurseId::new(*purse_key.as_uref().expect("should have uref"));

    let purse_balance = result.builder().get_purse_balance(purse_id);
    assert!(
        purse_balance.is_zero(),
        "when created directly a purse has 0 balance"
    );
}
