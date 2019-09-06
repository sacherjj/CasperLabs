use std::collections::HashMap;
use std::convert::TryInto;

use contract_ffi::bytesrepr::ToBytes;
use contract_ffi::key::Key;
use contract_ffi::value::account::{PublicKey, PurseId};
use contract_ffi::value::{Value, U512};

use crate::support::test_support::{
    DeployBuilder, ExecRequestBuilder, WasmTestBuilder, GENESIS_INITIAL_BALANCE,
};
use engine_core::engine_state::{EngineConfig, CONV_RATE, MAX_PAYMENT};
use engine_shared::transform::Transform;

use crate::support::test_support;

const GENESIS_ADDR: [u8; 32] = [12; 32];
const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];
const STANDARD_PAYMENT_WASM: &str = "standard_payment.wasm";
const DO_NOTHING_WASM: &str = "do_nothing.wasm";

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
                (account_1_public_key, U512::from(MAX_PAYMENT - 1)),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(MAX_PAYMENT),))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);

    let _response = builder
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_exec_request(exec_request)
        .expect_success()
        .commit()
        .get_exec_response(0)
        .expect("there should be a response")
        .to_owned();

    let account_1_request = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_session_code("revert.wasm", ())
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(MAX_PAYMENT - 1),))
            .with_authorization_keys(&[account_1_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let account_1_response = builder
        .exec_with_exec_request(account_1_request)
        .commit()
        .get_exec_response(1)
        .expect("there should be a response")
        .to_owned();

    let error_message = {
        let execution_result =
            crate::support::test_support::get_success_result(&account_1_response);
        test_support::get_error_message(execution_result)
    };

    assert_eq!(
        error_message, "Insufficient payment",
        "expected insufficient payment"
    );

    let expected_transfers_count = 0;
    let transforms = builder.get_transforms();
    let transform = &transforms[1];

    assert_eq!(
        transform.len(),
        expected_transfers_count,
        "there should be no transforms if the account main purse has less than max payment"
    );
}

#[ignore]
#[test]
fn should_raise_insufficient_payment_when_payment_code_does_not_pay_enough() {
    let genesis_public_key = PublicKey::new(GENESIS_ADDR);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(1)),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(1),))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = WasmTestBuilder::new(engine_config);

    let _response = builder
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_exec_request(exec_request)
        .commit()
        .get_exec_response(0)
        .expect("there should be a response")
        .to_owned();

    let mut modified_balance: Option<U512> = None;
    let mut reward_balance: Option<U512> = None;

    let transforms = builder.get_transforms();
    let transform = &transforms[0];

    for t in transform.values() {
        if let Transform::Write(Value::UInt512(v)) = t {
            modified_balance = Some(*v);
        }
        if let Transform::AddUInt512(v) = t {
            reward_balance = Some(*v);
        }
    }

    let modified_balance = modified_balance.expect("modified balance should be present");
    let initial_balance: U512 = U512::from(GENESIS_INITIAL_BALANCE);
    let expected_reward_balance: U512 = U512::from(MAX_PAYMENT);

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

    let response = builder
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let execution_result = crate::support::test_support::get_success_result(&response);
    let error_message = crate::support::test_support::get_error_message(execution_result);

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
            .with_payment_code("revert.wasm", (payment_purse_amount,))
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, transferred_amount),
            )
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = WasmTestBuilder::new(engine_config)
        .run_genesis(genesis_addr, HashMap::default())
        .exec_with_exec_request(exec_request)
        .commit()
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

    let execution_result = crate::support::test_support::get_success_result(&response);
    let error_message = crate::support::test_support::get_error_message(execution_result);

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
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
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

    let execution_result = crate::support::test_support::get_success_result(&response);
    let error_message = crate::support::test_support::get_error_message(execution_result);

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
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
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

    let motes = crate::support::test_support::get_success_result(&response).cost * CONV_RATE;

    let tally = U512::from(motes) + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );

    let execution_result = crate::support::test_support::get_success_result(&response);
    let error_message = crate::support::test_support::get_error_message(execution_result);

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
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
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

    let motes = crate::support::test_support::get_success_result(&response).cost * CONV_RATE;

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
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
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

    let motes = crate::support::test_support::get_success_result(&response).cost * CONV_RATE;

    let tally = U512::from(motes + transferred_amount) + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}

