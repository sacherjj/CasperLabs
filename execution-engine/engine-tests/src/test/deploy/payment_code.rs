use std::convert::TryFrom;

use engine_core::engine_state::{genesis::POS_REWARDS_PURSE, CONV_RATE, MAX_PAYMENT};
use engine_shared::{motes::Motes, stored_value::StoredValue, transform::Transform};
use engine_test_support::low_level::{
    utils, DeployItemBuilder, ExecuteRequestBuilder, InMemoryWasmTestBuilder, DEFAULT_ACCOUNT_ADDR,
    DEFAULT_ACCOUNT_INITIAL_BALANCE, DEFAULT_ACCOUNT_KEY, DEFAULT_GENESIS_CONFIG,
};
use types::{
    account::{PublicKey, PurseId},
    bytesrepr::ToBytes,
    CLValue, Key, U512,
};

const ACCOUNT_1_ADDR: [u8; 32] = [42u8; 32];
const STANDARD_PAYMENT_WASM: &str = "standard_payment.wasm";
const DO_NOTHING_WASM: &str = "do_nothing.wasm";
const CONTRACT_TRANSFER_PURSE_TO_ACCOUNT: &str = "transfer_purse_to_account.wasm";
const CONTRACT_REVERT: &str = "revert.wasm";

#[ignore]
#[test]
fn should_raise_insufficient_payment_when_caller_lacks_minimum_balance() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);

    let exec_request = ExecuteRequestBuilder::standard(
        DEFAULT_ACCOUNT_ADDR,
        CONTRACT_TRANSFER_PURSE_TO_ACCOUNT,
        (account_1_public_key, U512::from(MAX_PAYMENT - 1)),
    )
    .build();

    let mut builder = InMemoryWasmTestBuilder::default();

    let _response = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .expect_success()
        .commit()
        .get_exec_response(0)
        .expect("there should be a response")
        .to_owned();

    let account_1_request =
        ExecuteRequestBuilder::standard(ACCOUNT_1_ADDR, CONTRACT_REVERT, ()).build();

    let account_1_response = builder
        .exec(account_1_request)
        .commit()
        .get_exec_response(1)
        .expect("there should be a response");

    let error_message = utils::get_error_message(account_1_response);

    assert!(
        error_message.contains("InsufficientPaymentError"),
        "expected insufficient payment, got: {}",
        error_message
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
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(1)),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(1),))
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    let _response = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .get_exec_response(0)
        .expect("there should be a response")
        .to_owned();

    let mut modified_balance: Option<U512> = None;
    let mut reward_balance: Option<U512> = None;

    let transforms = builder.get_transforms();
    let transform = &transforms[0];

    for t in transform.values() {
        if let Transform::Write(StoredValue::CLValue(cl_value)) = t {
            if let Ok(v) = cl_value.to_owned().into_t() {
                modified_balance = Some(v);
            }
        }
        if let Transform::AddUInt512(v) = t {
            reward_balance = Some(*v);
        }
    }

    let modified_balance = modified_balance.expect("modified balance should be present");
    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);
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
        .expect("there should be a response");

    let execution_result = utils::get_success_result(response);
    let error_message = format!("{}", execution_result.error().expect("should have error"));

    assert_eq!(
        error_message, "Insufficient payment",
        "expected insufficient payment"
    );
}

