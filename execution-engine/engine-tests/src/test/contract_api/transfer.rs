use crate::support::test_support::{
    self, DeployBuilder, ExecRequestBuilder, InMemoryWasmTestBuilder, STANDARD_PAYMENT_CONTRACT,
};
use crate::test::{DEFAULT_ACCOUNT_ADDR, DEFAULT_GENESIS_CONFIG};
use contract_ffi::value::account::PublicKey;
use contract_ffi::value::U512;
use engine_core::engine_state::CONV_RATE;
use engine_core::engine_state::MAX_PAYMENT;
use engine_shared::motes::Motes;

const INITIAL_GENESIS_AMOUNT: u64 = 100_000_000_000;

const TRANSFER_1_AMOUNT: u64 = (MAX_PAYMENT * 5) + 1000;
const TRANSFER_2_AMOUNT: u32 = 750;

const TRANSFER_2_AMOUNT_WITH_ADV: u64 = MAX_PAYMENT + TRANSFER_2_AMOUNT as u64;
const TRANSFER_TOO_MUCH: u64 = u64::max_value();

const ACCOUNT_1_ADDR: [u8; 32] = [1u8; 32];
const ACCOUNT_2_ADDR: [u8; 32] = [2u8; 32];
const ACCOUNT_1_INITIAL_BALANCE: u64 = MAX_PAYMENT;

#[ignore]
#[test]
fn should_transfer_to_account() {
    let initial_genesis_amount: U512 = U512::from(INITIAL_GENESIS_AMOUNT);
    let transfer_amount: U512 = U512::from(TRANSFER_1_AMOUNT);

    // Run genesis
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account");

    let default_account_purse_id = default_account.purse_id();

    // Check genesis account balance
    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    assert_eq!(genesis_balance, initial_genesis_amount,);

    // Exec transfer contract

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("transfer_to_account_01.wasm", (ACCOUNT_1_ADDR,))
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();

        ExecRequestBuilder::from_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit();

    let account = builder
        .get_account(ACCOUNT_1_ADDR)
        .expect("should get account");
    let account_purse_id = account.purse_id();

    // Check genesis account balance

    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    let gas_cost = Motes::from_gas(builder.get_exec_costs(0)[0], CONV_RATE)
        .expect("should convert gas to motes");

    assert_eq!(
        genesis_balance,
        initial_genesis_amount - gas_cost.value() - transfer_amount
    );

    // Check account 1 balance

    let account_1_balance = builder.get_purse_balance(account_purse_id);

    assert_eq!(account_1_balance, transfer_amount,);
}

#[ignore]
#[test]
fn should_transfer_from_account_to_account() {
    let initial_genesis_amount: U512 = U512::from(INITIAL_GENESIS_AMOUNT);
    let transfer_1_amount: U512 = U512::from(TRANSFER_1_AMOUNT);
    let transfer_2_amount: U512 = U512::from(TRANSFER_2_AMOUNT);

    // Run genesis
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account");

    let default_account_purse_id = default_account.purse_id();

    // Check genesis account balance
    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    assert_eq!(genesis_balance, initial_genesis_amount,);

    // Exec transfer 1 contract

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("transfer_to_account_01.wasm", (ACCOUNT_1_ADDR,))
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();

        ExecRequestBuilder::from_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit();

    let exec_1_response = builder
        .get_exec_response(0)
        .expect("should have exec response");

    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    let gas_cost = Motes::from_gas(test_support::get_exec_costs(&exec_1_response)[0], CONV_RATE)
        .expect("should convert");

    assert_eq!(
        genesis_balance,
        initial_genesis_amount - gas_cost.value() - transfer_1_amount
    );

    // Check account 1 balance
    let account_1 = builder
        .get_account(ACCOUNT_1_ADDR)
        .expect("should have account 1");
    let account_1_purse_id = account_1.purse_id();
    let account_1_balance = builder.get_purse_balance(account_1_purse_id);

    assert_eq!(account_1_balance, transfer_1_amount,);

    // Exec transfer 2 contract

    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_deploy_hash([2; 32])
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(
                "transfer_to_account_02.wasm",
                (U512::from(TRANSFER_2_AMOUNT),),
            )
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_1_ADDR)])
            .build();

        ExecRequestBuilder::from_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit();

    let exec_2_response = builder
        .get_exec_response(1)
        .expect("should have exec response");

    let account_2 = builder
        .get_account(ACCOUNT_2_ADDR)
        .expect("should have account 2");

    let account_2_purse_id = account_2.purse_id();

    // Check account 1 balance

    let account_1_balance = builder.get_purse_balance(account_1_purse_id);

    let gas_cost = Motes::from_gas(test_support::get_exec_costs(&exec_2_response)[0], CONV_RATE)
        .expect("should convert");

    assert_eq!(
        account_1_balance,
        transfer_1_amount - gas_cost.value() - transfer_2_amount
    );

    let account_2_balance = builder.get_purse_balance(account_2_purse_id);

    assert_eq!(account_2_balance, transfer_2_amount,);
}