#[ignore]
#[test]
fn independent_standard_payments_should_not_write_the_same_keys() {
    let genesis_public_key = PublicKey::new(GENESIS_ADDR);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 10_000_000;

    let engine_config = EngineConfig::new().set_use_payment_code(true);

    let mut builder = WasmTestBuilder::new(engine_config);

    let setup_exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(payment_purse_amount)),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    // create another account via transfer
    builder
        .run_genesis(GENESIS_ADDR, HashMap::default())
        .exec_with_exec_request(setup_exec_request)
        .expect_success()
        .commit();

    let exec_request_from_genesis = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_session_code(DO_NOTHING_WASM, ())
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(2)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_from_account_1 = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_session_code(DO_NOTHING_WASM, ())
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_authorization_keys(&[account_1_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    // run two independent deploys
    builder
        .exec_with_exec_request(exec_request_from_genesis)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_from_account_1)
        .expect_success()
        .commit();

    let transforms = builder.get_transforms();
    let transforms_from_genesis = &transforms[1];
    let transforms_from_account_1 = &transforms[2];

    // confirm the two deploys have no overlapping writes
    let common_write_keys = transforms_from_genesis.keys().filter(|k| {
        match (
            transforms_from_genesis.get(k),
            transforms_from_account_1.get(k),
        ) {
            (Some(Transform::Write(_)), Some(Transform::Write(_))) => true,
            _ => false,
        }
    });

    assert_eq!(common_write_keys.count(), 0);
}

#[ignore]
#[test]
fn should_charge_non_main_purse() {
    // as account_1, create & fund a new purse and use that to pay for something
    // instead of account_1 main purse
    const TEST_PURSE_NAME: &str = "test-purse";

    let genesis_addr = GENESIS_ADDR;
    let genesis_public_key = PublicKey::new(genesis_addr);
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = U512::from(10_000_000);
    let account_1_funding_amount = U512::from(100_000_000);
    let account_1_purse_funding_amount = U512::from(50_000_000);

    let engine_config = EngineConfig::new().set_use_payment_code(true);
    let mut builder = WasmTestBuilder::new(engine_config);

    // arrange
    let setup_exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(GENESIS_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm", // creates account_1
                (account_1_public_key, account_1_funding_amount),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (payment_purse_amount,))
            .with_authorization_keys(&[genesis_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let create_purse_exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_session_code(
                "transfer_main_purse_to_new_purse.wasm", // creates test purse
                (TEST_PURSE_NAME, account_1_purse_funding_amount),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (payment_purse_amount,))
            .with_authorization_keys(&[account_1_public_key])
            .with_nonce(1)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = builder
        .run_genesis(genesis_addr, HashMap::default())
        .exec_with_exec_request(setup_exec_request)
        .expect_success()
        .commit()
        .exec_with_exec_request(create_purse_exec_request)
        .expect_success()
        .commit()
        .finish();

    // get account_1
    let account_1 = transfer_result
        .builder()
        .get_account(Key::Account(ACCOUNT_1_ADDR))
        .expect("should have account");
    // get purse
    let purse_id_key = account_1.urefs_lookup()[TEST_PURSE_NAME];
    let purse_id = PurseId::new(*purse_id_key.as_uref().expect("should have uref"));

    let purse_starting_balance = {
        let purse_bytes = purse_id
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

    assert_eq!(
        purse_starting_balance, account_1_purse_funding_amount,
        "purse should be funded with expected amount"
    );

    // should be able to pay for exec using new purse
    let account_payment_exec_request = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_session_code(DO_NOTHING_WASM, ())
            .with_payment_code(
                "named_purse_payment.wasm",
                (TEST_PURSE_NAME, payment_purse_amount),
            )
            .with_authorization_keys(&[account_1_public_key])
            .with_nonce(2)
            .build();

        ExecRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = builder
        .exec_with_exec_request(account_payment_exec_request)
        .expect_success()
        .commit()
        .finish();

    let response = transfer_result
        .builder()
        .get_exec_response(2)
        .expect("there should be a response")
        .clone();

    let motes = crate::support::test_support::get_success_result(&response).cost * CONV_RATE;

    let expected_resting_balance = account_1_purse_funding_amount - motes;

    let purse_final_balance = {
        let purse_bytes = purse_id
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

    assert_eq!(
        purse_final_balance, expected_resting_balance,
        "purse resting balance should equal funding amount minus exec costs"
    );
}
