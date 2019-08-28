use std::collections::hash_map::RandomState;
use std::collections::{BTreeMap, HashMap};
use std::convert::TryInto;

use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};
use engine_core::engine_state::{EngineConfig, CONV_RATE};
use engine_shared::transform::Transform;

use crate::support::test_stored_contract_support::{
    get_account, DeployBuilder, Diff, ExecRequestBuilder, WasmTestBuilder, WasmTestResult,
    GENESIS_INITIAL_BALANCE,
};
use engine_grpc_server::engine_server::ipc::ExecuteRequest;

use crate::support::test_stored_contract_support;
use crate::support::test_support;

const GENESIS_ADDR: [u8; 32] = [12; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];
const STANDARD_PAYMENT_CONTRACT_NAME: &str = "standard_payment";
const TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME: &str = "transfer_purse_to_account";

fn get_transformed_balance(
    builder: &WasmTestBuilder,
    transforms: &HashMap<Key, Transform, RandomState>,
    account_key: &Key,
) -> U512 {
    let modified_account = {
        let account_transforms = transforms
            .get(account_key)
            .expect("Unable to find transforms for account");

        if let Transform::Write(Value::Account(account)) = account_transforms {
            account
        } else {
            panic!(
                "Transform {:?} is not a Transform with a Value(Account)",
                account_transforms
            );
        }
    };

    let purse_bytes = modified_account
        .purse_id()
        .value()
        .addr()
        .to_bytes()
        .expect("should be able to serialize purse bytes");

    let mint = builder.get_mint_contract_uref();
    let balance_mapping_key = Key::local(mint.addr(), &purse_bytes);
    let balance_uref = builder
        .query(None, balance_mapping_key, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should find balance uref");

    let balance: U512 = builder
        .query(None, balance_uref, &[])
        .and_then(|v| v.try_into().ok())
        .expect("should parse balance into a U512");

    balance
}

fn get_test_result(builder: &mut WasmTestBuilder, exec_request: ExecuteRequest) -> WasmTestResult {
    builder
        .exec_with_exec_request(exec_request)
        .expect_success() // <- assert equivalent
        .commit()
        .finish()
}

#[ignore]
#[test]
fn should_exec_non_stored_code() {
    // using the new execute logic, passing code for both payment and session
    // should work exactly as it did with the original exec logic

    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let genesis_account_key = Key::Account(genesis_addr);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 10_000_000;
    let transferred_amount = 1;

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_session_code(
                &format!("{}.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);
    builder.run_genesis(genesis_addr, HashMap::default());

    let test_result = get_test_result(&mut builder, exec_request);

    let transforms = &test_result.builder().get_transforms()[0];

    let modified_balance: U512 =
        get_transformed_balance(&builder, transforms, &genesis_account_key);

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

    let motes = test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

    let tally = U512::from(motes + transferred_amount) + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}

#[ignore]
#[test]
fn should_exec_stored_code_by_hash() {
    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let genesis_account_key = Key::Account(genesis_addr);
    let payment_purse_amount = 10_000_000;

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    // first, store standard payment contract
    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_session_code(
                &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);
    builder.run_genesis(genesis_addr, HashMap::default());

    let test_result = get_test_result(&mut builder, exec_request);

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

    let motes_alpha = test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

    let modified_balance_alpha: U512 =
        get_transformed_balance(&builder, transforms, &genesis_account_key);

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = 1;

    // next make another deploy that USES stored payment logic
    let exec_request_stored_payment = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
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
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(2)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = get_test_result(&mut builder, exec_request_stored_payment);

    let transforms = &test_result.builder().get_transforms()[1];

    let modified_balance_bravo: U512 =
        get_transformed_balance(&builder, transforms, &genesis_account_key);

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    let motes_bravo = test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

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
    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let genesis_account_key = Key::Account(genesis_addr);
    let payment_purse_amount = 10_000_000;

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    // first, store standard payment contract
    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_session_code(
                &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);
    builder.run_genesis(genesis_addr, HashMap::default());

    let test_result = get_test_result(&mut builder, exec_request);

    let response = test_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let motes_alpha = test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

    let transforms = &test_result.builder().get_transforms()[0];

    let modified_balance_alpha: U512 =
        get_transformed_balance(&builder, transforms, &genesis_account_key);

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = 1;

    // next make another deploy that USES stored payment logic
    let exec_request_stored_payment = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_session_code(
                &format!("{}.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_stored_payment_named_key(
                STANDARD_PAYMENT_CONTRACT_NAME,
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(2)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = get_test_result(&mut builder, exec_request_stored_payment);

    let transforms = &test_result.builder().get_transforms()[1];

    let modified_balance_bravo: U512 =
        get_transformed_balance(&builder, transforms, &genesis_account_key);

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    let motes_bravo = test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

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
    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let genesis_account_key = Key::Account(genesis_addr);
    let payment_purse_amount = 100_000_000; // <- seems like a lot, but it gets spent fast!

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    // first, store transfer contract
    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_session_code(
                &format!("{}_stored.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);
    builder.run_genesis(genesis_addr, HashMap::default());

    let test_result = get_test_result(&mut builder, exec_request);

    let response = test_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let motes_alpha = test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

    let transforms: &HashMap<Key, Transform, RandomState> =
        &test_result.builder().get_transforms()[0];

    let modified_balance_alpha: U512 =
        get_transformed_balance(&builder, transforms, &genesis_account_key);

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = 1;

    // next make another deploy that USES stored session logic
    let exec_request_stored_session = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_stored_session_named_key(
                TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME,
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(2)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = get_test_result(&mut builder, exec_request_stored_session);

    let transforms = &test_result.builder().get_transforms()[1];

    let modified_balance_bravo: U512 =
        get_transformed_balance(&builder, transforms, &genesis_account_key);

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    let motes_bravo = test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

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
    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let genesis_account_key = Key::Account(genesis_addr);
    let payment_purse_amount = 100_000_000; // <- seems like a lot, but it gets spent fast!

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    // first, store standard payment contract
    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_session_code(
                &format!("{}_stored.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);
    builder.run_genesis(genesis_addr, HashMap::default());

    let test_result = get_test_result(&mut builder, exec_request);

    let response = test_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let motes_alpha = test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

    // next store transfer contract
    let exec_request_store_transfer = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_session_code(
                &format!("{}_stored.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (),
            )
            .with_stored_payment_named_key(
                STANDARD_PAYMENT_CONTRACT_NAME,
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(2)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = get_test_result(&mut builder, exec_request_store_transfer);

    let response = test_result
        .builder()
        .get_exec_response(1)
        .expect("there should be a response")
        .clone();

    let motes_bravo = test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = 1;

    // next make another deploy that USES stored payment logic & stored transfer
    // logic
    let exec_request_stored_only = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_stored_session_named_key(
                TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME,
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_stored_payment_named_key(
                STANDARD_PAYMENT_CONTRACT_NAME,
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(3)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = get_test_result(&mut builder, exec_request_stored_only);

    let response = test_result
        .builder()
        .get_exec_response(2)
        .expect("there should be a response")
        .clone();

    let motes_charlie =
        test_stored_contract_support::get_success_result(&response).cost * CONV_RATE;

    let transforms = &test_result.builder().get_transforms()[2];

    let modified_balance: U512 =
        get_transformed_balance(&builder, transforms, &genesis_account_key);

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

    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let genesis_account_key = Key::Account(genesis_addr);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 100_000_000;
    let transferred_amount = 1;

    // first, store transfer contract
    let exec_request_genesis = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_session_code(
                &format!("{}_stored.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                (),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let engine_config = EngineConfig::new().set_use_payment_code(true);
    let mut builder_by_uref = WasmTestBuilder::new(engine_config);
    builder_by_uref.run_genesis(genesis_addr, HashMap::default());

    let test_result = get_test_result(&mut builder_by_uref, exec_request_genesis.clone());
    let transforms: &HashMap<Key, Transform, RandomState> =
        &test_result.builder().get_transforms()[0];

    let stored_payment_contract_uref = {
        // get pos contract public key
        let pos_uref = {
            let account = get_account(transforms, &genesis_account_key)
                .expect("genesis account should exist");
            let pos_public_key = account
                .urefs_lookup()
                .get("pos")
                .expect("there should be a pos entry");
            let pos_private_key: Value = builder_by_uref
                .query(None, *pos_public_key, &[])
                .expect("pos private key should exist");

            match pos_private_key {
                Value::Key(Key::URef(uref)) => Some(uref.remove_access_rights()),
                _ => None,
            }
        }
        .expect("should have pos uref");

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
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_stored_session_uref(
                *stored_payment_contract_uref,
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(2)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = get_test_result(&mut builder_by_uref, exec_request_by_uref);
    let direct_uref_transforms = &test_result.builder().get_transforms()[1];

    let engine_config = EngineConfig::new().set_use_payment_code(true);
    let mut builder_by_named_uref = WasmTestBuilder::new(engine_config);
    builder_by_named_uref.run_genesis(genesis_addr, HashMap::default());
    let _ = get_test_result(&mut builder_by_named_uref, exec_request_genesis);

    // named uref exec
    let exec_request_by_named_uref = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_stored_session_named_key(
                TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME,
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code(
                &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                (U512::from(payment_purse_amount),),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(2)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let test_result = get_test_result(&mut builder_by_named_uref, exec_request_by_named_uref);
    let direct_named_uref_transforms = &test_result.builder().get_transforms()[1];

    assert_eq!(
        direct_uref_transforms, direct_named_uref_transforms,
        "transforms should match"
    );
}

#[ignore]
#[test]
fn should_produce_same_transforms_as_exec() {
    // using the new execute logic, passing code for both payment and session
    // should work exactly as it did with the original exec logic

    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 1_000_000_000;
    let transferred_amount = 1;

    let config = EngineConfig::new().set_use_payment_code(true);

    let execute_transforms = {
        let config = config.clone();

        let request = {
            let deploy = DeployBuilder::new()
                .with_address(genesis_addr)
                .with_session_code(
                    &format!("{}.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                    (account_1_public_key, U512::from(transferred_amount)),
                )
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[genesis_public_key])
                .with_nonce(1)
                .build();

            ExecRequestBuilder::new().push_deploy(deploy).build()
        };

        WasmTestBuilder::new(config)
            .run_genesis(genesis_addr, HashMap::default())
            .exec_with_exec_request(request)
            .expect_success()
            .get_transforms()[0]
            .to_owned()
    };

    let exec_transforms = {
        let config = config.clone();

        let request = {
            let deploy = crate::support::test_support::DeployBuilder::new()
                .with_address(genesis_addr)
                .with_session_code(
                    &format!("{}.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                    (account_1_public_key, U512::from(transferred_amount)),
                )
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[genesis_public_key])
                .with_nonce(1)
                .build();

            test_support::ExecRequestBuilder::new()
                .push_deploy(deploy)
                .build()
        };

        test_support::WasmTestBuilder::new(config)
            .run_genesis(genesis_addr, HashMap::default())
            .exec_with_exec_request(request)
            .expect_success()
            .get_transforms()[0]
            .to_owned()
    };

    assert_eq!(execute_transforms, exec_transforms);
}

#[ignore]
#[test]
fn should_have_equivalent_transforms_with_stored_contract_pointers() {
    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 1_000_000_000;
    let transferred_amount = 1;

    let config = EngineConfig::new().set_use_payment_code(true);

    let stored_transforms = {
        let config = config.clone();

        let store_request = |name: &str, nonce: u64| {
            let store_transfer = DeployBuilder::new()
                .with_address(genesis_addr)
                .with_session_code(&format!("{}_stored.wasm", name), ())
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[genesis_public_key])
                .with_nonce(nonce)
                .build();

            ExecRequestBuilder::new()
                .push_deploy(store_transfer)
                .build()
        };

        let mut builder = WasmTestBuilder::new(config);

        let store_transforms = builder
            .run_genesis(genesis_addr, HashMap::default())
            .exec_with_exec_request(store_request(TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME, 1))
            .expect_success()
            .commit()
            .exec_with_exec_request(store_request(STANDARD_PAYMENT_CONTRACT_NAME, 2))
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
            let deploy = DeployBuilder::new()
                .with_address(genesis_addr)
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
                .with_authorization_keys(&[genesis_public_key])
                .with_nonce(3)
                .build();

            ExecRequestBuilder::new().push_deploy(deploy).build()
        };

        builder
            .exec_with_exec_request(call_stored_request)
            .expect_success()
            .commit()
            .get_transforms()[2]
            .to_owned()
    };

    let provided_transforms = {
        let config = config.clone();

        let do_nothing_request = |nonce: u64| {
            let deploy = DeployBuilder::new()
                .with_address(genesis_addr)
                .with_session_code("do_nothing.wasm", ())
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[genesis_public_key])
                .with_nonce(nonce)
                .build();

            ExecRequestBuilder::new().push_deploy(deploy).build()
        };

        let provided_request = {
            let deploy = DeployBuilder::new()
                .with_address(genesis_addr)
                .with_session_code(
                    &format!("{}.wasm", TRANSFER_PURSE_TO_ACCOUNT_CONTRACT_NAME),
                    (account_1_public_key, U512::from(transferred_amount)),
                )
                .with_payment_code(
                    &format!("{}.wasm", STANDARD_PAYMENT_CONTRACT_NAME),
                    (U512::from(payment_purse_amount),),
                )
                .with_authorization_keys(&[genesis_public_key])
                .with_nonce(3)
                .build();

            ExecRequestBuilder::new().push_deploy(deploy).build()
        };

        WasmTestBuilder::new(config)
            .run_genesis(genesis_addr, HashMap::default())
            .exec_with_exec_request(do_nothing_request(1))
            .expect_success()
            .commit()
            .exec_with_exec_request(do_nothing_request(2))
            .expect_success()
            .commit()
            .exec_with_exec_request(provided_request)
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
                assert_eq!(la.nonce(), ra.nonce());
                assert_eq!(la.purse_id(), ra.purse_id());
                assert_eq!(la.action_thresholds(), ra.action_thresholds());
                assert_eq!(la.account_activity(), ra.account_activity());

                assert!(Iterator::eq(
                    la.get_associated_keys(),
                    ra.get_associated_keys()
                ));

                // la has stored contracts under named urefs
                assert_ne!(la.urefs_lookup(), ra.urefs_lookup());
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
