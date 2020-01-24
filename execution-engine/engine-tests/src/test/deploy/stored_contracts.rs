use std::collections::{hash_map::RandomState, BTreeMap};

use engine_core::engine_state::{upgrade::ActivationPoint, CONV_RATE};
use engine_grpc_server::engine_server::ipc::DeployCode;
use engine_shared::{
    additive_map::AdditiveMap, motes::Motes, stored_value::StoredValue, transform::Transform,
};
use engine_test_support::low_level::{
    utils, AdditiveMapDiff, DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder,
    UpgradeRequestBuilder, DEFAULT_ACCOUNT_ADDR, DEFAULT_ACCOUNT_INITIAL_BALANCE,
    DEFAULT_ACCOUNT_KEY, DEFAULT_GENESIS_CONFIG,
};
use types::{account::PublicKey, Key, ProtocolVersion, U512};

const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];
const DEFAULT_ACTIVATION_POINT: ActivationPoint = 1;
const DO_NOTHING_NAME: &str = "do_nothing";
const DO_NOTHING_STORED_CONTRACT_NAME: &str = "do_nothing_stored";
const MODIFIED_MINT_UPGRADER_CONTRACT_NAME: &str = "modified_mint_upgrader.wasm";
const MODIFIED_SYSTEM_UPGRADER_CONTRACT_NAME: &str = "modified_system_upgrader.wasm";
const PROTOCOL_VERSION: ProtocolVersion = ProtocolVersion::V1_0_0;
const STANDARD_PAYMENT_CONTRACT_NAME: &str = "standard_payment";
const STORE_AT_HASH: &str = "hash";
const STORE_AT_UREF: &str = "uref";
const TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME: &str = "transfer_purse_to_account";
// Currently Error enum that holds this variant is private and can't be used otherwise to compare
// message
const EXPECTED_ERROR_MESSAGE: &str = "IncompatibleProtocolMajorVersion { expected: 2, actual: 1 }";

/// Prepares a upgrade request with pre-loaded deploy code, and new protocol version.
fn make_upgrade_request(
    new_protocol_version: ProtocolVersion,
    code: &str,
) -> UpgradeRequestBuilder {
    let installer_code = {
        let bytes = utils::read_wasm_file_bytes(code);
        let mut deploy_code = DeployCode::new();
        deploy_code.set_code(bytes);
        deploy_code
    };

    UpgradeRequestBuilder::new()
        .with_current_protocol_version(PROTOCOL_VERSION)
        .with_new_protocol_version(new_protocol_version)
        .with_activation_point(DEFAULT_ACTIVATION_POINT)
        .with_installer_code(installer_code)
}