#[ignore]
#[test]
fn should_raise_insufficient_payment_error_when_out_of_gas() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount: U512 = U512::from(1);
    let transferred_amount = U512::from(1);
    let expected_transfers_count = 2;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code(STANDARD_PAYMENT_WASM, (payment_purse_amount,))
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, transferred_amount),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    assert_eq!(
        transform.len(),
        expected_transfers_count,
        "unexpected forced transfer transforms count"
    );

    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);
    let expected_reward_balance: U512 = U512::from(MAX_PAYMENT);
    let mut modified_balance: Option<U512> = None;
    let mut reward_balance: Option<U512> = None;

    for t in transform.values() {
        if let Transform::Write(StoredValue::CLValue(cl_value)) = t {
            if let Ok(v) = cl_value.to_owned().into_t() {
                modified_balance = Some(v);
            }
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
        .expect("there should be a response");

    let execution_result = utils::get_success_result(response);
    let error_message = format!("{}", execution_result.error().expect("should have error"));

    assert_eq!(
        error_message, "Insufficient payment",
        "expected insufficient payment"
    );
}

#[ignore]
#[test]
fn should_forward_payment_execution_runtime_error() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = U512::from(1);
    let expected_transfers_count = 2;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code("revert.wasm", ())
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, transferred_amount),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    assert_eq!(
        transform.len(),
        expected_transfers_count,
        "unexpected forced transfer transforms count"
    );

    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);
    let expected_reward_balance: U512 = U512::from(MAX_PAYMENT);
    let mut modified_balance: Option<U512> = None;
    let mut reward_balance: Option<U512> = None;

    for t in transform.values() {
        if let Transform::Write(StoredValue::CLValue(cl_value)) = t {
            if let Ok(v) = cl_value.to_owned().into_t() {
                modified_balance = Some(v);
            }
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
        .expect("there should be a response");

    let execution_result = utils::get_success_result(response);
    let error_message = format!("{}", execution_result.error().expect("should have error"));

    assert!(
        error_message.contains("Revert(65636)"),
        "expected payment error",
    );
}

#[ignore]
#[test]
fn should_forward_payment_execution_gas_limit_error() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let transferred_amount = U512::from(1);
    let expected_transfers_count = 2;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code("endless_loop.wasm", ())
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, transferred_amount),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .finish();

    let transforms = transfer_result.builder().get_transforms();
    let transform = &transforms[0];

    assert_eq!(
        transform.len(),
        expected_transfers_count,
        "unexpected forced transfer transforms count"
    );

    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);
    let expected_reward_balance: U512 = U512::from(MAX_PAYMENT);
    let mut modified_balance: Option<U512> = None;
    let mut reward_balance: Option<U512> = None;

    for t in transform.values() {
        if let Transform::Write(StoredValue::CLValue(cl_value)) = t {
            if let Ok(v) = cl_value.to_owned().into_t() {
                modified_balance = Some(v);
            }
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
        .expect("there should be a response");

    let execution_result = utils::get_success_result(response);
    let error_message = format!("{}", execution_result.error().expect("should have error"));

    assert!(
        error_message.contains("GasLimit"),
        "expected gas limit error"
    );
}

#[ignore]
#[test]
fn should_run_out_of_gas_when_session_code_exceeds_gas_limit() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 10_000_000;
    let transferred_amount = 1;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_session_code(
                "endless_loop.wasm",
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    let transfer_result = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .finish();

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response");

    let execution_result = utils::get_success_result(response);
    let error_message = format!("{}", execution_result.error().expect("should have error"));

    assert!(
        error_message.contains("GasLimit"),
        "expected gas limit, got {}",
        error_message
    );
}

#[ignore]
#[test]
fn should_correctly_charge_when_session_code_runs_out_of_gas() {
    let payment_purse_amount = 10_000_000;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_session_code("endless_loop.wasm", ())
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    let transfer_result = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .finish();

    let default_account = transfer_result
        .builder()
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance: U512 = transfer_result
        .builder()
        .get_purse_balance(default_account.purse_id());
    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);

    assert_ne!(
        modified_balance, initial_balance,
        "balance should be less than initial balance"
    );

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response");

    let success_result = utils::get_success_result(&response);
    let gas = success_result.cost();
    let motes = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let tally = motes.value() + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );

    let execution_result = utils::get_success_result(response);
    let error_message = format!("{}", execution_result.error().expect("should have error"));

    assert!(error_message.contains("GasLimit"), "expected gas limit");
}

#[ignore]
#[test]
fn should_correctly_charge_when_session_code_fails() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 10_000_000;
    let transferred_amount = 1;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_session_code(
                "revert.wasm",
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    let transfer_result = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .commit()
        .finish();

    let default_account = transfer_result
        .builder()
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance: U512 = transfer_result
        .builder()
        .get_purse_balance(default_account.purse_id());
    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);

    assert_ne!(
        modified_balance, initial_balance,
        "balance should be less than initial balance"
    );

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let success_result = utils::get_success_result(&response);
    let gas = success_result.cost();
    let motes = Motes::from_gas(gas, CONV_RATE).expect("should have motes");
    let tally = motes.value() + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
}

#[ignore]
#[test]
fn should_correctly_charge_when_session_code_succeeds() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 10_000_000;
    let transferred_amount = 1;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    let transfer_result = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(exec_request)
        .expect_success()
        .commit()
        .finish();

    let default_account = transfer_result
        .builder()
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get genesis account");
    let modified_balance: U512 = transfer_result
        .builder()
        .get_purse_balance(default_account.purse_id());
    let initial_balance: U512 = U512::from(DEFAULT_ACCOUNT_INITIAL_BALANCE);

    assert_ne!(
        modified_balance, initial_balance,
        "balance should be less than initial balance"
    );

    let response = transfer_result
        .builder()
        .get_exec_response(0)
        .expect("there should be a response")
        .clone();

    let success_result = utils::get_success_result(&response);
    let gas = success_result.cost();
    let motes = Motes::from_gas(gas, CONV_RATE).expect("should have motes");
    let total = motes.value() + U512::from(transferred_amount);
    let tally = total + modified_balance;

    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    );
    assert_eq!(
        initial_balance, tally,
        "no net resources should be gained or lost post-distribution"
    )
}

fn get_pos_purse_id_by_name(
    builder: &InMemoryWasmTestBuilder,
    purse_name: &str,
) -> Option<PurseId> {
    let pos_contract = builder.get_pos_contract();

    pos_contract
        .named_keys()
        .get(purse_name)
        .and_then(Key::as_uref)
        .map(|u| PurseId::new(*u))
}

fn get_pos_rewards_purse_balance(builder: &InMemoryWasmTestBuilder) -> U512 {
    let purse_id = get_pos_purse_id_by_name(builder, POS_REWARDS_PURSE)
        .expect("should find PoS payment purse");
    builder.get_purse_balance(purse_id)
}