#[ignore]
#[test]
fn should_transfer_to_existing_account() {
    let initial_genesis_amount: U512 = U512::from(INITIAL_GENESIS_AMOUNT);
    let transfer_1_amount: U512 = U512::from(TRANSFER_1_AMOUNT);
    let transfer_2_amount: U512 = U512::from(TRANSFER_2_AMOUNT);

    // Run genesis
    let mut builder = InMemoryWasmTestBuilder::default();

    let builder = builder.run_genesis(&DEFAULT_GENESIS_CONFIG);

    let default_account = builder
        .get_account(DEFAULT_ACCOUNT_ADDR)
        .expect("should get account");

    let default_account_purse_id = default_account.purse_id();

    // Check genesis account balance
    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    assert_eq!(genesis_balance, initial_genesis_amount,);

    // Exec transfer 1 contract

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("transfer_to_account_01.wasm", (ACCOUNT_1_ADDR,))
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();

        ExecRequestBuilder::from_deploy(deploy).build()
    };

    builder
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit();

    // Exec transfer contract

    let account_1 = builder
        .get_account(ACCOUNT_1_ADDR)
        .expect("should get account");

    let account_1_purse_id = account_1.purse_id();

    // Check genesis account balance

    let genesis_balance = builder.get_purse_balance(default_account_purse_id);

    let gas_cost = Motes::from_gas(builder.get_exec_costs(0)[0], CONV_RATE)
        .expect("should convert gas to motes");

    assert_eq!(
        genesis_balance,
        initial_genesis_amount - gas_cost.value() - transfer_1_amount
    );

    // Check account 1 balance

    let account_1_balance = builder.get_purse_balance(account_1_purse_id);

    assert_eq!(account_1_balance, transfer_1_amount,);

    // Exec transfer contract

    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_deploy_hash([2; 32])
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(
                "transfer_to_account_02.wasm",
                (U512::from(TRANSFER_2_AMOUNT),),
            )
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_1_ADDR)])
            .build();

        ExecRequestBuilder::from_deploy(deploy).build()
    };
    builder
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit();

    let account_2 = builder
        .get_account(ACCOUNT_2_ADDR)
        .expect("should get account");

    let account_2_purse_id = account_2.purse_id();

    // Check account 1 balance

    let account_1_balance = builder.get_purse_balance(account_1_purse_id);

    let gas_cost = Motes::from_gas(builder.get_exec_costs(1)[0], CONV_RATE)
        .expect("should convert gas to motes");

    assert_eq!(
        account_1_balance,
        transfer_1_amount - gas_cost.value() - transfer_2_amount,
    );

    // Check account 2 balance

    let account_2_balance_transform = builder.get_purse_balance(account_2_purse_id);

    assert_eq!(account_2_balance_transform, transfer_2_amount);
}

#[ignore]
#[test]
fn should_fail_when_insufficient_funds() {
    // Run genesis

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_deploy_hash([1; 32])
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code("transfer_to_account_01.wasm", (ACCOUNT_1_ADDR,))
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_deploy_hash([2; 32])
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(
                "transfer_to_account_02.wasm",
                (U512::from(TRANSFER_2_AMOUNT_WITH_ADV),),
            )
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_1_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    let exec_request_3 = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_deploy_hash([2; 32])
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(
                "transfer_to_account_02.wasm",
                (U512::from(TRANSFER_TOO_MUCH),),
            )
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_1_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    let result = InMemoryWasmTestBuilder::default()
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        // Exec transfer contract
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        // Exec transfer contract
        .exec_with_exec_request(exec_request_2)
        .expect_success()
        .commit()
        // Exec transfer contract
        .exec_with_exec_request(exec_request_3)
        // .expect_success()
        .commit()
        .finish();

    assert_eq!(
        "Trap(Trap { kind: Unreachable })",
        result
            .builder()
            .get_exec_error_message(2)
            .expect("should have error message"),
    )
}

#[ignore]
#[test]
fn should_transfer_total_amount() {
    let mut builder = test_support::InMemoryWasmTestBuilder::default();

    let exec_request_1 = {
        let deploy = DeployBuilder::new()
            .with_address(DEFAULT_ACCOUNT_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),))
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (ACCOUNT_1_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
            )
            .with_deploy_hash([1u8; 32])
            .with_authorization_keys(&[PublicKey::new(DEFAULT_ACCOUNT_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };

    let exec_request_2 = {
        let deploy = DeployBuilder::new()
            .with_address(ACCOUNT_1_ADDR)
            .with_payment_code(STANDARD_PAYMENT_CONTRACT, (U512::from(MAX_PAYMENT),)) // New account transfers exactly N motes to new account (total amount)
            .with_session_code(
                "transfer_purse_to_account.wasm",
                (ACCOUNT_2_ADDR, U512::from(ACCOUNT_1_INITIAL_BALANCE)),
            )
            .with_deploy_hash([2u8; 32])
            .with_authorization_keys(&[PublicKey::new(ACCOUNT_1_ADDR)])
            .build();
        ExecRequestBuilder::from_deploy(deploy).build()
    };
    builder
        .run_genesis(&DEFAULT_GENESIS_CONFIG)
        .exec_with_exec_request(exec_request_1)
        .expect_success()
        .commit()
        .exec_with_exec_request(exec_request_2)
        .commit()
        .expect_success()
        .finish();
}