#[ignore]
#[test]
fn should_exec_non_stored_code() {
    // using the new execute logic, passing code for both payment and session
    // should work exactly as it did with the original exec logic

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 10_000_000;
    let transferred_amount = 1;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([1; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let test_result = builder.exec_commit_finish(exec_request);

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance: U512 = builder.get_purse_balance(default_account.purse_id());

    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);

    assert_ne!(
        modified_balance, initial_balance,
        "balance should be less than initial balance"
    );

    let response = test_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let success_result = utils::get_success_result(&response);
    let gas = success_result.cost();
    let motes = Motes::from_gas(gas, CONV_RATE).expect("should have motes");
    let tally = motes.value() + U512::from(transferred_amount) + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}

#[ignore]
#[test]
fn should_exec_stored_code_by_hash() {
    let payment_purse_amount = 10_000_000;

    // first, store standard payment contract
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
        (STORE_AT_HASH.to_string(),),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let test_result = builder.exec_commit_finish(exec_request);

    let response = test_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let transforms = &test_result.builder().get_transforms()[0];

    // find the contract write transform, then get the hash from its key
    let stored_payment_contract_hash = {
        let mut ret = None;
        for (k, t) in transforms {
            if let Transform::Write(StoredValue::Contract(_)) = t {
                if let Key::Hash(hash) = k {
                    ret = Some(hash);
                    break;
                }
            }
        }
        ret
    };

    assert_ne!(
        stored_payment_contract_hash, None,
        "stored_payment_contract_hash should exist"
    );

    let mut result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_alpha = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance_alpha: U512 = builder.get_purse_balance(default_account.purse_id());

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = 1;

    // next make another deploy that USES stored payment logic
    let exec_request_stored_payment = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_stored_payment_hash(
                stored_payment_contract_hash
                    .expect("hash should exist")
                    .to_vec(),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = builder.exec_commit_finish(exec_request_stored_payment);

    let modified_balance_bravo: U512 = builder.get_purse_balance(default_account.purse_id());

    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_bravo = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let tally = motes_alpha.value()
        + motes_bravo.value()
        + U512::from(transferred_amount)
        + modified_balance_bravo;

    assert!(
        modified_balance_alpha < initial_balance,
        "balance should be less than initial balance"
    );

    assert!(
        modified_balance_bravo < modified_balance_alpha,
        "second modified balance should be less than first modified balance"
    );

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}

#[ignore]
#[test]
fn should_exec_stored_code_by_named_hash() {
    let payment_purse_amount = 10_000_000;

    // first, store standard payment contract
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
        (STORE_AT_HASH.to_string(),),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let test_result = builder.exec_commit_finish(exec_request);

    let response = test_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let mut result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_alpha = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance_alpha: U512 = builder.get_purse_balance(default_account.purse_id());

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = 1;

    // next make another deploy that USES stored payment logic
    let exec_request_stored_payment = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_stored_payment_named_key(
                STANDARD_PAYMENT_CONTRACT_NAME,
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = builder.exec_commit_finish(exec_request_stored_payment);

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance_bravo: U512 = builder.get_purse_balance(default_account.purse_id());

    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_bravo = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let tally = motes_alpha.value()
        + motes_bravo.value()
        + U512::from(transferred_amount)
        + modified_balance_bravo;

    assert!(
        modified_balance_alpha < initial_balance,
        "balance should be less than initial balance"
    );

    assert!(
        modified_balance_bravo < modified_balance_alpha,
        "second modified balance should be less than first modified balance"
    );

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}

#[ignore]
#[test]
fn should_exec_stored_code_by_named_uref() {
    let payment_purse_amount = 100_000_000; // <- seems like a lot, but it gets spent fast!

    // first, store transfer contract
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}_stored.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([1; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let test_result = builder.exec_commit_finish(exec_request);

    let response = test_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let mut result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_alpha = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance_alpha: U512 = builder.get_purse_balance(default_account.purse_id());

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = 1;

    // next make another deploy that USES stored session logic
    let exec_request_stored_session = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_session_named_key(
                TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME,
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = builder.exec_commit_finish(exec_request_stored_session);

    let modified_balance_bravo: U512 = builder.get_purse_balance(default_account.purse_id());

    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_bravo = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let tally = motes_alpha.value()
        + motes_bravo.value()
        + U512::from(transferred_amount)
        + modified_balance_bravo;

    assert!(
        modified_balance_alpha < initial_balance,
        "balance should be less than initial balance"
    );

    assert!(
        modified_balance_bravo < modified_balance_alpha,
        "second modified balance should be less than first modified balance"
    );

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}

#[ignore]
#[test]
fn should_exec_payment_and_session_stored_code() {
    let payment_purse_amount = 100_000_000; // <- seems like a lot, but it gets spent fast!

    // first, store standard payment contract
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
        (STORE_AT_HASH.to_string(),),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let test_result = builder.exec_commit_finish(exec_request);

    let response = test_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let mut result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_alpha = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    // next store transfer contract
    let exec_request_store_transfer = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}_stored.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (),
            )
            .with_stored_payment_named_key(
                STANDARD_PAYMENT_CONTRACT_NAME,
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = builder.exec_commit_finish(exec_request_store_transfer);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_bravo = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = 1;

    // next make another deploy that USES stored payment logic & stored transfer
    // logic
    let exec_request_stored_only = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_session_named_key(
                TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME,
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_stored_payment_named_key(
                STANDARD_PAYMENT_CONTRACT_NAME,
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = builder.exec_commit_finish(exec_request_stored_only);

    let response = test_result
        .builder()
        .get_exec_response(2)
        .expect("there should be a response")
        .clone();

    result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes_charlie = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance: U512 = builder.get_purse_balance(default_account.purse_id());

    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);

    let tally = motes_alpha.value()
        + motes_bravo.value()
        + motes_charlie.value()
        + U512::from(transferred_amount)
        + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}

#[ignore]
#[test]
fn should_produce_same_transforms_by_uref_or_named_uref() {
    // get transforms for direct uref and named uref and compare them

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 100_000_000;
    let transferred_amount = 1;

    // first, store transfer contract
    let exec_request_genesis = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}_stored.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([1u8; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder_by_uref = InMemoryWasmTestBuilder::default();
    builder_by_uref.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    let test_result = builder_by_uref.exec_commit_finish(exec_request_genesis);
    let transforms: &AdditiveMap<Key, Transform, RandomState> =
        &test_result.builder().get_transforms()[0];

    let stored_payment_contract_uref = {
        // get pos contract public key
        let pos_uref = builder_by_uref.get_pos_contract_uref();

        // find the contract write transform, then get the uref from its key
        // the pos contract gets re-written when the refund purse uref is removed from
        // it and therefore there are two URef->Contract Writes present in
        // transforms... we want to ignore the proof of stake URef as it is not
        // the one we are interested in
        let stored_payment_contract_uref = transforms
            .iter()
            .find_map(|key_transform| match key_transform {
                (Key::URef(uref), Transform::Write(StoredValue::Contract(_)))
                    if uref != &pos_uref =>
                {
                    Some(uref)
                }
                _ => None,
            })
            .expect("should have stored_payment_contract_uref");

        assert_ne!(
            &pos_uref, stored_payment_contract_uref,
            "should ignore the pos_uref"
        );

        stored_payment_contract_uref
    };

    // direct uref exec
    let exec_request_by_uref = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_session_uref(
                *stored_payment_contract_uref,
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2u8; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = builder_by_uref.exec_commit_finish(exec_request_by_uref);
    let direct_uref_transforms = &test_result.builder().get_transforms()[1];

    // requests aren't cloneable, so create another one
    let exec_request_genesis = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}_stored.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([1u8; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder_by_named_uref = InMemoryWasmTestBuilder::default();
    builder_by_named_uref.run_genesis(&*DEFAULT_GENESIS_CONFIG);
    let _ = builder_by_named_uref.exec_commit_finish(exec_request_genesis);

    // named uref exec
    let exec_request_by_named_uref = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_session_named_key(
                TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME,
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2u8; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = builder_by_named_uref.exec_commit_finish(exec_request_by_named_uref);
    let direct_named_uref_transforms = &test_result.builder().get_transforms()[1];

    assert_eq!(
        direct_uref_transforms, direct_named_uref_transforms,
        "transforms should match"
    );
}

#[ignore]
#[test]
fn should_have_equivalent_transforms_with_stored_contract_pointers() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 100_000_000;
    let transferred_amount = 1;

    let stored_transforms = {
        let mut builder = InMemoryWasmTestBuilder::default();

        let exec_request_1 = {
            let store_transfer = DeployItemBuilder::new()
                .with_address(DEFAULT_ACCOUNT_ADDR)
                .with_session_code(
                    &format!("{}_stored.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                    (),
                )
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
                .with_deploy_hash([1; 32])
                .build();

            ExecuteRequestBuilder::new()
                .push_deploy(store_transfer)
                .build()
        };

        let exec_request_2 = {
            let store_transfer = DeployItemBuilder::new()
                .with_address(DEFAULT_ACCOUNT_ADDR)
                .with_session_code(
                    &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (STORE_AT_HASH.to_string(),),
                )
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
                .with_deploy_hash([2; 32])
                .build();

            ExecuteRequestBuilder::new()
                .push_deploy(store_transfer)
                .build()
        };

        let store_transforms = builder
            .run_genesis(&*DEFAULT_GENESIS_CONFIG)
            .exec(exec_request_1)
            .expect_success()
            .commit()
            .exec(exec_request_2)
            .expect_success()
            .commit()
            .get_transforms()[1]
            .to_owned();

        let stored_payment_contract_hash =
            store_transforms
                .iter()
                .find_map(|key_transform| match key_transform {
                    (Key::Hash(hash), Transform::Write(StoredValue::Contract(_))) => Some(hash),
                    _ => None,
                });

        assert!(stored_payment_contract_hash.is_some());

        let call_stored_request = {
            let deploy = DeployItemBuilder::new()
                .with_address(DEFAULT_ACCOUNT_ADDR)
                .with_stored_session_named_key(
                    TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME,
                    (account_1_public_key, U512::from(transferred_amount)),
                )
                .with_stored_payment_hash(
                    stored_payment_contract_hash
                        .expect("hash should exist")
                        .to_vec(),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
                .with_deploy_hash([3; 32])
                .build();

            ExecuteRequestBuilder::new().push_deploy(deploy).build()
        };

        builder
            .exec(call_stored_request)
            .expect_success()
            .commit()
            .get_transforms()[2]
            .to_owned()
    };

    let provided_transforms = {
        let do_nothing_request = |deploy_hash: [u8; 32]| {
            let deploy = DeployItemBuilder::new()
                .with_address(DEFAULT_ACCOUNT_ADDR)
                .with_session_code(&format!("{}.wasm", DO_NOTHING_NAME), ())
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
                .with_deploy_hash(deploy_hash)
                .build();

            ExecuteRequestBuilder::new().push_deploy(deploy).build()
        };

        let provided_request = {
            let deploy = DeployItemBuilder::new()
                .with_address(DEFAULT_ACCOUNT_ADDR)
                .with_session_code(
                    &format!("{}.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                    (account_1_public_key, U512::from(transferred_amount)),
                )
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
                .with_deploy_hash([3; 32])
                .build();

            ExecuteRequestBuilder::new().push_deploy(deploy).build()
        };

        InMemoryWasmTestBuilder::default()
            .run_genesis(&*DEFAULT_GENESIS_CONFIG)
            .exec(do_nothing_request([1; 32]))
            .expect_success()
            .commit()
            .exec(do_nothing_request([2; 32]))
            .expect_success()
            .commit()
            .exec(provided_request)
            .expect_success()
            .get_transforms()[2]
            .to_owned()
    };

    let diff = AdditiveMapDiff::new(provided_transforms, stored_transforms);

    let left: BTreeMap<&Key, &Transform> = diff.left().iter().collect();
    let right: BTreeMap<&Key, &Transform> = diff.right().iter().collect();

    // The diff contains the same keys...
    assert!(Iterator::eq(left.keys(), right.keys()));

    // ...but a few different values
    for lr in left.values().zip(right.values()) {
        match lr {
            (
                Transform::Write(StoredValue::CLValue(l_value)),
                Transform::Write(StoredValue::CLValue(r_value)),
            ) => {
                // differing refunds and balances
                let _ = l_value.to_owned().into_t::<U512>().expect("should be U512");
                let _ = r_value.to_owned().into_t::<U512>().expect("should be U512");
            }
            (
                Transform::Write(StoredValue::Account(la)),
                Transform::Write(StoredValue::Account(ra)),
            ) => {
                assert_eq!(la.pub_key(), ra.pub_key());
                assert_eq!(la.purse_id(), ra.purse_id());
                assert_eq!(la.action_thresholds(), ra.action_thresholds());

                assert!(Iterator::eq(
                    la.get_associated_keys(),
                    ra.get_associated_keys()
                ));

                // la has stored contracts under named urefs
                assert_ne!(la.named_keys(), ra.named_keys());
            }
            (Transform::AddUInt512(_), Transform::AddUInt512(_)) => {
                // differing payment
            }
            _ => {
                panic!("unexpected diff");
            }
        }
    }
}

#[ignore]
#[test]
fn should_fail_payment_stored_at_named_key_with_incompatible_major_version() {
    let payment_purse_amount = 10_000_000;

    // first, store standard payment contract
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
        (STORE_AT_HASH.to_string(),),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    builder.exec_commit_finish(exec_request);

    let query_result = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query default account");
    let default_account = query_result
        .as_account()
        .expect("query result should be an account");

    assert!(
        default_account
            .named_keys()
            .contains_key(STANDARD_PAYMENT_CONTRACT_NAME),
        "standard_payment should be present"
    );

    //
    // upgrade with new wasm costs with modified mint for given version to avoid missing wasm costs
    // table that's queried early
    //
    let sem_ver = PROTOCOL_VERSION.value();
    let new_protocol_version =
        ProtocolVersion::from_parts(sem_ver.major + 1, sem_ver.minor, sem_ver.patch);

    let mut upgrade_request =
        make_upgrade_request(new_protocol_version, MODIFIED_MINT_UPGRADER_CONTRACT_NAME).build();

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    // next make another deploy that USES stored payment logic
    let exec_request_stored_payment = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(&format!("{}.wasm", DO_NOTHING_NAME), ())
            .with_stored_payment_named_key(
                STANDARD_PAYMENT_CONTRACT_NAME,
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(new_protocol_version)
            .build()
    };

    let test_result = builder.exec(exec_request_stored_payment).commit();

    assert!(
        test_result.is_error(),
        "calling a payment module with increased major protocol version should be error"
    );
    let error_message = builder
        .exec_error_message(1)
        .expect("should have exec error");
    assert!(error_message.contains(EXPECTED_ERROR_MESSAGE));
}

#[ignore]
#[test]
fn should_fail_payment_stored_at_hash_with_incompatible_major_version() {
    let payment_purse_amount = 10_000_000;

    // first, store standard payment contract
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
        (STORE_AT_HASH.to_string(),),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    builder.exec_commit_finish(exec_request);

    let query_result = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query default account");
    let default_account = query_result
        .as_account()
        .expect("query result should be an account");
    let stored_payment_contract_hash = default_account
        .named_keys()
        .get(STANDARD_PAYMENT_CONTRACT_NAME)
        .expect("should have standard_payment named key")
        .as_hash()
        .expect("standard_payment should be an uref");

    //
    // upgrade with new wasm costs with modified mint for given version to avoid missing wasm costs
    // table that's queried early
    //
    let sem_ver = PROTOCOL_VERSION.value();
    let new_protocol_version =
        ProtocolVersion::from_parts(sem_ver.major + 1, sem_ver.minor, sem_ver.patch);

    let mut upgrade_request =
        make_upgrade_request(new_protocol_version, MODIFIED_MINT_UPGRADER_CONTRACT_NAME).build();

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    // next make another deploy that USES stored payment logic
    let exec_request_stored_payment = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(&format!("{}.wasm", DO_NOTHING_NAME), ())
            .with_stored_payment_hash(
                stored_payment_contract_hash.to_vec(),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(new_protocol_version)
            .build()
    };

    let test_result = builder.exec(exec_request_stored_payment).commit();

    assert!(
        test_result.is_error(),
        "calling a payment module with increased major protocol version should be error"
    );
    let error_message = builder
        .exec_error_message(1)
        .expect("should have exec error");
    assert!(error_message.contains(EXPECTED_ERROR_MESSAGE));
}

#[ignore]
#[test]
fn should_fail_payment_stored_at_uref_with_incompatible_major_version() {
    let payment_purse_amount = 10_000_000;

    // first, store standard payment contract
    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
        (STORE_AT_UREF.to_string(),),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    builder.exec_commit_finish(exec_request);

    let query_result = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query default account");
    let default_account = query_result
        .as_account()
        .expect("query result should be an account");
    let stored_payment_contract_uref = default_account
        .named_keys()
        .get(STANDARD_PAYMENT_CONTRACT_NAME)
        .expect("should have standard_payment named key")
        .as_uref()
        .cloned()
        .expect("standard_payment should be an uref");

    //
    // upgrade with new wasm costs with modified mint for given version to avoid missing wasm costs
    // table that's queried early
    //
    let sem_ver = PROTOCOL_VERSION.value();
    let new_protocol_version =
        ProtocolVersion::from_parts(sem_ver.major + 1, sem_ver.minor, sem_ver.patch);

    let mut upgrade_request =
        make_upgrade_request(new_protocol_version, MODIFIED_MINT_UPGRADER_CONTRACT_NAME).build();

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    // next make another deploy that USES stored payment logic
    let exec_request_stored_payment = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(&format!("{}.wasm", DO_NOTHING_NAME), ())
            .with_stored_payment_uref(
                stored_payment_contract_uref,
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(new_protocol_version)
            .build()
    };

    let test_result = builder.exec(exec_request_stored_payment).commit();

    assert!(
        test_result.is_error(),
        "calling a payment module with increased major protocol version should be error"
    );
    let error_message = builder
        .exec_error_message(1)
        .expect("should have exec error");
    assert!(error_message.contains(EXPECTED_ERROR_MESSAGE));
}

#[ignore]
#[test]
fn should_fail_session_stored_at_named_key_with_incompatible_major_version() {
    let payment_purse_amount = 10_000_000;

    // first, store standard payment contract for v1.0.0
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", DO_NOTHING_NAME),
        (STORE_AT_UREF.to_string(),),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    builder.exec_commit_finish(exec_request_1);

    let query_result = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query default account");
    let default_account = query_result
        .as_account()
        .expect("query result should be an account");
    assert!(
        default_account
            .named_keys()
            .contains_key(DO_NOTHING_STORED_CONTRACT_NAME),
        "do_nothing should be present in named keys"
    );

    //
    // upgrade with new wasm costs with modified mint for given version
    //
    let sem_ver = PROTOCOL_VERSION.value();
    let new_protocol_version =
        ProtocolVersion::from_parts(sem_ver.major + 1, sem_ver.minor, sem_ver.patch);

    let mut upgrade_request =
        make_upgrade_request(new_protocol_version, MODIFIED_MINT_UPGRADER_CONTRACT_NAME).build();

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    // Call stored session code

    let exec_request_stored_payment = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_session_named_key(DO_NOTHING_STORED_CONTRACT_NAME, ())
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(new_protocol_version)
            .build()
    };

    let test_result = builder.exec(exec_request_stored_payment).commit();

    assert!(
        test_result.is_error(),
        "calling a session module with increased major protocol version should be error",
    );
    let error_message = builder
        .exec_error_message(1)
        .expect("should have exec error");
    assert!(error_message.contains(EXPECTED_ERROR_MESSAGE));
}

#[ignore]
#[test]
fn should_fail_session_stored_at_hash_with_incompatible_major_version() {
    let payment_purse_amount = 10_000_000;

    // first, store standard payment contract for v1.0.0
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", DO_NOTHING_NAME),
        (STORE_AT_HASH.to_string(),),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    builder.exec_commit_finish(exec_request_1);

    let query_result = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query default account");
    let default_account = query_result
        .as_account()
        .expect("query result should be an account");
    let do_nothing_contract_hash = default_account
        .named_keys()
        .get(DO_NOTHING_STORED_CONTRACT_NAME)
        .expect("do_nothing should be present in named keys")
        .as_hash()
        .expect("do_nothing named key should be hash");

    //
    // upgrade with new wasm costs with modified mint for given version
    //
    let sem_ver = PROTOCOL_VERSION.value();
    let new_protocol_version =
        ProtocolVersion::from_parts(sem_ver.major + 1, sem_ver.minor, sem_ver.patch);

    let mut upgrade_request =
        make_upgrade_request(new_protocol_version, MODIFIED_MINT_UPGRADER_CONTRACT_NAME).build();

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    // Call stored session code

    let exec_request_stored_payment = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_session_hash(do_nothing_contract_hash.to_vec(), ())
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(new_protocol_version)
            .build()
    };

    let test_result = builder.exec(exec_request_stored_payment).commit();

    assert!(
        test_result.is_error(),
        "calling a session module with increased major protocol version should be error",
    );
    let error_message = builder
        .exec_error_message(1)
        .expect("should have exec error");
    assert!(error_message.contains(EXPECTED_ERROR_MESSAGE));
}

#[ignore]
#[test]
fn should_fail_session_stored_at_uref_with_incompatible_major_version() {
    let payment_purse_amount = 10_000_000;

    // first, store standard payment contract for v1.0.0
    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", DO_NOTHING_NAME),
        (STORE_AT_UREF.to_string(),),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    builder.exec_commit_finish(exec_request_1);

    let query_result = builder
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query default account");
    let default_account = query_result
        .as_account()
        .expect("query result should be an account");
    let do_nothing_contract_uref = default_account
        .named_keys()
        .get(DO_NOTHING_STORED_CONTRACT_NAME)
        .expect("do_nothing should be present in named keys")
        .as_uref()
        .cloned()
        .expect("do_nothing named key should be hash");

    //
    // upgrade with new wasm costs with modified mint for given version
    //
    let sem_ver = PROTOCOL_VERSION.value();
    let new_protocol_version =
        ProtocolVersion::from_parts(sem_ver.major + 1, sem_ver.minor, sem_ver.patch);

    let mut upgrade_request =
        make_upgrade_request(new_protocol_version, MODIFIED_MINT_UPGRADER_CONTRACT_NAME).build();

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    // Call stored session code

    let exec_request_stored_payment = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_session_uref(do_nothing_contract_uref, ())
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(new_protocol_version)
            .build()
    };

    let test_result = builder.exec(exec_request_stored_payment).commit();

    assert!(
        test_result.is_error(),
        "calling a session module with increased major protocol version should be error",
    );
    let error_message = builder
        .exec_error_message(1)
        .expect("should have exec error");
    assert!(error_message.contains(EXPECTED_ERROR_MESSAGE));
}

#[ignore]
#[test]
fn should_execute_stored_payment_and_session_code_with_new_major_version() {
    let payment_purse_amount = 10_000_000;

    let mut builder = InMemoryWasmTestBuilder::default();
    builder.run_genesis(&*DEFAULT_GENESIS_CONFIG);

    //
    // upgrade with new wasm costs with modified mint for given version
    //
    let sem_ver = PROTOCOL_VERSION.value();
    let new_protocol_version =
        ProtocolVersion::from_parts(sem_ver.major + 1, sem_ver.minor, sem_ver.patch);

    let mut upgrade_request =
        make_upgrade_request(new_protocol_version, MODIFIED_SYSTEM_UPGRADER_CONTRACT_NAME).build();

    builder.upgrade_with_upgrade_request(&mut upgrade_request);

    let upgrade_response = builder
        .get_upgrade_response(0)
        .expect("should have response");

    assert!(upgrade_response.has_success(), "expected success");

    // first, store standard payment contract for v2.0.0

    let exec_request_1 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
        (STORE_AT_HASH.to_string(),),
    )
    .with_protocol_version(new_protocol_version)
    .build();

    let exec_request_2 = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        &format!("{}_stored.wasm", DO_NOTHING_NAME),
        (STORE_AT_UREF.to_string(),),
    )
    .with_protocol_version(new_protocol_version)
    .build();

    // store both contracts
    let test_result = builder
        .exec(exec_request_1)
        .expect_success()
        .commit()
        .exec(exec_request_2)
        .expect_success()
        .commit()
        .finish();

    // query both stored contracts by their named keys
    let query_result = test_result
        .builder()
        .query(None, Key::Account(DEFAULT_ACCOUNT_ADDR), &[])
        .expect("should query default account");
    let default_account = query_result
        .as_account()
        .expect("query result should be an account");
    let standard_payment_stored_hash = default_account
        .named_keys()
        .get(STANDARD_PAYMENT_CONTRACT_NAME)
        .expect("standard_payment should be present in named keys")
        .as_hash()
        .expect("standard_payment named key should be hash");
    let do_nothing_stored_uref = default_account
        .named_keys()
        .get(DO_NOTHING_STORED_CONTRACT_NAME)
        .expect("do_nothing should be present in named keys")
        .as_uref()
        .cloned()
        .expect("do_nothing named key should be hash");

    // Call stored session code

    let exec_request_stored_payment = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_stored_session_uref(do_nothing_stored_uref, ())
            .with_stored_payment_hash(
                standard_payment_stored_hash.to_vec(),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new()
            .push_deploy(deploy)
            .with_protocol_version(new_protocol_version)
            .build()
    };

    let final_test_result = InMemoryWasmTestBuilder::from_result(test_result)
        .exec(exec_request_stored_payment)
        .commit()
        .finish();

    assert!(
        !final_test_result.builder().is_error(),
        "calling upgraded stored payment and session code should work",
    );
}
