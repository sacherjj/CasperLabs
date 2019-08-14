extern crate grpc;

extern crate casperlabs_engine_grpc_server;
extern crate contract_ffi;
extern crate engine_core;
extern crate engine_shared;
extern crate engine_storage;

use std::collections::HashMap;
use std::convert::TryInto;

use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::{Value, U512};

use engine_core::engine_state::{EngineConfig, CONV_RATE, MAX_PAYMENT};
use engine_shared::transform::Transform;
use test_support::{DeployBuilder, ExecRequestBuilder, WasmTestBuilder, GENESIS_INITIAL_BALANCE};

#[allow(dead_code)]
mod test_support;

const GENESIS_ADDR: [u8; 32] = [12; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];

#[ignore]
#[test]
fn should_raise_insufficient_payment_when_caller_lacks_minimum_balance() {
    let genesis_public_key = PublicKey::new(GENESIS_ADDR);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(100)),
            )
            .with_payment_code("standard_payment.wasm", U512::from(100))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let response = WasmTestBuilder::new(engine_config)
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_exec_request(exec_request)
        .get_exec_response(0)
        .expect("there should be a response")
        .to_owned();

    let error_message = {
        let execution_result = test_support::get_success_result(&response);
        test_support::get_error_message(execution_result)
    };

    assert_eq!(
        error_message, "Insufficient payment",
        "expected insufficient payment"
    );
}

#[ignore]
#[test]
fn should_raise_insufficient_payment_when_payment_code_fails() {
    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount: U512 = U512::from(1_000_000);
    let transferred_amount = U512::from(1);
    let expected_transfers_count = 2;

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_payment_code("revert.wasm", payment_purse_amount)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, transferred_amount),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);

    let transfer_result = builder
        .run_genesis(genesis_addr, HashMap::default())
        .exec_with_exec_request(exec_request)
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    assert_eq!(
        transform.len(),
        expected_transfers_count,
        "unexpected forced transfer transforms count"
    );

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);
    let expected_reward_balance: U512 = U512::from(MAX_PAYMENT);
    let mut modified_balance: Option<U512> = None;
    let mut reward_balance: Option<U512> = None;

    for t in transform.values() {
        if let Transform::Write(Value::UInt512(v)) = t {
            modified_balance = Some(*v);
        }
        if let Transform::AddUInt512(v) = t {
            reward_balance = Some(*v);
        }
    }

    let modified_balance = modified_balance.expect("modified balance should be present");

    assert_eq!(
        modified_balance,
        initial_balance - expected_reward_balance,
        "modified balance is incorrect"
    );

    let reward_balance = reward_balance.expect("reward balance should be present");

    assert_eq!(
        reward_balance, expected_reward_balance,
        "reward balance is incorrect"
    );

    assert_eq!(
        initial_balance,
        (modified_balance + reward_balance),
        "no net resources should be gained or lost post-distribution"
    );

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let execution_result = test_support::get_success_result(&response);
    let error_message = test_support::get_error_message(execution_result);

    assert_eq!(
        error_message, "Insufficient payment",
        "expected insufficient payment"
    );
}

#[ignore]
#[test]
fn should_run_out_of_gas_when_session_code_exceeds_gas_limit() {
    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 10_000_000;
    let transferred_amount = 1;

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_payment_code("standard_payment.wasm", U512::from(payment_purse_amount))
            .with_session_code(
                "endless_loop.wasm",
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);

    let transfer_result = builder
        .run_genesis(genesis_addr, HashMap::default())
        .exec_with_exec_request(exec_request)
        .commit()
        .finish();

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let execution_result = test_support::get_success_result(&response);
    let error_message = test_support::get_error_message(execution_result);

    assert_eq!(error_message, "GasLimit", "expected gas limit");
}

#[ignore]
#[test]
fn should_correctly_charge_when_session_code_runs_out_of_gas() {
    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let genesis_account_key = Key::Account(genesis_addr);
    let payment_purse_amount = 10_000_000;

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(genesis_addr)
            .with_payment_code("standard_payment.wasm", U512::from(payment_purse_amount))
            .with_session_code("endless_loop.wasm", ())
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);

    let transfer_result = builder
        .run_genesis(genesis_addr, HashMap::default())
        .exec_with_exec_request(exec_request)
        .commit()
        .finish();

    let transforms = &transfer_result.builder().get_transforms()[0];

    let modified_balance: U512 = {
        let modified_account = {
            let account_transforms = transforms
                .get(&genesis_account_key)
                .expect("Unable to find transforms for genesis");
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
    };

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    assert_ne!(
        modified_balance, initial_balance,
        "balance should be less than initial balance"
    );

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let motes = test_support::get_success_result(&response).cost * CONV_RATE;

    let tally = U512::from(motes) + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );

    let execution_result = test_support::get_success_result(&response);
    let error_message = test_support::get_error_message(execution_result);

    assert_eq!(error_message, "GasLimit", "expected gas limit");
}

#[ignore]
#[test]
fn should_correctly_charge_when_session_code_fails() {
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
            .with_payment_code("standard_payment.wasm", U512::from(payment_purse_amount))
            .with_session_code(
                "revert.wasm",
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);

    let transfer_result = builder
        .run_genesis(genesis_addr, HashMap::default())
        .exec_with_exec_request(exec_request)
        .commit()
        .finish();

    let transforms = &transfer_result.builder().get_transforms()[0];

    let modified_balance: U512 = {
        let modified_account = {
            let account_transforms = transforms
                .get(&genesis_account_key)
                .expect("Unable to find transforms for genesis");
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
    };

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    assert_ne!(
        modified_balance, initial_balance,
        "balance should be less than initial balance"
    );

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let motes = test_support::get_success_result(&response).cost * CONV_RATE;

    let tally = U512::from(motes) + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}

#[ignore]
#[test]
fn should_correctly_charge_when_session_code_succeeds() {
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
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code("standard_payment.wasm", U512::from(payment_purse_amount))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);

    let transfer_result = builder
        .run_genesis(genesis_addr, HashMap::default())
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit()
        .expect_success() //<-- assert equivalent
        .finish();

    let transforms = &transfer_result.builder().get_transforms()[0];

    let modified_balance: U512 = {
        let modified_account = {
            let account_transforms = transforms
                .get(&genesis_account_key)
                .expect("Unable to find transforms for genesis");
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
    };

    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);

    assert_ne!(
        modified_balance, initial_balance,
        "balance should be less than initial balance"
    );

    let response = transfer_result
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