#[ignore]
#[test]
fn should_finalize_to_rewards_purse() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 10_000_000;
    let transferred_amount = 1;

    let exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(transferred_amount)),
            )
            .with_payment_code("standard_payment.wasm", (U512::from(payment_purse_amount),))
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([1; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let mut builder = InMemoryWasmTestBuilder::default();

    builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let rewards_purse_balance = get_pos_rewards_purse_balance(&builder);
    assert!(rewards_purse_balance.is_zero());

    builder.exec(exec_request).expect_success().commit();

    let rewards_purse_balance = get_pos_rewards_purse_balance(&builder);
    assert!(!rewards_purse_balance.is_zero());
}

#[ignore]
#[test]
fn independent_standard_payments_should_not_write_the_same_keys() {
    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = 10_000_000;
    let transfer_amount = 10_000_000;

    let mut builder = InMemoryWasmTestBuilder::default();

    let setup_exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (account_1_public_key, U512::from(transfer_amount)),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([1; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    // create another account via transfer
    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(setup_exec_request)
        .expect_success()
        .commit();

    let exec_request_from_genesis = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(DO_NOTHING_WASM, ())
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let exec_request_from_account_1 = {
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_session_code(DO_NOTHING_WASM, ())
            .with_payment_code(STANDARD_PAYMENT_WASM, (U512::from(payment_purse_amount),))
            .with_authorization_keys(&[account_1_public_key])
            .with_deploy_hash([1; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    // run two independent deploys
    builder
        .exec(exec_request_from_genesis)
        .expect_success()
        .commit()
        .exec(exec_request_from_account_1)
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

    let account_1_public_key = PublicKey::new(ACCOUNT_1_ADDR);
    let payment_purse_amount = U512::from(10_000_000);
    let account_1_funding_amount = U512::from(100_000_000);
    let account_1_purse_funding_amount = U512::from(50_000_000);

    let mut builder = InMemoryWasmTestBuilder::default();

    // arrange
    let setup_exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_session_code(
                "transfer_purse_to_account.wasm", // creates account_1
                (account_1_public_key, account_1_funding_amount),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (payment_purse_amount,))
            .with_authorization_keys(&[*DEFAULT_ACCOUNT_KEY])
            .with_deploy_hash([1; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let create_purse_exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_session_code(
                "transfer_main_purse_to_new_purse.wasm", // creates test purse
                (TEST_PURSE_NAME, account_1_purse_funding_amount),
            )
            .with_payment_code(STANDARD_PAYMENT_WASM, (payment_purse_amount,))
            .with_authorization_keys(&[account_1_public_key])
            .with_deploy_hash([2; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec(setup_exec_request)
        .expect_success()
        .commit()
        .exec(create_purse_exec_request)
        .expect_success()
        .commit()
        .finish();

    // get account_1
    let account_1 = transfer_result
        .builder()
        .get_account(ACCOUNT_1_ADDR)
        .expect("should have account");
    // get purse
    let purse_id_key = account_1.named_keys()[TEST_PURSE_NAME];
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
            .and_then(|v| CLValue::try_from(v).ok())
            .and_then(|cl_value| cl_value.into_t().ok())
            .expect("should find balance uref");

        let balance: U512 = builder
            .query(None, balance_uref, &[])
            .and_then(|v| CLValue::try_from(v).ok())
            .and_then(|cl_value| cl_value.into_t().ok())
            .expect("should parse balance into a U512");

        balance
    };

    assert_eq!(
        purse_starting_balance, account_1_purse_funding_amount,
        "purse should be funded with expected amount"
    );

    // should be able to pay for exec using new purse
    let account_payment_exec_request = {
        let deploy = DeployItemBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_session_code(DO_NOTHING_WASM, ())
            .with_payment_code(
                "named_purse_payment.wasm",
                (TEST_PURSE_NAME, payment_purse_amount),
            )
            .with_authorization_keys(&[account_1_public_key])
            .with_deploy_hash([3; 32])
            .build();

        ExecuteRequestBuilder::new().push_deploy(deploy).build()
    };

    let transfer_result = builder
        .exec(account_payment_exec_request)
        .expect_success()
        .commit()
        .finish();

    let response = transfer_result
        .builder()
        .get_exec_response(2)
        .expect("there should be a response")
        .clone();

    let result = utils::get_success_result(&response);
    let gas = result.cost();
    let motes = Motes::from_gas(gas, CONV_RATE).expect("should have motes");

    let expected_resting_balance = account_1_purse_funding_amount - motes.value();

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
            .and_then(|v| CLValue::try_from(v).ok())
            .and_then(|cl_value| cl_value.into_t().ok())
            .expect("should find balance uref");

        let balance: U512 = builder
            .query(None, balance_uref, &[])
            .and_then(|v| CLValue::try_from(v).ok())
            .and_then(|cl_value| cl_value.into_t().ok())
            .expect("should parse balance into a U512");

        balance
    };

    assert_eq!(
        purse_final_balance, expected_resting_balance,
        "purse resting balance should equal funding amount minus exec costs"
    );
}
