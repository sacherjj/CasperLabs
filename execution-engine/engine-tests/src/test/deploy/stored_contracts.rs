use std::collections::hash_map::RandomState;
use std::collections::{BTreeMap, HashMap};

use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::CONV_RATE;
use engine_shared::transform::Transform;

use crate::support::test_support::{
    self, DeployItemBuilder, Diff, ExecuteRequestBuilder, InMemoryWasmTestBuilder,
    GENESIS_INITIAL_BALANCE,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_ACCOUNT_KEY, DEFAULT_GENESIS_CONFIG};

const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];
const STANDARD_PAYMENT_CONTRACT_NAME: &str = "standard_payment";
const TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME: &str = "transfer_purse_to_account";

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

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    assert_ne!(
        modified_balance, initial_balance,
        "balance should be less than initial balance"
    );

    let response = test_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let motes = test_support::get_success_result(&response).cost * CONV_RATE;

    let tally = U512::from(motes + transferred_amount) + modified_balance;

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
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
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

    let transforms = &test_result.builder().get_transforms()[0];

    // find the contract write transform, then get the hash from its key
    let stored_payment_contract_hash = {
        let mut ret = None;
        for (k, t) in transforms {
            if let Transform::Write(Value::Contract(_)) = t {
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

    let motes_alpha = test_support::get_success_result(&response).cost * CONV_RATE;

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

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    let motes_bravo = test_support::get_success_result(&response).cost * CONV_RATE;

    let tally = U512::from(motes_alpha + motes_bravo + transferred_amount) + modified_balance_bravo;

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
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
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

    let motes_alpha = test_support::get_success_result(&response).cost * CONV_RATE;

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

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    let motes_bravo = test_support::get_success_result(&response).cost * CONV_RATE;

    let tally = U512::from(motes_alpha + motes_bravo + transferred_amount) + modified_balance_bravo;

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

    let motes_alpha = test_support::get_success_result(&response).cost * CONV_RATE;

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

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    let motes_bravo = test_support::get_success_result(&response).cost * CONV_RATE;

    let tally = U512::from(motes_alpha + motes_bravo + transferred_amount) + modified_balance_bravo;

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
    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
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

    let motes_alpha = test_support::get_success_result(&response).cost * CONV_RATE;

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

    let motes_bravo = test_support::get_success_result(&response).cost * CONV_RATE;

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

    let motes_charlie = test_support::get_success_result(&response).cost * CONV_RATE;

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance: U512 = builder.get_purse_balance(default_account.purse_id());

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    let tally = U512::from(motes_alpha + motes_bravo + motes_charlie + transferred_amount)
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

    let test_result = builder_by_uref.exec_commit_finish(exec_request_genesis.clone());
    let transforms: &HashMap<Key, Transform, RandomState> =
        &test_result.builder().get_transforms()[0];

    let stored_payment_contract_uref = {
        // get pos contract public key
        let pos_uref = {
            let account = builder_by_uref
                .get_account(DEFAULT_ACCOUNT_ADDR)
                .expect("genesis account should exist");
            account
                .named_keys()
                .get("pos")
                .and_then(Key::as_uref)
                .expect("should have pos uref")
                .to_owned()
        };

        // find the contract write transform, then get the uref from its key
        // the pos contract gets re-written when the refund purse uref is removed from
        // it and therefore there are two URef->Contract Writes present in
        // transforms... we want to ignore the proof of stake URef as it is not
        // the one we are interested in
        let stored_payment_contract_uref = transforms
            .iter()
            .find_map(|key_transform| match key_transform {
                (Key::URef(uref), Transform::Write(Value::Contract(_))) if uref != &pos_uref => {
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
    let payment_purse_amount = 1_000_000_000;
    let transferred_amount = 1;

    let stored_transforms = {
        let store_request = |name: &str, deploy_hash: [u8; 32]| {
            let store_transfer = DeployItemBuilder::new()
                .with_address(DEFAULT_ACCOUNT_ADDR)
                .with_session_code(&format!("{}_stored.wasm", name), ())
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
                .with_deploy_hash(deploy_hash)
                .build();

            ExecuteRequestBuilder::new()
                .push_deploy(store_transfer)
                .build()
        };

        let mut builder = InMemoryWasmTestBuilder::default();

        let store_transforms = builder
            .run_genesis(&*DEFAULT_GENESIS_CONFIG)
            .exec(store_request(
                TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME,
                [1; 32],
            ))
            .expect_success()
            .commit()
            .exec(store_request(STANDARD_PAYMENT_CONTRACT_NAME, [2; 32]))
            .expect_success()
            .commit()
            .get_transforms()[1]
            .to_owned();

        let stored_payment_contract_hash =
            store_transforms
                .iter()
                .find_map(|key_transform| match key_transform {
                    (Key::Hash(hash), Transform::Write(Value::Contract(_))) => Some(hash),
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
                .with_session_code("do_nothing.wasm", ())
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

    let diff = Diff::new(provided_transforms, stored_transforms);

    let left: BTreeMap<&Key, &Transform> = diff.left().iter().collect();
    let right: BTreeMap<&Key, &Transform> = diff.right().iter().collect();

    // The diff contains the same keys...
    assert!(Iterator::eq(left.keys(), right.keys()));

    // ...but a few different values
    for lr in left.values().zip(right.values()) {
        match lr {
            (Transform::Write(Value::UInt512(_)), Transform::Write(Value::UInt512(_))) => {
                // differing refunds and balances
            }
            (Transform::Write(Value::Account(la)), Transform::Write(Value::Account(ra))) => {
                assert_eq!(la.pub_key(), ra.pub_key());
                assert_eq!(la.purse_id(), ra.purse_id());
                assert_eq!(la.action_thresholds(), ra.action_thresholds());
                assert_eq!(la.account_activity(), ra.account_activity());

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
